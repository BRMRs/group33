# C2 prospective deactivation — partial #53

2026-11-05, branch feature/53-c2-deactivate-plan, based on current main.
Lead scope authorization: issue #53 comment 6494662943. C2 owner Iris-wly;
S1 BRMRs and S2 XinyanXue review command/lifecycle and revision semantics.

PlanDeactivation consumes the existing deactivate/resume interfaces for one immutable
plan ID and expected revision with a host-provided DEACTIVATE_PLAN journal context.
Construction does not dispatch. The host calls deactivate only for an explicit user
action and closes the consumer on access/scope invalidation on main. No event/history
writer is accepted. Deactivation is prospective: already generated events retain
their original meaning; a confirmed command does not establish delivery to an Elder.

The lifecycle/command consumer pattern is derived from C2 PR #108, including its
synchronous-close fix, but this branch does not include that unmerged PR. A shared
consumer extraction is deferred until the two scopes are reviewed.

Nine JVM cases cover explicit dispatch, repeat taps, original revision/key on explicit
not-submitted retry, rejection, unknown completion, cache reconciliation, recovery-only
reconstruction, synchronous close, late receipts, scope and command validation.
Strict doubles reject any plan operation other than deactivate. These tests do not
prove durable storage, server transactions, real process recovery or history retention
in a production adapter.

Validation command: :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug.
Remaining: trusted host/journal.begin wiring, explicit confirmation UI and bilingual
prospective explanation, rejected retry UI, real adapters, actual process/device and
cross-device acceptance. No shared API/schema/permission or migration change.
