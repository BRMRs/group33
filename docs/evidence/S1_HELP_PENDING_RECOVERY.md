# S1 Help pending recovery — #17 / #24 / PR #160

## Failure and correction

The durable reminder runtime records Help in Room before synchronization. The Debug integration then returned `PERMANENT_FAILURE` without making a Server request because the r1 route cannot represent Help. That local classification invented a terminal rejection and prevented a later reviewed adapter from recovering the original action identity.

The adapter now returns `RETRY` for that exact unsupported branch. WorkManager retains the original Room action and action ID as Pending work. Taken, Snooze, explicit Server rejection, environment/account fences and existing recovery behavior are unchanged. No route, wire model, retry identity or user-visible sync state is added.

## Verification

On the connected Xiaomi API27 device, the unchanged tracer assertion first failed with expected `RETRY` / actual `PERMANENT_FAILURE` and zero transport calls. After the correction:

- the same focused case passed;
- all 10 `RuntimeActionBoundaryTest` cases passed;
- all 8 `ReminderDispatchRecoveryTest` cases passed;
- clean offline `gate1Check test` passed 109 tasks (57 executed, 51 from cache, one up-to-date);
- repository policy, all artifact hashes and `git diff --check` passed.

## Boundary

This is truthful retention, not Help delivery. PR #131 remains proposal-only and still requires exact-head human acceptance. Server Help ingestion, original receipt/current projection, caregiver visibility, notification delivery and two-device evidence remain unimplemented. Until those exist, the ordinary Elder UI must not claim the Server or caregiver accepted the local Help action.
