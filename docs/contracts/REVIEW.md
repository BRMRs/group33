# Android and Server contract consistency review

Current status: compiled Gate 1 common contracts and local checks are ready for team review. Earlier document-only/source-hash records below are historical evidence of those earlier turns, not claims that source is still unchanged now.

Scope: API contract, Shared models, Android Repository proposals, test plan and example JSON for [Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12). The initial agent review was document-only, not teammate sign-off. Later scaffold corrections and the current Gate 1 contract-code/test delivery are recorded separately; see [current review packet](GATE1_REVIEW_PACKET.md). All six formal role reviews and Android/Server sign-off remain outstanding; account mapping is deferred by the project lead.

## Gate 1 code/test delivery — 2026-10-16

The project lead requested progressing Gate 1, and deferred mapping the six role slots to GitHub accounts. [The review packet](GATE1_REVIEW_PACKET.md) now pins common code, test scope, artifacts and pending actual role review. No individual teammate is credited with agent-prepared code and no formal signature is checked.

- Actual Shared P0 DTOs, nine Android Repository Interfaces/helper models, three-layer contract tests and bilingual P0 error-code mappings now exist; scaffold feature code is preserved.
- Cross-serializer tests exposed Boot selecting serialization runtime 1.6.3 against Shared's 1.8.1-generated serializers (AbstractMethodError). The Server overrides Boot's serialization BOM property from the existing version catalog. The built bootJar contains serialization 1.8.1 and no test-only ContractProbe.
- Real Boot Jackson/MVC tests verify required/null/default/list/enum/Long and structured missing-header conversion. The probe lives only in tests; it is not a dose acceptance implementation.
- Current local suites: Shared 16, Server 30, Android Debug 23; additionally Android Release 23. All have zero failures/errors. `gate1Check test` also builds Debug APK/Server bootJar and runs lint (0 errors, 28 warnings: dependency-version notices and missing icon). This is 69 three-module JVM checks plus 23 Release variant executions, not the 33 P0 scenarios being fully passed.
- Test-only Repository doubles prove callable typed Interfaces/model distinctions, not durable Room, receipts, background retries, actual authorization or state reducers. Production evidence remains in #10/#13–#21; the closed Issue #9 delivery sequence is unchanged.
- At the original local checkpoint the repository had no initial commit and no commit/push/PR was performed. The lead subsequently authorized the three-file bootstrap and Draft PR publication documented in [submission scope](../INITIAL_SUBMISSION_SCOPE.md). Publication links and remote CI evidence belong in #12; neither publication nor locally passing CI configuration constitutes teammate sign-off.

## Determinable findings and corrections

| Finding | Android impact | Server impact | Document correction and regression cases |
|---|---|---|---|
| CR01 — P1: Pending-only pairing query cannot expose rejection/expiry/replacement after recreation | C1/E cannot distinguish terminal outcomes from no data | No route or closure reason for polling a known request | Add participant-only request detail and optional-filter list; closedReason/relationshipId and accepted participant summaries. A02/A05/A04 |
| CR02 — P1: Ordinary Outcome<T> cannot represent a lost online success or committed/cache-pending result | Recreated UI may submit a new key and duplicate a plan/photo; false save failure | Same logical operation may arrive as a second creation | Explicit OnlineCommandResult/CommandContext and a common recoverable command journal; each feature owns its own replay mapper. R02/R04/S01 |
| CR03 — P1: Local action model has no late classification | Offline Late taken could repaint Missed as Taken before Server corrects it | Server already distinguishes timely/late, so client diverges | Add report classification and effectiveStatus versus accepted status/local reports; late local fixture keeps Missed + Pending. D04/U01 |
| CR04 — P1: Opaque snapshot token and timestamp do not define safe merge order | Older in-flight response may undo Taken, overwrite newer refresh or reopen revoked cache | Replay receipts are historical; current-state read must follow accepted writes | Serialize scoped replay/merge, invalidate older request/access generations, avoid token/time sorting; preserve facts and read-after-write. S06/S08 |
| CR05 — P1: Version-check/replay ordering and same-key concurrency are unspecified | Lost successful update may be shown as a new conflict | Rechecking old expectedRevision before replay rejects an already committed write; concurrent keys may double-create | Authenticate/authorize, replay accepted receipt/binding, then validate a new command and atomically commit effect + receipt. S01/S02 |
| CR06 — P2: Capability arrival order can regress the last-known view | Old unavailable report may replace newer restored capability | Last network arrival is not last device observation | Persist per-installation observationSequence; acknowledge old reports without applying them; add separate capability receipt. S09/R03 |
| CR07 — P2: Response/model/read-path gaps | Photo ID may never enter Caregiver Room before Medication; plan edit leaves medication cache stale | Plan list promises revisions with no revision field; relationship list has no display identity | Add snapshot photos/photo refresh, ReminderPlanCollection, UpdateReminderPlanResponse, participant summaries and missing helper shapes. W01/P03/R04 |
| CR08 — P2: Upload test may imply an atomic transaction across object storage and PostgreSQL | Retry logic may incorrectly assume no private object exists after failure | Impossible all-or-nothing DB transaction is assumed across two systems | Separate private staging/object reconciliation from publishing ready metadata; require no usable partial data/duplicates, not an absence of every orphan object. P01/P02 |

The corrections above align representations with confirmed behavior. The subsequent T01–T07 approval below settles the technical choices; concrete declarations/adapters still require formal review and tests. The original review scope was document-only; later scoped implementation is recorded separately below.

## Resolved technical confirmations — 2026-10-16

The project lead explicitly approved T01–T07 as recommended, baseline `T01-T07/2026-10-16-r1`; [the decision record](TECHNICAL_RECOMMENDATIONS.md) preserves rationale and trade-offs. The earlier pending list is resolved, not superseded by an agent's assumption. No closed product decision is reopened.

| ID | Approved choice | Formal reviewers / verification still required |
|---|---|---|
| T01 | Gap shift; fold earlier instant once; [plannedAt, deadline); frozen instant/offset/policy | C2/E/S1/S2; API 23/35 + Server golden vectors |
| T02 | Elder sole real generator, rolling next 24 hours offline; seven-day previews distinct; all generated events immutable; evidence registration and inline action recovery | E/C2/S1/S2; old-revision/offline/order/reboot cases |
| T03 | Same Elder/Medication resolved-instant collisions, not different medications or mere date-range intersection | C2/S2/S1; open-ended/DST algorithm and counterexamples |
| T04 | Eight digits, ten minutes, consumed single-use code, approved limits, serialized transitions, 30-day terminal metadata | C1/E/S2; expiry/race/limiter/terminal recovery |
| T05 | Canonical typed payload, P0 accepted-receipt retention, retryable in-progress 409, explicit rejected retry and typed resultRef | S1/S2 + feature owners; equality/concurrency/recovery production tests |
| T06 | JPEG/PNG 5 MiB/20 MP, protected proxy, durable source, private staging/READY+receipt and safe 24-hour cleanup threshold | E/C2/S2/S1; limits/fault injection/device budget |
| T07 | Primary consistent bounded snapshot, durable capability sequence/evidence; cloud-independent local Spring Boot/PostgreSQL/private-file adapter | C3/S1/S2/E; data consistency, reboot, environment isolation and full offline-cloud rehearsal |

### Formal review is not waived

All six roles must be named and submit/review their own contract/model/mapping/tests under [Submission matrix](SUBMISSION_MATRIX.md). A named Android reviewer and Server reviewer must sign the same baseline/fixture/commit. Parameter approval is complete; the full P0 declarations, overlap/time-zone algorithms, production adapters and their acceptance remain unverified. The limited scaffold tests below do not complete Issue #12 or its downstream implementation Issues.

## Verification limits

The original document-only checks covered links, model/route names, JSON examples and test-case references. Examples alone do not execute a reducer, database transaction, HTTP handler or Android adapter. The P0 test plan retains 33 case IDs and eight feature interfaces plus one shared command-recovery interface. Original unchanged-source hash evidence is historical; the later scaffold correction changes source as recorded below. Full production tests and actual teammate sign-off remain outstanding.

### Synchronization verification — 2026-10-16

- At the original synchronization checkpoint, checked 38 local Markdown links across 13 changed/new Markdown documents; all resolved. This historical count covers that checkpoint's changed documents, not the current repository-wide total. JSON parses and illustrative TTL/action/evidence/deadline/New York vectors were consistent at that checkpoint.
- Preserved 33 test-case IDs and nine Repository Interfaces (eight feature + one recovery); subcases are specifications, not executed production tests.
- All 32 existing source/resource files have unchanged SHA-256 hashes and no source file was added/removed. No Gradle business-test pass is claimed for this document-only turn.
- Read back the main map and all 11 ordinary Issues: bodies match the updated baseline, all remain OPEN, original assignees and completion checkboxes are preserved; #12 remains assigned to BRMRs. [Approval/synchronization trace](https://github.com/BRMRs/group33/issues/12#issuecomment-6216776752).
- Repository documents/fixtures remain local pending future PR submission; no commit/push, feature implementation or formal teammate signature was performed.

### Local documentation consistency follow-up — 2026-10-16

- Checked the accepted Issue #4 decision before reconciling snooze wording: retain the fixed ten-minute delay and original deadline; expiry takes precedence over a repeat at or after the deadline. Corrected the inconsistent last-ten-minutes Glossary exception and aligned API/model/UI/architecture/handoff text, ADR-0003 and D03/O01 vectors. The nullable active snooze target is a concrete contract detail awaiting the existing formal implementation review.
- Clarified that the photo proxy checks current authorization and rejects a revoked Caregiver; Elder self-access remains available. Described legacy schedule relationship checks as a current implementation gap rather than an implemented guarantee.
- Specified `409 PAIRING_REQUEST_CLOSED` for a new command on a participant's closed request, with terminal-result recovery, successful-command replay and unrelated-caller privacy cases in A02/A05 and fixtures. This HTTP mapping remains part of the contract review draft.
- Clarified ADR-0006 implementation/acceptance as outstanding work tracked by Issue #20, and labeled the earlier link count as historical.
- Rechecked all 50 local Markdown links across the 26 repository Markdown files (16 contain local links); all resolved. JSON parses without duplicate keys; arithmetic and expectations for eight snooze boundary scenarios and the closed-request/replay fixture are internally consistent. The 33 case IDs and nine Repository interfaces are preserved.
- Compared SHA-256 hashes for all 32 existing Android/Server/Shared source/resource files before and after this follow-up; unchanged. These checks validate document/fixture consistency only. Production business tests, device behavior and formal signatures remain outstanding. No GitHub Issue was modified by this follow-up.

### User-authorized scaffold correction — 2026-10-16

- Replaced the unused free-text error model with Shared `ApiError`/`FieldError`; controller advice and both JWT security handlers emit safe JSON with matching `X-Request-ID`. Added typed login field/role errors and bilingual Android code-to-resource mappings with an unknown-code fallback.
- Added legacy schedule validation for real ISO dates, unique valid times, date order, required identifiers and non-negative timestamps. Android preview creation uses a valid date and UUID; invalid local replacements preserve existing data. This is not the P0 zone/revision/overlap validator.
- Legacy ScheduleController checks a relationship-access seam before exposing validation details, and the shipped adapter denies all access until real Pairing exists. Caregiver reads/writes cannot trust caregiverId alone; Elder self-reads remain scoped. Tests inject an explicit test-only relationship lookup to prove grant/revocation and field rejection. Resource-ID reuse cannot transfer ownership. No production consent/pairing route was added.
- Replaced the global Android schedule observation with caregiver-scoped and Elder/date-scoped queries, inclusive enabled-plan filtering and midnight refresh. Demo identity/Shanghai-time previews are labeled in both languages; they are not authenticated sessions. Adopted canonical role/product copy and light-only themes, with Force Dark disabled only in API-29+ resources. Enabled java.time desugaring for API 23.
- `./gradlew test :android-app:lintDebug :android-app:assembleDebug` passed: Shared 5 tests, Server 17 tests and Android 7 tests (both Debug and Release variants), no failures/skips. Lint has 0 errors; Debug APK built successfully. These are 29 distinct scaffold regression tests, not completion of the separate 33-case P0 plan.
- Started the real Spring Boot server on a local test port and verified HTTP health `200`, invalid login fields `422` with field errors, missing/invalid JWT `401` JSON, unpaired Caregiver write `404` and its subsequent empty list `200`. Error request IDs match their response headers. Stopped the test server afterward; test requests used fictional in-memory data.
- No Android device is connected and no emulator is installed in the configured SDK; device UI/API-23 runtime checks remain unverified. No commit, push, Issue edit or formal teammate signature was performed.
