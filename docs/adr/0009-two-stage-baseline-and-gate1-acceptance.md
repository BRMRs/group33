# ADR-0009: Separate development-baseline acceptance from Gate 1 completion

## Status

Accepted by the project lead on 2026-10-17. This delivery-workflow supplement clarifies ADR-0007 and the initial PR #22 scope; it changes no product decision. The original preparation-only authorization is historical; the separately approved one-time publication exception below controls this release attempt.

## Context

New developers need a buildable common foundation and concrete agent-readable tasks before they can contribute feature code. Requiring all six members' code PRs before publishing that foundation creates circular onboarding. Publishing a foundation must not be confused with freezing its contracts or completing Gate 1.

A parallel accepted product record already exists: [ELDER_REMINDER_V2 / Issue #23](https://github.com/BRMRs/group33/issues/23), [product ADR-0008 at the latest confirmed PR #25 revision](https://github.com/BRMRs/group33/blob/5dc2683045a0c12393c60949d4143cd1575087e1/docs/adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md), and [technical coordination #24](https://github.com/BRMRs/group33/issues/24). PR #22 still contains r1 code/fixtures. That code can be a structural/regression baseline, not current V2 acceptance. Its two-hour duration agrees with the latest clarification but does not implement the separate thirty-minute notification or scheduled connection checks. Do not import or merge PR #25 as part of this workflow change; its integration remains separately tracked.

## Decision

| Stage | Acceptance boundary | Not a prerequisite / not implied |
|---|---|---|
| A — Development baseline | Buildable clean checkout, actual CI, truthful implemented/legacy/fake/missing inventory, six independent role-onboarding briefs with concrete tasks and resolved navigation defects; normal non-author approval, resolved discussions, up-to-date checks and applicable real Android/Server boundary review before a separately authorized merge | Six members' future feature PRs and full Gate 1 signatures are not required merely to obtain the foundation. Agent briefs are not approvals, named reviews, member contributions or proof that six people's machines work |
| B — Gate 1 completion | All six actual role contribution/review records, attributable code/test PRs, named Android/Server same-version sign-off, agreed current contracts/fixtures and executable contract checks; unresolved V2 protocol/migration questions resolved for affected scope under #24, downstream production evidence assigned without circular dependencies | A Stage A merge or green legacy r1 suite cannot close #12, mark V2 implemented, freeze retired behavior, or waive later feature/device acceptance |

PR #22 is a Stage A candidate and partial delivery referencing #12, not a request to close #12. Separate Stage A and Stage B checklists in the PR and tracker. Keep every unfulfilled Stage B item open rather than treating it as a failed prerequisite for accessing the initial engineering foundation.

Stage A boundary reviewers assess safe foundation publication and explicitly identify unfrozen/legacy seams. That assessment is not Stage B approval of V2 protocols. Product authority is the accepted current record even when its documentation PR is not yet merged; conflicting r1 representations must be labeled legacy and affected production implementations must wait for coordinated technical resolution. Unaffected preparation may proceed within recorded scope.

Account mapping remains deferred. Role-only agent onboarding and lead-authorized common preparation are allowed; ordinary feature Definition of Ready, accountable ownership and required human reviewers are unchanged. S1 remains the infrastructure owner, not the sole feature integrator; S2 remains responsible for principal Server business code, not only deployment.

## Consequences and controls

- The initial baseline is accessible before six future feature contributions exist, while those contributions remain mandatory for Stage B.
- Keep one short agent entry point, six role cards, explicit first-task boundaries and reproducible evidence. Record independent agent runs honestly; they are not formal team signatures.
- Published review commit/manifest/fixture identities must match. Pre-commit rehearsals identify the parent SHA plus hashed working-tree inputs, not a nonexistent commit containing uncommitted documents.
- The earlier preparation authorization covered documentation, six read-only rehearsals and commit/push only. The newer explicit publication authorization below supersedes its Draft-only restriction for PR #22, not for any other PR.
- Regular delivery retains branch protection, squash-only, CI, non-author approval and applicable specialized review. Passing preparation checks alone never authorizes a merge.

## One-time PR 22 publication exception — 2026-10-17

Authority: the project lead directly requested “记录我批准的本次免人工审批例外” and “最终版本重跑检查，通过后 squash 合入 main，并恢复常规审批要求”. This is a recorded release authorization, **not a teammate approval, Android/Server signature or individual contribution**.

- **Exact scope:** only the first Stage A development-baseline publication through [PR #22](https://github.com/BRMRs/group33/pull/22). For this attempt only, human non-author and specialized publication approvals are waived; independent Standards/Spec agent checks and final executable checks remain required. No synthetic GitHub review is submitted.
- **Retained gates:** real passing `build-and-test` from app 15368 on the final head, current-main ancestry, resolved blocking discussions, a non-Draft PR, squash-only, an active main ruleset, no bypass actors, no force push/deletion, and exact expected-head merge. No `--admin` or direct push to main.
- **Minimum temporary change:** immediately before merging that verified head, lower only the approval-related parameters in ruleset 25250513: approving review count to zero, last-push approval off, and extra approval for unattributed changes off. Keep stale-review dismissal and every non-approval parameter unchanged. GitHub cannot scope these parameters to one PR; keep this main-wide window to the single merge operation and allow no other merge in it.
- **Restore-first:** snapshot the complete writable ruleset before the attempt; use a `finally` restoration on merge success or failure, independently read back the full ruleset and effective main rules, and retry restoration if needed. A failed or uncertain restore is a release incident: stop other work and restore protection before reporting completion. The exception expires when this attempt ends; regular approval rules apply thereafter.
- **No wider waiver:** Gate 1 / #12 remains OPEN. Six members' actual code/test PRs, role reviews and named same-version Android/Server contract signatures remain PENDING. Account mapping stays deferred; V2 protocol/production acceptance, real devices, security and cloud deployment are not approved by this baseline merge. PR #25 remains a separate normal-review delivery.
- **Audit:** record both review axes, fixed base, reviewed head/manifest, exact checks, merge SHA, temporary settings and verified restoration in the [publication check record](../evidence/PR22_BASELINE_RELEASE_CHECK.md) and PR/#12 execution records. Commit identities and completed external actions belong in the latter so the evidence does not certify a future action or invent a self-referential commit.
