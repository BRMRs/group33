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
