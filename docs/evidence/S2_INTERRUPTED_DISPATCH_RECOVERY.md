# Interrupted reminder dispatch recovery

Owner/contributor: XinyanXue (S2). Refs #17/#24 and [#119 C1 review](https://github.com/BRMRs/group33/pull/119#pullrequestreview-5943248465).
Isolated repair on S1's #119 head `82f794fe4a83d74a7eb93cab18f15efa63a70a03`.
Original authorship and branch remain intact. S1/Android and non-author review are required;
this document is not approval or evidence that #119's blocking review is resolved.

## Reproduction and repair

Actions and phase facts persist DISPATCHING before transport, but recovery scans selected only
PENDING. A restarted Worker encountering an unexpired claim returned success, losing its retry.
Repeated recovery also used REPLACE, cancelling unfinished unique work.

Recovery now includes PENDING and DISPATCHING, excludes terminal facts, returns retry on a busy
claim, and uses KEEP for existing unfinished work. Cancellation propagates with the durable claim
intact. Internal dispatch functions called by the actual Workers make the existing path testable
with real Room and injected transport. The existing 60-second claim duration is unchanged.

No action/group identity, original timestamp, payload, deadline or business outcome is regenerated.
Acceptance alone permits ACK; terminal replay does not send. Server/Shared/Debug HTTP adapters,
Room schema/version and V2 wire/reducers are unchanged. Phase-fact recovery does not implement
new probes, alerts or transport guarantees.

## Evidence and practical limits

- Before production changes: disposable local Android 37 ARM64, three real Room tests, two scan
  regressions failed and the stable-identity/stale-reclaim control passed. After repair the same
  three methods plus five Worker/unique-work/cancellation cases passed (8/8). Task evidence retains
  original logs, APK/source hashes and individual results.
- A two-process probe uses persistent Room and the actual dispatch function. Its loopback test
  receiver commits then holds its response; the host force-stops the uploading app. A different
  process reads the claimed row, returns retry while fresh, then crosses the lease with an injected
  clock. The same action/payload is resent. Two identical requests produce one idempotent test
  effect; only a received acceptance produces durable ACK. Terminal replay sends nothing.
  Local proof has distinct PIDs, two dispatch attempts, and unchanged Taken state/deadline.
- The receiver is an injected test adapter, not the production Server/PG or a caregiver device.
  The probe uses a dedicated Room DB and the same function as the Worker; automatic startup to
  Server is not claimed end to end. Real recovery queries and unique WorkManager scheduling have
  separate tests. Lease expiry is injected rather than a real-minute timing claim.
- API35 CI retains all original 13 runtime/notification cases, strict result parsing and screenshot.
  It additionally runs eight recovery tests plus the probe's one verification test after killed
  preparation. Any added failure fails the runner. Exact-head results are recorded on the PR.
- Local Android 37 is not labelled API35, physical-device, Chinese/TalkBack or audible evidence.
  E's large-font manual notification evidence remains separately attributable to E.

## Reproduction and integration

Use the existing `scripts/run_runtime_action_api35.py` CI entry point. For an explicitly disposable
emulator, use `scripts/check_reminder_dispatch_recovery.py --adb PATH --serial emulator-N --output DIR`.
It force-stops the test app and uses a dedicated probe DB; it is not for personal installations.
No dependency, migration, cloud/SMS/provider or real identity is added.

The repair PR targets #119's existing branch, exposing only this repair rather than S1's entire
unmerged runtime. The lead can adopt it after normal review; no original review is auto-resolved.
Rollback reverts code/scheduling, without rewriting stored actions or terminal facts. #130/#131
joint decisions, capability merges, security approval and dual-device/LAN acceptance remain separate.

## 2026-11-20 additional verification

Two earlier limits are tested separately without changing production App/Worker/Server code or
accepted wire/schema. These are complementary traces, not one Android-to-production-Server trace
and not a V2 or real-provider acceptance claim.

### Real startup and WorkManager

An opt-in test APK (`-PautomaticRecoveryRunner=true`) installs a test Application inheriting the
unchanged production `Group33Application` bootstrap. It uses the ordinary reminders DB, actual
Coordinator local transaction and real WorkManager; only loopback transport is injected. Ordinary
builds retain AndroidJUnitRunner and the existing Debug application.

After the receiver accepts and holds the first response, the host force-stops the app. Startup in
a different process discovers the claimed record. Verification never calls enqueue, claim or a
dispatch helper: it observes the original work UUID, an actual busy-claim retry and automatic retry
after the claim expires. The saved trace distinguishes pre-kill attempt 1 from
new-process ENQUEUED attempts 2/3 and successful attempt 4. Local Android37 evidence observed 105,271ms between
successful claims, the same work/action/payload, two requests/one test-receiver effect and durable
ACK with the original deadline. No manual recovery call is used.

CI runs this after the original 13 cases, eight Room/Worker cases and deterministic process probe.
It clears only its explicitly disposable installation before preparation so earlier pending fixtures
cannot reach the new receiver. It never clears between preparation and new-process verification.

### Real packaged Server and PostgreSQL

`check_postgres_receipt_recovery.py` creates an isolated random loopback test schema using existing
V1–V10 SQL fixtures, starts the actual Boot JAR in `postgres-local` mode and performs real anonymous
authentication, pairing/consent, private photo upload, two plan confirmations and batch registration.
No memory Store, mocked receiver or modified business endpoint is involved in this backend flow.
Fixture SQL setup does not claim Flyway-managed rollout validation.

A proxy forwards a whole-group Snooze, receives committed success, then drops only its client
response. Three retries preserve the original receipt and two event rows; same-key changed intent
conflicts. A separate legitimate Taken changes current events while old Snooze/registration receipts
stay original. After stopping the whole Server JVM and starting a different JVM on the same schema,
both action receipts, current authorized caregiver reads and counts remain correct: two registered
occurrences, two current event rows and two distinct legitimate command receipts. Retries add no rows.
The local backend flow passed 62 assertions, representing checks in one flow rather than 62 defects.

Initial local setup failed because the fixture object directory was not 0700. The private adapter
correctly rejected it; fixture permissions were corrected and original failure evidence retained.
No production permission check was relaxed. Keys/objects are removed on success; failed private logs
are excluded from uploads. Both JVMs/proxy stop, the schema is dropped and local orchestration restores
the practice database's original stopped state.

Exact-head API35 CI adds automatic process verification (23 device report instances total) and the
real PostgreSQL flow. Raw UUIDs/logs, real elapsed time, original receipts, row counts, process IDs and
cleanup results must be verified before declaring that head successful. Earlier Android37/API35
evidence stays attributable to its own run.

## 2026-11-27 current-main hardening

The production lease remains 60 seconds. The opt-in disposable API35 test application now overrides
only that lease to 20 seconds, while still using the production startup and WorkManager path, original
work UUID and real automatic retry. This removes a real-minute test wait without changing production
behavior. The runtime clock is now supplied by the application boundary so deadline, recovery,
notification and Worker decisions can use an injected clock in tests. Historical 105-second evidence
above remains attributed to its original head and is not relabelled as a new run.
