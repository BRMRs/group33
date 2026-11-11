# S1 command recovery foundation

Refs #17. Owner: BRMRs (S1). This independent main-based slice implements existing `OnlineCommandRepository` over merged #45; it does not depend on session PR #84 or change Shared/API/Repository declarations, Room schema, dependencies or permissions.

## Consumption and observable behavior

`CommandRecoveryCoordinator` is internal infrastructure, not registered in the app host. Feature owners still canonicalize and bind their original inputs, perform the initial online submission, decode HTTP and supply idempotent scoped Room cache reconciliation. This coordinator consumes attempted entries and their original private payload; it never manufactures an operation ID during recovery.

- `begin` durably creates scoped local attempt metadata only. `observeCommands` observes the existing Room journal, with no payload in UI records. Missing IDs/unsupported recovery adapters return typed INVALID_INPUT; storage failure returns STORAGE_UNAVAILABLE. Room Flow storage exceptions propagate; no empty-success fallback hides journal loss.
- Independent same-process instances share a private change counter. Each write/transaction attempt wakes readers, which query a fresh Room snapshot; there is no in-memory copy of business state. This covers a fresh writer committing before Room's asynchronous invalidation service registration completes. Room multi-instance invalidation stays enabled. This bridge does not promise reliable first-write live notification across processes; a future separate-process host needs an explicit ready/invalidation bridge. Cold-start reads still restore persisted state.
- `resume` leaves NOT_SUBMITTED and REJECTED untouched. An AWAITING_CONFIRMATION entry claims the original key/payload before transport; another live owner prevents a duplicate dispatch. A replacement claim fences old results. Same-boot monotonic lease expiry or a different actual boot permits later recovery; claim ownership is not proof of server acceptance.
- `retryRejected` acts only on a stored rejection and requires trusted current online authentication. It reuses the original bytes/key. A content correction creates a new intent; neither background recovery nor this API can change old bytes. Applying the server's current authorization/revision/precondition remains the HTTP/service adapter's responsibility.
- A local 120-second HTTP budget, thrown transport failure or unknown reply retains AWAITING_CONFIRMATION. Parent cancellation propagates and keeps the persisted claim/state, so a later process/worker can recover after lease expiry. No automatic loop or WorkManager registration is added.
- OPERATION_IN_PROGRESS (including an adapter mistakenly classifying that code as ordinary rejection) holds the durable claim during the required one-second Retry-After, then releases it as unknown. A concurrent or process-restored caller cannot bypass that delay. Other HTTP status/header decoding, including 401 invalidation, 429/503 Retry-After and connect timeout, belongs to the feature HTTP adapter; it must honor applicable delay before returning Unknown. Unknown is never proof of non-submission.
- A confirmed resultRef is persisted as CONFIRMED/REFRESH_REQUIRED **before** cache work. Failed/thrown cache reconciliation returns this confirmed record; it never becomes a failed service save. Later resume only reconciles cache, without resending the business command. APPLIED is marked only after successful idempotent reconciliation. Repeated/concurrent reconciliation must be safe in the feature adapter.

`RecoveryAccess` is implemented by trusted host/session infrastructure and returns OFFLINE/SESSION_NOT_READY or one captured authenticated generation. `RecoverySession.scope` must equal the journal scope and `isCurrent` is rechecked before transport, result persistence and cache acknowledgement. A stale generation cannot confirm/apply the response through this coordinator; it leaves recovery evidence intact. The HTTP adapter uses the same captured generation for credentials. It must fence actual cache writes/host publication against session changes as well: a check before a callback is not an atomic transaction with the session store. UI must not implement this gate or supply arbitrary principals. No test session/transport is registered as production.

## Boot/time source and persistence

`AndroidJournalClock.now` uses elapsedRealtime (including deep sleep) plus public Settings.Global.BOOT_COUNT on API24+. API23 reads the kernel's boot_id UUID, which remains stable within a boot. It does not fall back to a per-process UUID, wall-clock estimate or another identity source after failure. An unavailable/denied/malformed source stops recovery through typed storage failure, preserving existing commands. API23 kernel access is vendor-dependent; emulator evidence does not prove every OEM build. No boot identity is logged or sent in wire requests.

The Room v1 schema, original receipt/payload and no-backup scope isolation remain unchanged. No destructive migration, cleanup or removal of accepted evidence is added. Reverting this unused coordinator leaves records readable by #45; pending claims expire safely or can be recovered on a new boot.

## Verification record

Real Room tests use public journal APIs, never DAO shortcuts or an in-memory database. Transport/session/cache fixtures are scripted and prove only coordinator policy: unattempted/rejected non-dispatch, exact key/byte preservation after reopen/response loss, explicit rejection retry/offline, accepted-receipt-before-cache, confirmed cache failure/reconciliation-only resume, independent-instance single owner, replaced claim, stale session, cancellation, Retry-After claim retention, foreign scope/unsupported adapter/closed storage.

The app-context platform probe checks monotonic stable-boot readings. Its explicit prepare / force-stop / verify-process / actual emulator reboot / verify-reboot phases retain private expected state, prove a different PID does not steal a live same-boot claim, and prove a changed boot reclaims the exact original intent. This is separate from the default single-method clock check. No real HTTP, JWT, Server transaction, business-cache transaction, worker/alarm scheduling, OEM-wide compatibility or two-device success is claimed. Exact device/full-gate results and final CI are recorded in the PR after execution.

The initial candidate passed 23 core methods on each API23/API35 emulator (12 baseline journal, 10 recovery, 1 clock). Review then identified confirmation lost on journal cache-ack/readback failure; further timeout checks exposed adapter-local transport/cache timeouts incorrectly propagating cancellation. All three were actually reproduced as failures with their original assertions before correction. Four added regressions cover these and true parent cancellation during cache work. An intermediate corrected candidate contained 27 methods (12 baseline + 14 recovery + 1 clock); the final 28-method result is recorded below. Known durable confirmation now survives subsequent local storage failures conservatively as REFRESH_REQUIRED; true parent cancellation still propagates.

The three explicit probe phases each passed on API23 and API35: prepare, different-process same-boot claim protection, and actual emulator-reboot original-intent recovery. These are three executions of one probe per API, not six independent business cases. They ran before the subsequent confirmation/timeout-only corrections; clock, schema, key/payload and boot-claim logic were unchanged. Full `gate1Check test`, lint/APK/bootJar, policy, manifest and whitespace results are recorded against the final candidate, without claiming all cached baseline methods ran afresh.

One corrected-candidate API35 run exposed an existing baseline observer timeout (27 methods, one failure in the unchanged `observersRecoverIdentityAndSeeOtherConnectionStateChangesWithoutPayload`). Its isolated rerun passed; that rerun was not treated as a repair. The same-process notification bridge above fixes the fresh-writer registration window, and an additional regression repeats fresh independent writer/active observer setup 20 times without relaxing or quarantining the original test. Final API23 and API35 suites each pass **28 methods** (12 original journal + 15 recovery + 1 clock), zero failures/skips. Original failing logs are retained separately. The DAO gains only a snapshot query; the exported Room v1 schema is unchanged.

## Remaining cross-role boundaries

| Boundary | Owner | Required next evidence |
|---|---|---|
| Login method, real auth HTTP, role/current-Elder host and repository lifecycle | C1 tianiridescent + S2 XinyanXue; S1 supports private session/lease glue | Reconcile #49 with accepted API; capture verified server identity, scope and generation, cancel/deactivate old host; actual login/logout/switch tests |
| Feature canonical request + HTTP reply + scoped Room cache adapter | C1 pairing; E photo; C2 plans; C3 observations; S2 endpoints | Each feature consumes the same original context and verified session; idempotent cache writes and failure/response-loss tests; S1 supports coordinator wiring |
| V2 group Taken/Remind later/Help, caregiver takeover, probes/notification payload and audit | #24: S1/S2 plus E/C2/C3 consumption | Named Android/Server contract closure before implementing affected outbox/receipts or alarm transitions; fixed timing itself is already decided |
| Automatic workers, reminders and full two-device causal path | S1 scheduling with E/C1/C2/C3 + S2 integration | Trusted host/feature adapters and V2 state dependencies above; actual process/reboot/background/device evidence, not fake tests |

These boundaries stop the affected wiring. They do not require discarding or waiting to write this independently testable shared foundation. #10/#17 remain open; this document supplies no human review or Gate1 signature.

## Primary references checked 2026-10-29

- [Settings.Global.BOOT_COUNT](https://developer.android.com/reference/android/provider/Settings.Global#BOOT_COUNT)
- [SystemClock.elapsedRealtime](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime())
- [Linux kernel random/boot_id](https://docs.kernel.org/admin-guide/sysctl/kernel.html#random)
- [Room multi-instance invalidation service](https://developer.android.com/reference/androidx/room/MultiInstanceInvalidationService): instances connect to a service; same-process direct notification avoids depending on registration timing for first writes.
