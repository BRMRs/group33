# Engineering playbook

This document is the operating agreement for all work in `BRMRs/group33`. It is intentionally explicit so that six members can begin, review, integrate, and hand over work without relying on an unrecorded conversation.

The project lead is the final integration and release owner. Any teammate may act as backup for any role when the lead records that assignment in the relevant issue. Backup assignment does not remove required Android, Server, security, or shared-contract review.

## 1. Authority and sources of truth

When documents disagree, use this order:

1. Accepted Architecture Decision Records in `docs/adr/`.
2. Accepted API, Shared model, Repository, and test contracts in `docs/API_CONTRACT.md` and `docs/contracts/`.
3. This playbook and `CONTRIBUTING.md`.
4. The acceptance criteria and recorded decisions in the relevant GitHub issue.
5. Module documentation and code comments.
6. Pull-request discussion.

A lower item cannot silently override a higher item. Chat, meeting, and verbal decisions are not authoritative until they are recorded in an issue, contract review, or ADR.

If work discovers a conflict:

1. Stop only the affected implementation, not unrelated work.
2. Add `blocked` to the issue and describe the conflict.
3. Link the conflicting sources.
4. The owners named below record a decision in the appropriate source.
5. Resume only after the issue links the recorded decision.

Product or architecture changes require an ADR. Cross-client wire or behavior changes require the Android and Server contract reviewers. Local implementation choices that preserve accepted behavior belong in the owning issue or PR.

## 2. Roles, ownership, and backup

The six responsibility slots are E, C1, C2, C3, S1, and S2 as defined in `docs/TEAM_BRIEFING.md`. Actual members and GitHub handles are recorded in `docs/TEAM_ROSTER.md`.

Each issue has exactly one accountable owner. Contributors may share implementation, but the owner remains responsible for scope, integration, verification, documentation, and review follow-up.

All five other teammates form the backup pool. When an owner is unavailable or a review is overdue, the project lead records a named backup in the issue. The backup may continue the work after reading the issue, linked decisions, current branch/PR, and verification record. A backup cannot approve their own changes and cannot replace a mandatory cross-boundary reviewer with an unrelated approval.

The project lead owns:

- integration order and release readiness;
- resolution of ownership conflicts;
- build health and urgent repair assignment;
- merge and release decisions;
- confirmation that shared decisions have the required review.

The lead follows CI, security and contract-review rules. The only first-baseline publication approval exception is the recorded PR #22 authorization in ADR-0009; ordinary non-author and specialized approvals remain mandatory.

## 3. Work states and Definition of Ready

Use these workflow labels:

- `needs-triage`: not yet evaluated;
- `needs-info`: missing information;
- `ready-for-agent`: fully specified and safe for an agent;
- `ready-for-human`: fully specified but requires human/device/account work;
- `in-progress`: an owner and active branch exist;
- `in-review`: a non-draft PR is ready;
- `blocked`: a named dependency or decision prevents progress;
- `wontfix`: explicitly declined with a reason.

An issue is Ready only when it contains:

- a user, system, or engineering outcome;
- observable acceptance criteria;
- one accountable owner and required reviewers;
- dependencies and blocking issues;
- affected modules, interfaces, or shared files;
- test IDs or a concrete verification plan;
- security, privacy, localization, accessibility, and migration impact where relevant;
- no unresolved conflict with an accepted ADR;
- a size that normally fits one primary PR.

Work may use reviewed fake interfaces while a dependency is being implemented. It may not invent unresolved shared fields or behavior. The technical items T01-T07 in `docs/contracts/REVIEW.md` block only the affected adapters and tests, but must be resolved before those implementations are frozen.

When work starts, the owner comments with the branch name and target PR scope and applies `in-progress`. When a PR becomes ready, use `in-review`. A blocked issue names the blocker, the person or role needed, and the next action.

## 4. Branches

Create branches from the latest `main`. Use lowercase ASCII and hyphens:

```text
feature/<issue-number>-<short-name>
fix/<issue-number>-<short-name>
chore/<issue-number>-<short-name>
docs/<issue-number>-<short-name>
test/<issue-number>-<short-name>
refactor/<issue-number>-<short-name>
```

Examples:

```text
feature/14-reminder-plan-form
fix/23-preserve-dose-action-id
docs/12-accept-contract-review
```

Rules:

- One branch has one primary issue and one coherent outcome.
- Do not create permanent personal, role, client, or Server branches.
- Do not push directly to `main`. The sole initialization exception is the project lead's explicit 2026-10-16 authorization to create and publish the empty repository's first commit containing only `.editorconfig`, `.gitattributes`, and `.gitignore`. This establishes ancestry, not a buildable application baseline. All engineering, governance and Gate 1 artifacts follow through a PR; this exception permits no later direct feature push or merge bypass.
- Do not force-push a branch after review has started unless the PR clearly explains why reviewers must re-review.
- The author updates the branch from `main` and resolves conflicts.
- Delete the branch after merge.
- Dependabot branches are exempt from the project branch-name pattern.

## 5. Commits

Pushed commits use Conventional Commits:

```text
<type>(<scope>): <imperative summary>
```

Allowed types:

```text
feat fix test docs refactor perf build ci chore revert
```

Preferred scopes:

```text
elder caregiver pairing auth plan dose sync room alarm server shared photo ui ci docs
```

Examples:

```text
feat(pairing): add elder approval flow
fix(sync): preserve action id across retries
test(server): reject revoked caregiver access
docs(contract): record pairing expiry behavior
```

Commit requirements:

- Write the summary in English, imperative mood, lowercase first word, without a trailing period.
- Keep each commit to one logical change.
- Do not mix broad formatting with behavior changes.
- Do not commit known build failures to a shared branch.
- Use `Refs #14` in the footer when a commit needs direct issue traceability.
- Use `BREAKING CHANGE:` only after the required contract or ADR approval.
- Never commit secrets, tokens, real medical/personal data, `local.properties`, `.env*`, logs, IDE state, or generated build output.

Local exploratory commits may be untidy, but the pushed review history must be understandable. `main` uses squash merges, so the PR title becomes the final commit subject and is checked automatically.

## 6. Pull requests

Open a Draft PR early when it helps expose integration or contract risk. Mark it ready only after self-review and required local checks.

PR titles use the same Conventional Commit form as final commits. A PR must:

- use `Closes #<issue>` when the PR completes its primary issue; use `Refs #<issue>` for an explicitly scoped partial delivery and list the remaining acceptance criteria. The initial Gate 1 publication references #12 without closing it: six real role reviews, attributable member PRs and joint Android/Server sign-off remain required;
- explain the outcome and important implementation choices;
- state what is deliberately outside the PR;
- identify architecture, API, schema, permission, configuration, and privacy effects;
- list exact automated and manual verification;
- identify applicable contract-test IDs and any deferred cases;
- include English and Chinese evidence for user-visible states;
- include screenshots or recordings for UI changes;
- describe migration and rollback for persistent or deployed changes;
- link follow-up issues rather than hiding unfinished work.

Prefer no more than 500 changed non-generated lines. A larger PR is allowed only when the description explains why splitting it would make the change less safe or less reviewable. Generated fixtures, lock files, and mechanical migrations are excluded from this guideline.

During historical Stage A preparation, the lead-approved initial publication could be a Draft PR while mapping was deferred. The accountable lead remains BRMRs; current named roles are now recorded in [TEAM_ROSTER.md](TEAM_ROSTER.md). [ADR-0009](adr/0009-two-stage-baseline-and-gate1-acceptance.md), accepted on 2026-10-17, defines two separate checkpoints:

- **Stage A — development baseline:** reproducible build/CI, truthful scaffold/legacy/fake boundaries, six independent agent-onboarding briefs and resolved navigation defects. Any later merge still requires non-author approval, resolved discussions, current-main checks and applicable real Android/Server boundary review. Future six-member code PRs and full Gate 1 signatures are not prerequisites merely to publish the foundation.
- **Stage B — Gate 1 completion:** six actual member contributions/reviews, attributable code/test PRs, named Android/Server same-version contract sign-off and agreed current contract/test evidence. These remain required before closing #12; a Stage A merge does not satisfy them.

PR #22 is a Stage A partial delivery using `Refs #12`. Agent rehearsals are not human approval or signatures. Account mapping/ordinary feature DoR remain unchanged. Current accepted product behavior and open technical seams must be explicitly routed; legacy r1 test success cannot freeze the V2 protocol. Its separately authorized publication follows the one-time exception below; Gate 1 remains OPEN after a baseline merge.

Do not combine unrelated refactoring, dependency updates, and product behavior in one PR. A PR that changes a shared contract must update the relevant model, fixture, documentation, and tests together or explicitly use linked coordinated PRs.

## 7. Review and approval

Every PR requires:

- passing required CI;
- all conversations resolved;
- at least one approval from a teammate who is not an author;
- confirmation by the author that the final revision was built and tested.

Additional approval:

- Shared model, API, serialization, error, time, or sync-contract changes require one Android-side reviewer and one Server-side reviewer.
- Authentication, authorization, secrets, or privacy changes require S2 or the named Server/security backup.
- Room, WorkManager, AlarmManager, session storage, and Android background execution changes require S1 or the named Android-infrastructure backup.
- Database migrations and Storage publication/cleanup require S2 or the named persistence backup.

The lead's [one-time PR #22 publication exception](adr/0009-two-stage-baseline-and-gate1-acceptance.md#one-time-pr-22-publication-exception--2026-10-17) is the sole exception to the human approval bullets for that initial Stage A attempt. Follow its retained gates, minimum ruleset window and mandatory restoration. It does not mark any review or Stage B signature complete; all later PRs use the normal requirements above.

Review labels in comments:

- `blocking:` violates behavior, safety, architecture, testability, or acceptance criteria;
- `question:` needs explanation before approval;
- `suggestion:` improves the change but is not required;
- `nit:` optional style feedback.

Reviewers check behavior and failure paths, not only formatting. Authors answer every blocking or question thread. Material changes after approval require another review from the affected reviewer.

Review target is one working day. If no required reviewer responds within two working days, the project lead names any available teammate as backup. Mandatory Android/Server perspective requirements still apply; when the backup lacks that perspective, the project lead assigns an additional qualified reviewer.

## 8. Merge policy

- `main` must remain buildable and demonstrable.
- Use squash merge only.
- The squash title must pass the PR-title rule.
- The project lead or a recorded delegate performs the merge.
- The PR must be current with `main` or pass the repository's merge queue.
- Delete the source branch after merge.
- Never merge a Draft PR, a red CI build, unresolved blocking review, or an incomplete migration.

Configure GitHub branch protection to require a PR, required CI, at least one approval, resolved conversations, and prevention of force pushes and deletion. Where supported, require CODEOWNERS review. Repository settings are part of the engineering system and must match this document.

## 9. Definition of Done

Apply Done to the explicitly scoped delivery. A reviewed/merged Stage A baseline PR may finish its publication scope while #12 remains open for Stage B; it must not use an issue-closing keyword or mark unfinished Gate 1 criteria complete.

Work is Done only when:

- every acceptance criterion is demonstrated;
- relevant unit, contract, integration, and device tests pass;
- loading, empty, offline, authorization, validation, retry, and failure states are covered where applicable;
- English and Simplified Chinese resources are complete;
- accessibility requirements relevant to the screen are verified;
- no real personal or medical data is used;
- documentation, fixtures, migrations, and configuration examples are updated;
- the PR records commands and evidence;
- follow-up limitations have issues and owners;
- the contribution log links the issue, PR, tests, and evidence;
- the PR is reviewed, merged, and its issue is closed.

Passing a fake test does not claim production behavior. A document proposal does not claim implementation. A locally queued action does not claim Server acceptance.

## 10. Required checks and test ownership

Before requesting review, run:

```bash
python3 scripts/check_repository_policy.py
./gradlew test
./gradlew :android-app:lintDebug
./gradlew :android-app:assembleDebug
```

Minimum evidence by change type:

| Change | Minimum evidence |
|---|---|
| UI/ViewModel | ViewModel or mapping test plus device/emulator evidence |
| Shared DTO | Kotlin serialization and Jackson compatibility tests |
| Repository | interface/adapter tests, typed failures, and state observation |
| Server controller | authentication, status, header, and error-envelope tests |
| Server service | domain rules, authorization, idempotency, and transaction tests |
| Room | DAO, migration, transaction, and merge tests |
| Sync/outbox | retry, duplicate, rejection, ordering, and process-recovery tests |
| Alarm/reboot | deterministic unit tests plus device/API-level evidence |
| Database/Storage | real-adapter integration and restart/reconciliation evidence |
| Cross-device flow | two-installation causal-path recording and checklist |

Use injectable clocks for time behavior. Tests must not wait real minutes or depend on the public network. Never silently disable a flaky test: open an issue, name an owner, document the impact, and either fix it or quarantine it with an expiry date approved by the lead.

No global coverage percentage is used initially because it encourages low-value assertions. Every changed behavior must have a relevant automated test unless the PR explains why only device evidence can prove it and names the corresponding acceptance check.

## 11. Architecture and dependency rules

- Android UI calls ViewModels, not DAOs, HTTP clients, Workers, or alarm implementations.
- ViewModels depend on feature Repository interfaces and expose screen state.
- Room-backed repositories are the observation source for synchronized business data.
- Network responses update Room instead of creating a parallel UI truth.
- `shared` contains wire/domain contract types and no Android UI or database implementation.
- Server controllers adapt HTTP; application services own business behavior; persistence adapters own storage details.
- Caller identity and role come from verified authentication, never a client-selected actor ID.
- One feature must not reuse another feature's internal Activity, mutable state, DAO, or transport adapter.
- Dependency versions belong in `gradle/libs.versions.toml` unless a reviewed exception is documented.

Any intentional boundary exception requires an ADR or a time-limited debt issue approved by the affected owners.

## 12. API and contract lifecycle

Contract documents use these states:

- `DRAFT`: not safe for production implementation;
- `IN_REVIEW`: stable enough for review and fakes, but not frozen;
- `ACCEPTED`: reviewed by named Android and Server reviewers at a recorded commit;
- `SUPERSEDED`: retained for history and linked to its replacement.

Before marking a contract Accepted, record reviewer names, date, reviewed commit, resolved technical items, implemented fixture/serialization tests, and known downstream production tests.

An incompatible API change requires coordinated Android and Server review. Update routes/models, fixtures, error mapping, migration notes, and contract tests in the same PR or explicitly linked PRs. Raw Server messages are not the Android localization contract.

## 13. Persistence and migrations

Before the first production schema PR, S2 records the selected migration tool and creates the migration directory. Then:

- migration filenames are ordered and descriptive;
- a migration merged to `main` is immutable;
- corrections use a new forward migration;
- local, CI, Render, and fallback environments use the same migration sequence;
- schema changes include upgrade tests and startup/restart evidence;
- destructive changes require a backup, compatibility, and rollback plan;
- uniqueness, foreign-key, indexing, and idempotency constraints are reviewed as behavior, not incidental SQL;
- Storage objects are private, and publication/cleanup is reconciled with PostgreSQL metadata rather than assumed to be one distributed transaction.

Never delete history merely to implement revocation, plan edits, or event correction. Preserve the meanings required by the accepted ADRs and contracts.

## 14. Environment and configuration

The supported baseline is JDK 17, Android SDK 35, and a device/emulator at API 23 or later. Configuration and secrets remain outside Git.

Before cloud integration, add a committed `.env.example` containing names and safe descriptions only. Document required/optional variables, emulator host addressing, debug cleartext behavior, Render/Supabase setup, and local fallback dependencies. Never place a real value in an example.

Environment switches do not imply that identities, pairing, photos, or pending actions migrate. Each environment has visibly separate demo identities and data. Use fictional data only.

## 15. Security, privacy, and diagnostics

- Never log access tokens, installation IDs, photos, dosage text, pairing codes, or personal identifiers.
- Do not expose permanent public photo URLs.
- Unauthorized resource lookup must not reveal whether unrelated data exists.
- Diagnostics may use opaque request IDs and safe error codes.
- Release builds must not silently allow arbitrary cleartext traffic.
- Secret exposure is reported immediately to the lead, the secret is revoked/rotated, and the incident and repository cleanup are recorded.
- Dependency and security updates follow the normal PR and CI process; a high-impact active vulnerability is prioritized by the lead.

## 16. UI, localization, and accessibility

The sole current product/UI baseline is [ELDER_REMINDER_V2 / ADR-0008](adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md) and [Elder UI baseline](ELDER_UI_BASELINE.md). Former conflicting Issue/UI paragraphs are superseded. Remaining [technical questions](ELDER_UI_TECHNICAL_REVIEW.md) gate affected protocol freeze, not selection between old and new product behavior.

Shared color, typography, spacing, shape, elevation, icon, and component-state tokens are centrally owned. Feature branches do not invent local design systems.

User-visible work includes English and Simplified Chinese in the same PR. Android maps stable domain/error codes to localized resources and provides a safe unknown-code fallback. Do not build sentences by concatenating translated fragments.

Applicable UI work verifies text plus non-color state cues, touch targets, TalkBack labels, 200% font size, grayscale, long input, 360dp portrait, and the 320dp compatibility case described in `docs/UI_DESIGN_DECISION.md`.

## 17. Release and rollback

Before the first formal demonstration release, record:

- version and tag format;
- who deploys Render and runs migrations;
- required environment variables and health checks;
- APK/build identity and Server commit;
- deployment, migration, smoke-test, and rollback steps;
- cloud and local-fallback dependency coverage;
- a completed two-device bilingual rehearsal.

Release tags use semantic form `vMAJOR.MINOR.PATCH`. Do not tag a release until the lead approves the acceptance evidence. A rollback must consider database compatibility; reverting application code alone is not automatically safe.

## 18. Communication and recorded decisions

Issues are the durable record for task scope and blocking questions. PRs are the durable record for implementation review. ADRs and contracts are the durable record for accepted cross-cutting decisions.

Any useful decision made in a meeting or private chat must be copied into the relevant durable record with the decision, reason, participants, and date. Until then, teammates may not be expected to know or follow it.

If no existing rule applies, do not guess at a cross-module behavior. Create a decision question, tag the required owners, and continue only the unaffected work.
