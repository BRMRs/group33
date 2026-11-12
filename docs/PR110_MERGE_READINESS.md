# PR #110 merge readiness and review routing

This is the merge checklist for the scoped [shared-entry online integration PR](https://github.com/BRMRs/group33/pull/110), not acceptance of all of #15 or Gate 1. The lead authorized fixing merge blockers and merging only after the conditions are satisfied. The [engineering playbook](ENGINEERING_PLAYBOOK.md#7-review-and-approval) and [roster](TEAM_ROSTER.md#required-review-combinations) still govern actual approvals.

## Verified repository conditions

The 2026-11-05 audit found `main` at `33aee9335994e168ec0970003300ea160808beb9`, already an ancestor of the integration branch, and a conflict-free PR. The then-current head `faa9abd8ba8f62f1eb2971d9ec177ca66812888a` passed CI. It had zero reviews, zero requested reviewers and zero review threads; no existing conversation needed resolution. Recheck these live after the audit fixes are pushed.

The active `main-reviewed-squash-delivery` ruleset (ID 25250513) requires one approving review, approval after the last push, stale-review dismissal, resolved review threads, current-main `build-and-test`, and squash-only merging. It also enables extra approval for unattributed changes. Ordinary branch-protection returns 404 because protection is supplied by the ruleset; it is not an unprotected branch. CODEOWNERS currently routes only to the PR author, BRMRs, and does not satisfy independent or specialist review.

Merge only when:

- The final tested head has green required CI and remains current with main, with no conflicts.
- The PR is ready for review, with its scoped exclusions and evidence retained.
- All blocking/question conversations are answered and resolved.
- Actual non-author approvals cover the final head and the applicable feature, Android infrastructure, Server/security and persistence perspectives below. An author's explanation of their original component is not independent review of that component.
- The author confirms final build/device evidence. Material follow-up changes require renewed affected review.

Use a normal squash merge and delete the integration branch after success. Do not use admin bypass, weaken the ruleset, approve with the author's account or treat automated agents as reviewers.

## Review routing — all approvals still pending

The lead remains accountable. The lead-authorized temporary S1 review backup is C1/tianiridescent, whose existing #84 review already addresses private-session behavior, with C2/Iris-wly cross-checking journal/plan recovery. This limited assignment is recorded in [#15](https://github.com/BRMRs/group33/issues/15); it changes no permanent role, supplies no consent/approval, and grants no permission to self-approve.

| Person | Required scope or available review role | Concrete checks and completion evidence |
|---|---|---|
| iridescentian (`tianiridescent`) | C1 auth/pairing; recorded S1 infrastructure review backup | Review `DebugChainRepository`, `ChainHttp`, `PrivateSessionStore`, `EncryptedSessionFile`, `ChainViewModel` and `FeatureHost`: actual server-issued identity, encrypted environment/installation/generation scope, late401 fencing, pending relationship grants no access, switch/revoke cannot republish old Elder data, no redirects/credential logging, original command and Room publication guards. Review #85 journal/recovery with the original-ID response-loss tests. Confirm the new nine ViewModel regressions and #84/#85 integration against the final head. #59/#92 are this person's original contributions: Tang/Cle-cre must independently review those UI changes. |
| XinyanXue (`XinyanXue`) | S2/security/persistence perspective | Review Android HTTP/lease/auth/error handling and the cross-module pairing→private-photo→plan path. Check denied reads, authority invalidation, atomic plan/receipt/cache reconciliation, bounded SQL, schema startup/migration controls, and private photo publication. Match the exact assembled #99 source/evidence; decide whether any inherited real-PostgreSQL upgrade/restart proof needs renewal. Explain own Server changes; independent cross-review of them must come from the other reviewers, not self-approval. Confirm memory-store testing does not claim deployed persistence/phone identity. |
| Lingyu Wang (`Iris-wly`) | C2 plan/time semantics; journal/plan recovery cross-check | Review actual downloaded image binding, trusted selected-Elder target, editor/save integration and original operation identity across process restart. Check photo/Medication/plan IDs and timezone/date bounds through Server receipt and Elder Room display; failed/unknown/rejected recovery must not announce success. Cross-review Server confirmation/read semantics and Room/journal reconciliation. Explain original #86/#43/#58 code; Tang/S2 independently check those sources and new integration. |
| Tang (`Katherine-Tang`) | E flow and shared-language accessibility perspective | Independently review #59/#92 shared entry, language persistence, navigation/Back/recreation and absence of role-preview bypass. Verify one photo entry, no Elder sync-management controls/status, automatic authorized plan loading, short truthful failure/pending feedback, appropriate large text/touch targets and English/Chinese layouts. Independently review the C2 editor as used in the actual host. Original #82 camera code needs a different reviewer; a native emulator camera pass is not physical/OEM acceptance. |
| Cle-cre (`Cle-cre`) | Available independent overall/status-evidence reviewer | Review the assembled diff and causal evidence as a non-author of the source components: no wrong-Elder data, no false refreshed/saved/read/delivered claims, stale/unknown/failure mapping, navigation clears editor on scope loss, restart and duplicate prevention. Can supply the general non-author approval if satisfied. A separate Cle-cre approval is not an invented hard requirement when another qualified non-author already supplies it; specialist coverage above remains required. |
| Rui Zheng (`BRMRs`) | Author/integration owner; cannot approve this PR | The limited S1 review-backup assignment is recorded in #15. Publish exact final validation and fix review findings. Recheck same-head approvals/CI/threads/main, then squash merge only after all required coverage is present. Keep #15/#12 open and physical/production-phone/cloud/V2 exclusions explicit. |

No reviewer has been marked approved by this document. Listing handles is routing, not consent or an assertion that a review was requested or completed.

## Existing approvals do not transfer

The C1 approval on #84 targets `6bc1e4e8e45320bce0266e7c3198093d72bfac19` and explicitly asks future adapters to recheck the captured session lease. It is useful prior evidence, not approval of #110. #86's earlier S2 review is dismissed. The other assembled source PRs likewise do not supply a current #110 approval. Original PRs need not be merged individually first if their exact code and boundaries receive independent review in this integration PR.

## Agent audit corrections

### Standards

The automated Standards review found one P1: same-session old-Elder cache emissions could repopulate the newly selected/revoked Elder view while an old refresh finished cancellation. A same-Elder replacement relationship was also missing from the subscription key. The fix captures/rechecks the complete relationship/session context atomically at publication and includes it in subscription identity. The original finding is closed by read-only recheck; this is not a member approval.

### Spec

The automated Spec review found two P2 issues: ElderHome retained manual sync-management controls/status, and command recovery announced success despite failed/unknown outcomes. Refresh remains automatic for Elders; controls and technical sync status stay caregiver-side, with short bilingual Elder feedback. Recovery now distinguishes failed refresh, unresolved command/cache state, definitive rejection and confirmed/reconciled success. The original findings are closed by recheck.

Eight targeted unit cases were run before correction: seven failed, one passed. After correction those eight passed; a ninth case additionally verifies definitive rejection. The tests exercise delayed old-Elder publication during cancellation, revocation, same-Elder replacement, offline recovery, failed/unknown recovery, cache-refresh-required, confirmed/reconciled success and rejected receipt. Full final build/device evidence is recorded in [the integration evidence](evidence/DEBUG_ONLINE_CHAIN.md).

The broader source histories remain reviewable by humans. This audit does not freeze V2, declare phone binding complete or close parent issues. The explicitly deferred physical-device acceptance is not silently converted into a prerequisite for merging this Debug-scoped partial slice.
