# C2 edit conflict and command consumer — partial #53

Verified 2026-11-03 on feature/53-c2-edit-conflict. Scope authorized by the
lead in https://github.com/BRMRs/group33/issues/53#issuecomment-6494662943.

PlanEditDraft preserves input and the confirmed revision on conflict. Newer matching
plans require explicit review before revision adoption; inactive/unrelated/regressing
records are rejected. Rebased input needs revalidation and a new command identity.

PlanEditOperation consumes update/resume using a host-provided journal context.
It freezes draft times, serializes calls, keeps exceptions/cancellation after dispatch
as unknown, and validates recovery identity/scope/kind/target/result reference.
Confirmed cache recovery cannot downgrade a server commit. Only definitive
PLAN_VERSION_CONFLICT opens conflict draft recovery. A reconstructed consumer is
recovery-only and never submits its reconstructed input. Close prevents late receipts
from restoring UI state; the host must call it on access/scope invalidation on main.

## Validation

- PlanEditDraftTest: 5 cases; PlanEditOperationTest: 12 cases.
- :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug
- Repository policy and diff whitespace checks.
- No UI changes or device acceptance claimed.

## Remaining #53

Production journal.begin/HTTP/Room/host wiring, real process-kill recovery,
rejected-command retry UI, validation/localized error mapping, bilingual pages and
actual lifecycle/device evidence remain open. Deactivation is a separate delivery.
The restoration tests use supplied journal records and do not prove durable recovery.
No canonical event writer, authority-overlap algorithm or shared contract change is
introduced. D01/D05/D06 full integration and human approvals are not claimed.
