# C2 plan browser state — partial #52

Local validation: 2026-11-02. Branch: `feature/52-c2-plan-list-state`.
Scope follows the lead's [recorded authorization](https://github.com/BRMRs/group33/issues/52#issuecomment-6494662516).

The read-only component consumes a trusted host-supplied repository, scope, Elder ID
and execution-zone local date. It observes confirmed plans, medication details/photo
references and seven inclusive dates of repository-calculated candidates. It does
not load photo bytes, create events, schedule alarms, or call plan mutations.
Observation retry is not a server refresh. Each collection retains its own cache
metadata; observation failures retain the previous snapshot marked stale.
Access withdrawal hides data, revocation is latched for that scope/Elder, and
context replacement clears details and cancels prior observations.

## Verification

- `:android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug`: passed.
- `PlanBrowserViewModelTest`: 9 tests, zero failures/errors.
- Repository policy check: passed.
- Tests cover leap/year ranges, details with no end date and photo reference,
  empty versus missing cache, stale failure/recovery, revocation, context replacement,
  unchanged preview instants/revision matching, wrong Elder and out-of-range payloads.
- Repository test mutation methods fail immediately if called. No event repository
  is accepted by this component.

## Remaining scope

This is not completion of #52. Real host/Room/HTTP integration, bilingual pages,
device accessibility and TalkBack acceptance remain outstanding. Time conversion,
DST gap/overlap vectors and exclusion of frozen events belong to the preview adapter
and have not been newly validated here. The component does not implement conversion
or authoritative overlap detection. Cross-stream snapshot atomicity is not promised
by the current interfaces; candidates from a different plan revision are hidden.
No human review or approval is claimed.
