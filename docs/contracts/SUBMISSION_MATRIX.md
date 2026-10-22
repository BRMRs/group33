# Gate 1 submission and review checklist

**Delivery split accepted 2026-10-17:** [ADR-0009](../adr/0009-two-stage-baseline-and-gate1-acceptance.md) makes the initial PR #22 a Stage A development-baseline delivery. The six-member requirements below belong to Stage B / #12 completion, not to acquiring the initial foundation. Stage A retains CI, non-author and applicable cross-boundary publication review. Agent onboarding evidence never signs any row below.

**Product-version warning:** compiled r1 DTOs/JSON/tests below are legacy structural evidence. Current accepted product behavior is [ELDER_REMINDER_V2 / #23](https://github.com/BRMRs/group33/issues/23); its separate Draft PR #25 and [open protocol coordination #24](https://github.com/BRMRs/group33/issues/24) must be read before affected work. The r1 two-hour value agrees with the latest response window, but does not prove separate thirty-minute notification, T−5/T+5 probes or current group/reporting behavior. The one-time PR #22 publication-approval exception is in ADR-0009; it signs no Stage B row here.

[Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12) is assigned to **BRMRs (Rui Zheng)** as requested. This assignment makes the issue accountable; it does not assign all six people's implementation work to one account. The confirmed role-to-person mapping is in [TEAM_ROSTER.md](../TEAM_ROSTER.md).

The project lead authorized continuing Gate 1 code/test work on baseline `T01-T07/2026-10-16-r1`. P0 Shared DTOs, nine Android Repository Interfaces and actual wire/transport/caller tests now compile and pass locally; see [Gate 1 review packet](GATE1_REVIEW_PACKET.md). The pre-existing scaffold corrections are preserved. These common agent-prepared artifacts are not attributed to six individual teammates and do not deliver downstream P0 behavior. Member code/test PRs and formal review remain required before closing the Issue.

The earlier mapping deferral ended with the lead’s 2026-10-22 confirmed [roster](../TEAM_ROSTER.md). Named ownership does not establish a completed review, signature or code contribution.

## Current product authority

[ADR-0008 / ELDER_REMINDER_V2](../adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md) supersedes the affected prior inputs. The r1 compiled artifacts and passing tests below remain historical structural/regression evidence, not a freeze-ready V2 contract. [V2-T01–T06](../ELDER_UI_TECHNICAL_REVIEW.md) track coordinated code/fixture/interface migration and delivery decisions; those cannot restore old behavior.

## Current inputs with historical decision sources

- [Choose the caregiver-elder identity and pairing model](https://github.com/BRMRs/group33/issues/3): explicit Elder approval, active-relationship authorization, role/cardinality/revocation constraints.
- [Define medication plans and dose-event semantics](https://github.com/BRMRs/group33/issues/4): daily local times, Elder zone, 2h response/30m Caregiver notification/10m snooze, immutable event meaning and prospective edits.
- [Choose the offline-first synchronization and conflict model](https://github.com/BRMRs/group33/issues/5): Room-backed local execution, service-confirmed Caregiver saves, durable Elder reports/idempotent retry.
- [Research Android reminder, permission, and restart constraints](https://github.com/BRMRs/group33/issues/6): capability separation, late reports, local/reboot recovery and platform evidence.
- [Select the shared UI direction and elder accessibility profile](https://github.com/BRMRs/group33/issues/7): separate dose/sync/delivery semantics, language and accessibility mappings.
- [Set the P0 integration boundary and delivery sequence](https://github.com/BRMRs/group33/issues/9): real photo prerequisite, shared-contract review and seven delivery gates.
- [Confirm the P0 hosting, storage, and explicit synchronization stack](https://github.com/BRMRs/group33/issues/11): Room ↔ Spring Boot/Render ↔ Supabase PostgreSQL/Storage and rehearsed local fallback.

## Required contributions by each of the six members

The common contract code now exists in the review packet's paths. The table still defines each role's required attributable contribution/review, not automatic credit for agent-prepared code. Keep member PRs focused; coordinate shared-file edits through the Issue.

| Role | Gate 1 code/interface submission | Test/fixture contribution | Required joint review and later feature code |
|---|---|---|---|
| E — Elder | Own photo-submit, consent and Dose action Repository inputs/results; contribute `DoseEventState`/`LocalActionReceipt` mapping and feature interfaces under `android-app/.../core/data/` | P01/P04, D03/D04, S03/S04/S07/S09, R04/U01; local late reports and recording versus shared acceptance, repeated actions and permission notices | Review C1 Pairing, C2 photo/plan, S1 recorder; later deliver Elder UI/ViewModels and own integration fixes |
| C1 — Caregiver identity | Own `AuthRepository`/`PairingRepository`, user/relationship request mapping and consent-result types; coauthor session contracts with S1 | A01–A05, W02, R01/R04; pending is not active, cancellation/expiry/revocation, language persistence | Review S2 auth/pairing and E consent; later deliver login/pairing/Elder list and shared language/resources code |
| C2 — Caregiver Medication/plan | Own `ReminderPlanRepository`, photo-load interface contribution, confirmation/update DTO mappings and revision-aware result/errors | P02–P04, D01/D02/D05/D06, S05/R02/R04; atomic save and time-zone examples | Review S2 transactions and E photo handoff/S1 cache; later deliver forms, validation, photo viewing and integration code |
| C3 — Caregiver feedback | Own dashboard/history/Refresh Repository interface, `ElderOverview` and late-report/capability/stale mapping | D04, S06/S08/S09, R01/R03/R04, U01; three status categories and event correlation | Review E outcomes and S1/S2 snapshot; later deliver dashboard/history/refresh code plus test/evidence coordination |
| S1 — Android infrastructure | Own shared result/cache/sync primitives, online-command journal/recovery Interface, scoped merge generations, atomic local recorder/outbox and scheduler seams; feature authors implement registered handlers/mappers | W01, S01/S03/S04/S06/S07/S08/S09/R04; common fake harness and restart/merge vectors | Review all feature contracts and S2 receipts; later implement Room, networking, WorkManager, alarms and boot recovery with feature authors |
| S2 — Server and persistence | Maintain Shared wire DTO/enums/error declarations and Server transport contracts; specify authorization/transaction/idempotency/revision seams | W01, A01–A05, P01–P04, D04/D05, S01/S02/S07/S08/S09; Server/Jackson fixtures and production-adapter test plan | Review every client feature contract; later implement principal pairing/authorization/Medication/plan/event API, Service/Repository and PostgreSQL code as well as Storage/deployment |

Every role submits attributable contract/model/mapping code and relevant tests in the later contract PRs; later feature issues require real behavior code. Reviews, meetings, recordings, CI or deployment configuration alone do not satisfy a member's code contribution. Feature authors own their own Repository integration and model changes with S1/S2 review.

## Current-code gaps and proposed handling

| Location | Present mismatch with confirmed behavior | Proposed handling, without silently changing current code |
|---|---|---|
| `shared/.../MedicationModels.kt` | Legacy Schedule/Event remain intentionally limited | P0 `shared/.../contract/` DTOs now provide zone/revision/occurrence/frozen evidence/reports; migrate real callers in feature Issues rather than breaking the preview |
| `shared/.../UserModels.kt` | Legacy CareRelation remains in the scaffold | Explicit P0 lifecycle types now exist in IdentityContracts; UserView aliases existing User; C1/E/S2 review and integrate |
| `shared/.../ApiModels.kt` | `ApiError`/`FieldError` and controller/security envelope implemented; P0 feature-specific errors remain incomplete | Complete feature errors and bilingual recovery mappings with joint contract tests |
| `android-app/.../MedicationRepository.kt` | Scoped legacy preview remains in memory | Nine new P0 Interfaces/helper types compile separately in `core/data/contract/`; real Room adapters stay downstream, each feature owns its integration |
| `server/.../web/ScheduleController.kt` | Calls relationship-access seam; shipped adapter denies all until real Pairing exists | S2 implements verified active-relationship lookup with explicit consent/revocation; development-only endpoint cannot satisfy the P0 gate |
| Prior API resource sketch | Photo upload required an already created Medication; generic event PATCH allowed unclear status authorship | Proposed independent photo resource, atomic photo-to-plan confirmation and Elder-only action command replace the old sketch, preserving unaffected behavior; V2 separately requires caregiver entries and grouped actions |

## Approved technical baseline, formal review still required

The [review record](REVIEW.md) retains CR01–CR08 and records T01–T07 as resolved. [The decision record](TECHNICAL_RECOMMENDATIONS.md) preserves approved values/trade-offs. Compiled models/Repository/fixtures remain r1; current behavior docs and the existing 33 planned cases plus V201–V206 now require V2. Coordinated migration is pending; the two artifact versions are not interchangeable. No additional project-lead parameter choice is pending among these seven.

E coauthors frozen generation/source-upload/action mappings; C1 owns pairing limits/terminal recovery; C2 owns preview-versus-generated/edit-effective/overlap/time-zone mappings; C3 owns bounded history, retained-instance/capability/refresh and local rehearsal tests; S1 owns generation/registration work, durable journal/outbox/sequence and environment isolation with feature authors; S2 owns real auth/pairing/plan/event/registration business code, receipt transactions, persistence and both Storage adapters. No role can substitute review/deployment-only work for its own code and tests.

Technical approval does not constitute formal engineering sign-off. All six role slots must record actual review against the same manifest/fixture/commit and link attributable code/test PRs; use the confirmed [roster](../TEAM_ROSTER.md). A named Android reviewer and Server reviewer still jointly sign. Local Gate 1 code/tests now pass; production-adapter/device/fully cloud-independent rehearsal remains outstanding under its ordinary Issues, not disguised as completed Gate 1 tests.

## Review and completion record

- [x] Issue assigned to BRMRs.
- [x] Draft routes/errors/idempotency and data-model migration documented.
- [x] Feature Repository interface proposals, role-specific submissions and case matrix documented.
- [x] Agent Android/Server consistency review recorded; determinable corrections applied and examples updated. Test plan has 33 proposed cases; eight feature interfaces share an additional command-recovery Interface.
- [ ] Six role-specific contribution/review records and required code/test PRs linked; named assignments are in [TEAM_ROSTER.md](../TEAM_ROSTER.md).
- [ ] Android reviewer: name, date, reviewed fixture/commit version.
- [ ] Server reviewer: name, date, reviewed fixture/commit version.
- [x] Project lead approved and recorded T01–T07; technical choices synchronized into the specification baseline.
- [x] Compiled P0 wire declarations, nine Repository Interfaces, local model/wire/Jackson/MVC/caller tests and bilingual error mappings delivered; gate1Check passes locally.
- [ ] All six role slots record actual formal review of their Interface/model/test obligations against the same baseline/fixture/manifest/commit; use the confirmed roster.
- [ ] Actual Shared serialization/Server transport/Android Repository tests implemented and passing for the agreed Gate 1 scope; downstream real-adapter cases tracked separately.

Issue remains OPEN for formal review/member contribution/PR acceptance. The local automated checks above are evidence, not teammate sign-off; the unchecked formal test acceptance remains for the team to verify against the same reviewed artifact. See the review packet for exact commands, 69 three-module JVM checks plus 23 Release variant checks, and downstream production tests. Feature implementation still follows accepted Interfaces and attributable code/test PRs.
