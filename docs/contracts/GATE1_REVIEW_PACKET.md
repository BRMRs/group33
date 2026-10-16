# Gate 1 technical review packet

Baseline: `T01-T07/2026-10-16-r1`. Status: **historical r1 code/check evidence; V2 migration required before affected contract freeze; Gate 1 is NOT closed**.

Current behavior is [ELDER_REMINDER_V2 / ADR-0008](../adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). The unchanged r1 two-hour fixture/WireContractTest duration matches the 2026-10-17 clarification, but is not proof of separate thirty-minute notification, T−5/T+5 probes or other V2 behavior. [V2-T01–T06](../ELDER_UI_TECHNICAL_REVIEW.md) list pending implementation review; the product decisions are no longer alternatives. The docs PR refreshes the artifact checksum manifest solely to identify its current review inputs, not to bless r1 as V2.

The project lead authorized continuing Gate 1 implementation and explicitly deferred mapping E/C1/C2/C3/S1/S2 to GitHub accounts. Track the six role slots without inventing assignments or signatures. These are agent-prepared common artifacts, not six members' claimed contributions. Each member must still submit attributable code/tests and actual review evidence.

## Delivered scope

- Serializable P0 wire declarations in `shared/src/main/kotlin/com/group33/shared/contract/`: identity/pairing, photo/Medication/plan/revisions, generation evidence/registration/actions/receipts, capability and full snapshot. `UserView` aliases the existing `User`; legacy scaffold callers remain intact. Wire DTOs are transport representations, not domain validators.
- Nine Android Interfaces and typed local results in `android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/`: eight feature Interfaces and one common recovery Interface. Unknown completion, committed/cache-pending, local recording, sync and delivery remain distinct. No production adapter or arbitrary status PATCH is added.
- Shared wire and Boot Jackson/MVC conversion tests consume the same `docs/contracts/examples.json`. The test-only `__contract-probe` controller is not a P0 handler and is excluded from production artifacts.
- Server decoding now rejects missing/null required primitive values, unknown fields and fractional integers instead of silently defaulting/truncating them. Optional documented null/default behavior is cross-tested. Spring's serialization BOM override aligns runtime with the existing catalog's 1.8.1; this fixes the AbstractMethodError exposed by the tests, not a stack upgrade.
- Missing required headers return structured 400 errors. Android maps P0 error codes to nonempty English/Chinese resources with an unknown-code fallback.
- `gate1Check` aggregates Shared/Server/Android JVM tests, Server bootJar, Android lint and Debug APK assembly. CI runs `./gradlew gate1Check test`, preserving the additional Release JVM checks.

## Executed local checks

Run from the repository root:

```bash
python3 scripts/check_repository_policy.py --branch feature/12-gate1-contracts --pr-title 'feat(shared): establish project and Gate 1 contracts'
./gradlew gate1Check test --rerun-tasks --console=plain
shasum -a 256 -c docs/contracts/GATE1_ARTIFACTS.sha256
```

| Suite / output | Current observed result | Limits |
|---|---|---|
| Shared JVM | 16 tests, zero failures/errors | Includes 11 new wire tests; does not implement time/overlap reducers |
| Server JVM | 30 tests, zero failures/errors | Includes 13 new Jackson/MVC tests; real converter/security on a test probe, not implemented P0 action/Pairing APIs |
| Android Debug JVM | 23 tests, zero failures/errors | Includes 15 Repository model/caller harness tests; scripted doubles are not proof of Room/outbox/HTTP behavior |
| Android Release JVM | 23 tests, zero failures/errors | Same JVM suite on Release, not a device acceptance run |
| Android lint | 0 errors, 28 warnings | Dependency-version notices and missing application icon; no dependency-upgrade scope added |
| Debug APK / Server bootJar | Built successfully | Buildable artifacts do not prove product behavior |

Gate 1's three-module JVM scope totals 69 tests; Release adds 23 variant executions. These counts are unrelated to the **33 P0 scenario IDs** in CONTRACT_TEST_PLAN. The source/config/fixture review set is pinned in [GATE1_ARTIFACTS.sha256](GATE1_ARTIFACTS.sha256); any later edit requires regenerating the manifest and rerunning affected checks. It is a local artifact identity, not a substitute for a reviewed PR/commit.

## Evidence boundaries and downstream tracking

| Existing case IDs | Evidence now | Still required / ordinary Issues |
|---|---|---|
| W01 | Actual DTO round-trip, Jackson/kotlinx equality, required/null/enum/list/Long handling, MVC conversion and missing-header errors | Formal cross-role approval; each future endpoint's behavior/security remains with its feature Issue |
| W02 | P0 code-to-resource mappings, both locale resource files and unknown fallback tested | Actual screen/notification/TalkBack recovery presentation: #19 |
| A01–A05 | Identity/terminal relationship shapes tested; existing scaffold auth/fail-closed regressions retained | Real consent, code limits/races/retention and revoked access: #10/#16 |
| P01–P04 | Photo/confirmation DTOs, retained-source Interface and typed caller harness | Real image validation/upload/authorized bytes/staging/transactions/restart recovery: #13/#16/#20 |
| D01–D06 | Time/revision/frozen-evidence representation and distinct dose/report enums | Actual DST/deadline/snooze/state reducers, overlap and prospective edit behavior: #14/#18 |
| S01–S09 | Request/receipt identity and capability acknowledgment shapes; recovery/sequence represented in typed models | Canonical payload algorithm, durable accepted receipts, real transactions/outbox/merge/retry/sequence and old-revision reconciliation: #16/#17/#18 |
| R01–R04 | All nine public Interfaces compile and have caller harnesses; scope/result/source types, unknown versus cache-pending and late-report representation tested | Real feature adapter scoping, persistence, failure recovery and process recreation: #10/#13/#14/#15/#17 |
| U01 | Dose/sync/delivery type separation and bilingual error resources | User-visible/accessibility behavior: #19/#21 |
| O01/O02 | Only build configuration/artifact checks in this turn | Device/process/reboot and fully cloud-independent two-device flow: #18/#20/#21 |

Per the closed [delivery-gate decision](https://github.com/BRMRs/group33/issues/9), Gate 1 is the shared-contract checkpoint; later production acceptance remains mandatory in its ordinary Issues. Do not make #12 wait for complete later-gate implementations that already depend on #12, and do not mark those later cases passed merely to close Gate 1. This packet explicitly records the split instead of silently weakening acceptance.

## Six-role formal review queue

All rows remain **PENDING**. No GitHub account is assigned by this packet.

| Role | Concrete review/code surface | Required next evidence |
|---|---|---|
| E | DoseContracts/IdentityContracts, LocalActionReceipt/DoseEventState, photo/action/consent caller harnesses | Confirm local-recording versus Server acceptance and late/snooze boundaries; contribute feature mapping/tests; record actual review |
| C1 | IdentityContracts, Auth/Pairing/Language Interfaces, P0 error resource mappings | Review terminal/default/null/auth recovery; contribute caller/mapping tests and actual review |
| C2 | PlanContracts, ReminderPlan/Photo Interfaces and caller harnesses | Review update/null semantics, photo prerequisite, preview versus frozen occurrence and overlap vectors; contribute corrections/tests |
| C3 | SyncContracts, Dashboard/Capability/ElderOverview and history representations | Review stale/access/late/capability separation and bounded snapshots; contribute mapping tests and actual review |
| S1 | RepositoryModels, all Interface invariants, CommandContext/recovery/PhotoSource abstractions and Gradle check task | Review environment scope, missing/unknown result semantics and later atomic-storage obligations; contribute integration/harness tests |
| S2 | All Shared wire DTOs, strict Boot mapper/BOM alignment, HTTP error handler and WireTransportContractTest | Review wire/status/header compatibility and future auth/transaction seams; contribute Server transport tests and actual review |

Every review must record role, actual reviewer, date and the same manifest/fixture/commit version. GitHub-account mapping is deferred; it is not required to continue local contract work. At least one Android owner and one Server owner must jointly sign the same version. No member is credited with agent-prepared code merely because a role appears above. Member PRs and six real reviews remain required for completion.

## Publishing and completion state

The initial audit found an unborn `main` and no remote branches or PRs. On 2026-10-16 the project lead approved publishing exactly three bootstrap files to `main`, followed by the existing engineering baseline, Gate 1 common artifacts and collaboration rules on `feature/12-gate1-contracts`. See [submission scope](../INITIAL_SUBMISSION_SCOPE.md) for the historical versus current inventory and exclusions. Publication uses a Draft PR referencing #12, never a closure keyword; commit/PR links and remote CI results are recorded in Issue #12 after publication. Publication is not formal acceptance, and this packet does not claim a PR already reviewed or remote CI already passed.

Publication hardening pins the official Gradle 8.9 distribution checksum and excludes private signing keys, local databases/journals, APK/AAB/heap dumps and Python caches. The review manifest covers every published file except itself, including the new governance documents and checker. ADR-0007 is the renumbered governance record; ADR-0005 retains sole-generator/DST and two-hour response choices; ADR-0008 adds the separate thirty-minute alert and scheduled probes; ADR-0006 remains unchanged. Prototypes/photos stay local and are outside the manifest and PR. The newly added governance rules retain non-author approval, Android/Server review and six actual role contributions; no GitHub settings change is claimed by checking in a settings checklist.

- [x] Common wire types, nine Repository Interfaces and local contract test code delivered.
- [x] Automated local checks executed; results and limits recorded.
- [ ] Six actual role reviews and attributable member code/test PR evidence linked.
- [ ] Android owner and Server owner formally sign the same reviewed artifact version.
- [ ] Initial repository submission/PR workflow is established and reviewed artifacts published.
- [ ] Project lead accepts Gate 1 closure against the evidence; Issue #12 remains OPEN meanwhile.

No Pairing feature, plan/event reducer, Room adapter, sync worker, alarm, database migration, Storage adapter or deployment was implemented in this turn.
