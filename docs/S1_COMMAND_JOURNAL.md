# S1 local online-command journal

Partial delivery for [#17](https://github.com/BRMRs/group33/issues/17), based on main `746e7fd`. Owner: BRMRs (S1). Reviewers: tianiridescent (C1, session/calling boundary), XinyanXue (S2, original request/receipt semantics). This does not close #17 or Gate 1.

## Integration boundary

`core/data/journal/RoomCommandJournal` is private Android infrastructure for feature Repository adapters, not a ViewModel dependency or an HTTP implementation. It preserves the existing [Repository contract](contracts/REPOSITORY_INTERFACES.md#approved-t01t07-adapter-obligations) and Shared types. No #37 policy code is copied into this branch.

```text
begin -> persist NOT_SUBMITTED metadata / original operationId
bind -> atomically retain immutable canonical bytes
claim -> atomically persist AWAITING_CONFIRMATION + owner token before returning bytes
finish -> current token alone records unknown / definitive rejection / confirmation
CONFIRMED -> reconcile cache only; never dispatch again
```

Feature adapters own P0_CANONICAL_V1 canonicalization and verified current session/online authorization. They must recheck these before dispatch; a journal scope is not authorization. `CommandClaim.payloadBytes()` belongs only to the internal transport adapter. Observers receive existing `OnlineCommandRecord` metadata, never bound bytes. Do not log contexts, payloads, errors, pairing codes or personal identifiers.

`ClaimMode.EXPLICIT` is allowed only from NOT_SUBMITTED/REJECTED; `RESUME_UNKNOWN` only from AWAITING_CONFIRMATION. An unbound command cannot dispatch. Corrected content needs a new `begin`; retry retains the original context and bytes. The adapter maps timeouts, cancellation, expired credentials and OPERATION_IN_PROGRESS to unknown completion, not definitive rejection. A claimed attempt remains unknown after interruption even if HTTP did not actually start; it must not be called accepted or silently downgraded to an unattempted request. Retry-After and network budgets are future coordinator responsibilities.

Supply `JournalTime` from a trusted monotonic adapter. `bootSession` must be stable across processes within the same boot and change only after a real boot; do not generate it per process or use wall time. No production clock/boot provider or lease-duration default is included. Within a boot, a lease is live strictly before its deadline; at the deadline or on a different boot it can be replaced atomically. An owner may finish while its token is still current; after replacement its completion is rejected. No heartbeat/lease renewal is implemented. Lease/fencing controls local results, not exactly-once external HTTP: server idempotency remains necessary when old network work overlaps recovery.

The host owns journal lifetime: stop/join active calls and cancel observers before `close()`. Calls/collections started after close fail; opening a fresh instance with the same trusted scope restores records. Flow invalidation is enabled between independent Room connections. Disk/open/transaction failures propagate to the future feature adapter, which must map them to typed local STORAGE_UNAVAILABLE without claiming success. This component does not implement the full OnlineCommandRepository or UI error mapping.

## Storage and rollback

Room 2.7.2 uses KAPT matching the existing Kotlin 2.1.21; AGP/Gradle/Kotlin versions are unchanged. Room 2.6.1's compiler failed against the actual Kotlin metadata, so it was replaced before publication. References: [Room release notes](https://developer.android.com/jetpack/androidx/releases/room#2.7.2), [device database testing](https://developer.android.com/training/data-storage/room/testing-db).

Initial exported schema is version 1. Each environment/installation/user tuple has a length-delimited SHA-256 filename in the app-private `noBackupFilesDir`. This separates scopes and excludes backup restoration; hashing a filename is not encryption. Bound bytes and accepted evidence remain throughout P0. There is no deletion/purge API or destructive migration fallback. No previously shipped journal schema exists, so no upgrade path is claimed. Once shipped, changes require a new forward migration and upgrade tests; never rewrite the released v1 schema. Rolling back this unused adapter leaves its files intact. Reverting after production integration must preserve pending/accepted records and schema compatibility.

## Verification (2026-10-23)

Real file-backed Room through the public journal seam, on the connected Xiaomi MI PAD 4 PLUS / Android 8.1 / API 27, using fictional data only. Tests close/reopen actual databases and use two independent Room instances; they never query DAOs or substitute in-memory persistence.

| Test coverage | Evidence / relevant contract cases |
|---|---|
| Identity and immutable binding across close/reopen | First two tests: operationId/scope/target and original binding survive; changed bytes refused (T05 local identity/binding preparation; Server S01/S02 remain outstanding) |
| Initial and unknown-recovery concurrency | Two separate connection races, one winner each; replacement fences the old result (T05 local claim preparation; no business/outbox transaction) |
| Expiry, new-boot timeline, defensive byte copies | Exact-deadline recovery, live-lease refusal, original bytes retained; synthetic boot input, not actual reboot evidence |
| Unknown, rejected and confirmed/cache recovery | Unknown keeps key; rejection/error details survive and cannot auto-replay; confirmed only updates cache (T05 online-command local subset; S05 cache integration still pending) |
| Scope, context and failure boundaries | Each scope dimension isolated, ambiguous concatenations distinct, forged kind/target refused; invalid/overflowing lease and closed handle cannot claim success (T07/S08 scope preparation; no snapshot merge or capability sequence) |
| Observation | Metadata identity recovered and state changes observed across connections without exposing payload |

12 instrumentation tests passed. `gate1Check test` passed: Shared 16, Server 30, Android Debug 25 and Release 25 executions, all without failures/errors/skips; lint has 0 errors (38 version/icon/KAPT advisory warnings), APK and bootJar build. Repository policy, full artifact hashes and diff checks passed. TDD cycles 1–8 first failed at the new behavior, then passed individually; an additional full regression found and fixed Room's ability to reopen a closed handle. These are adapter subsets, not completed S01–S09 end-to-end acceptance. The original app APK is restored after device verification; only unique test databases/test package are removed, without clearing app data.

Reproduce on a designated test device with JDK 17 / SDK 35:

```bash
./gradlew :android-app:connectedDebugAndroidTest
./gradlew gate1Check test
python3 scripts/check_repository_policy.py
shasum -a 256 -c docs/contracts/GATE1_ARTIFACTS.sha256
```

The recorded device run installed test APKs with replacement and invoked `RoomCommandJournalTest` via AndroidJUnitRunner to preserve the existing app/data, rather than relying on automatic package cleanup. Device tests are separate from CI's JVM/lint/build gate.

Still outstanding in #17: actual process death/reboot and API23/35 tests; trusted clock/session/WorkManager integration; HTTP and server receipts; feature domain/cache/outbox atomic transactions and typed failure mapping; offline Elder actions and capability sequence; cross-device recovery. No UI change, new permission, wire field or product timing change is included. #37 policy integration follows separately after review/merge.

## Current-main revalidation — 2026-10-23

Updated onto main `e740d0b` (#25/#36/#38). Journal production files, its 12 device tests, schema and build configuration are byte-identical to the device-tested `422264c`; no new device run or reboot evidence is claimed. The original API27 evidence remains applicable to these unchanged files. Current JVM reports are Shared 18, Server 30, Android Debug/Release 25 each (98 executions). The final commit/CI and named reviewer requests are recorded in PR #45; full integration remains open in #17.

## Current-main publication revalidation — 2026-10-25

Pinned inputs: main `64ddd4e26440d60a60f209dd7eb12d102e2a55dd`, PR head `c6f385885d791753642c3f744fbc8df168684e43`. An isolated working directory incorporates current main, retains the merged #39/#44 changes and independent contribution records, and rebuilds the artifact manifest from both controlled file sets. Journal source, its 12 instrumentation tests, schema and build configuration remain byte-identical to the original PR head; no unmerged #37 policies are copied.

Local gate1Check/test, lint/APK/bootJar, instrumentation APK, policy, 133 artifact hashes and diff checks pass. Android Debug/Release each execute 40 tests; Shared18/Server40 are cached results, all without failures/errors/skips. The current API27 Xiaomi device rerun executes all 12 file-backed Room tests successfully (5.876s); the original APK is restored with identical SHA-256, the test package and unique databases are removed, and app data is not cleared. These are database reopen/synthetic boot-input tests, not actual process/boot acceptance. The final head, new CI and auxiliary Standards/Spec review are recorded in PR #45; old-head CI does not validate this merge. Final author inspection and C1/S2 human reviews remain necessary. Because S1 is the author, the lead must record a named non-author Android-infrastructure backup for the Room-specific review; C1's session review alone does not implicitly assign that backup. This does not close #17 or claim the deferred integration capabilities above.
