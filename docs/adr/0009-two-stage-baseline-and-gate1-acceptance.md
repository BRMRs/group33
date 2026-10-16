# ADR-0009: Separate development-baseline acceptance from Gate 1 completion

## Status

Accepted by the project lead on 2026-10-17. This delivery-workflow supplement clarifies ADR-0007 and the initial PR #22 scope; it changes no product decision or GitHub protection rule.

## Context

New developers need a buildable common foundation and concrete agent-readable tasks before they can contribute feature code. Requiring all six members' code PRs before publishing that foundation creates circular onboarding. Publishing a foundation must not be confused with freezing its contracts or completing Gate 1.

A parallel accepted product record already exists: [ELDER_REMINDER_V2 / Issue #23](https://github.com/BRMRs/group33/issues/23), [product ADR-0008 at the published PR #25 revision](https://github.com/BRMRs/group33/blob/7104c8c122691990ef9ddec1f8bfd608640328c2/docs/adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md), and [technical coordination #24](https://github.com/BRMRs/group33/issues/24). PR #22 still contains r1 code/fixtures. That code can be a structural/regression baseline, not current V2 acceptance. Do not import or merge PR #25 as part of this workflow change; its integration remains separately tracked.

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
- The lead's present authorization covers documentation, six read-only agent rehearsals, checks and commit/push to PR #22 only. PR #22 stays Draft and unmerged, #12 stays OPEN, no accounts are mapped and no person is signed for.
- Branch protection, squash-only, CI, at least one non-author approval and applicable specialized review are retained. Passing Stage A preparation checks is not permission for the agent to mark Ready or merge.
