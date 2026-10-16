# Contract test plan — Gate 1 review draft

> Version boundary (2026-10-17): the r1 baseline, 33 scenario IDs and fixtures below are historical structural/regression evidence where replaced by [accepted V2 / #23](https://github.com/BRMRs/group33/issues/23). They cannot freeze replaced interaction/reporting behavior or prove the separate follow-up/probe requirements. Full V2 document/test obligations live in separate Draft PR #25; coordinated code/fixture migration remains [#24](https://github.com/BRMRs/group33/issues/24). See [onboarding](../AGENT_START_HERE.md) before proposing a test or claiming V2 coverage.

This is the full P0 test plan for [Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12). Shared wire, Server Jackson/MVC and Android caller/model contract tests now execute against actual declarations and [examples.json](examples.json); [the review packet](GATE1_REVIEW_PACKET.md) records classes, commands, results and limits. Existing scaffold regressions remain. These checks do not mark the 33 P0 scenarios below complete.

Approved technical baseline: `T01-T07/2026-10-16-r1` (project lead, 2026-10-16). All seven choices are resolved; six-role contributions and Android/Server sign-off remain pending. The existing 33 case IDs are retained and expanded below, not marked passing.

## Gate 1 code and actual evidence

- `shared/.../contract/WireContractTest`: 11 tests of wire shapes, optional/required/null/enum/list/Long behavior and logical identity representation.
- `server/.../contract/WireTransportContractTest`: 13 tests using the real Boot ObjectMapper and HTTP converter/security with a test-only probe. No production Pairing/Dose action endpoint is implemented.
- `android-app/.../core/data/contract/RepositoryContractsTest` (8) and `FeatureRepositoryHarnessTest` (7): local model/result/scope tests and caller harnesses for all nine Interfaces, not durable-adapter acceptance.
- `ApiErrorMessagesTest` includes bilingual P0 mapping/resource checks and safe unknown fallback.
- `./gradlew gate1Check test` currently passes Shared 16, Server 30, Android Debug 23 and Android Release 23 JVM tests (92 variant executions, not 33 completed P0 scenarios), lint (0 errors/28 warnings), Debug assembly and Server bootJar. GitHub CI is configured but has not run on unpublished local files.
- Formal review stays role-based pending team action; account mapping is deferred, signatures and member contributions are not fabricated. Complete production acceptance remains mandatory in the linked downstream Issues.

## What Gate 1 should eventually prove

1. Shared serializers and both endpoint/Repository adapters agree on payloads, nulls, enum values, errors, time fields and logical identity.
2. Each feature author can exercise their proposed interface through a fake adapter, without an Activity calling HTTP or SQL.
3. Authorization, transaction, outbox, idempotency and recovery acceptance assertions are specified now and are exercised against real production adapters in later implementation issues. A scripted fake returning the desired result is not proof that a Server business rule works.

## Test layers and proposed locations

| Layer | Proposed future location | Authors | Scope |
|---|---|---|---|
| Shared wire | `shared/src/test/kotlin/.../contract/` | S2 maintains; feature owners add fixtures | kotlinx encode/decode, round-trip required/null fields, enum separation, request/receipt keys |
| Server HTTP/serialization | `server/src/test/kotlin/.../contract/` | S2 + feature author | Jackson compatibility, status/header/error body mapping, subject extraction, request scope. Start with controller adapter fixtures when implementation arrives |
| Android Repository | `android-app/src/test/kotlin/.../contract/` | E/C1/C2/C3 per interface, S1 common harness | Fake adapters, typed failures, operation identity, observed state; real Room/HTTP adapter tests added with their feature issues |
| Real persistence/API | Server integration tests + Android adapter tests in ordinary feature issues | S2/S1 and relevant feature author | Actual transactions, uniqueness, stored receipts, authorized photo bytes, durable outbox/merge |
| Device and end-to-end | Android instrumentation and recorded two-device checklist | E/C1/C2/C3 + S1/S2 | Local notification/process/boot, bilingual/accessibility, full causal path. Assigned to later delivery issues |

## Shared fixture data

Use fictional `elder-1`, `caregiver-1` (active), `caregiver-2` (unrelated), `elder-2` (other relationship), plan `plan-1`, photo `photo-1`, and a second plan revision. Local test date is 2026-10-20 in `Asia/Shanghai`; it is a fixture, not the current device time. Inject a controllable clock into tested adapters. The r1 two-hour values agree with the latest V2 response duration, but do not cover the separate thirty-minute notification, T−5/T+5 probes or group/reporting migration. Coordinate affected vectors through #24; use controlled clocks, not real waiting or relabeled historical evidence.

[examples.json](examples.json) contains a P0 confirmation request/response, one timely offline Taken and receipt, the separate Pending sync local representation, a late report, and an error. These are illustrative JSON instances, not a full OpenAPI/JSON Schema or executable test harness. Test code must read the actual DTOs/production adapters when those exist; parsing these examples alone does not prove an API implementation.

## Test matrix

| ID | Scenario and required assertions | Lead / contributor | Where proved |
|---|---|---|---|
| W01 | Shared/Jackson agree on canonical fields, enum values, null/list behavior and epoch-millisecond integers; verify every route's concrete response, including plan collection/revisions, update result, photo metadata, terminal pairing and capability receipts | S2 + S1 + each feature owner | Gate 1 serialization suites, migration PR |
| W02 | Stable error codes map to English/Chinese resources; unknown code has safe localized fallback; server message is not the only user copy | C1 + C2/C3/E; S2 envelope | Gate 1 mapping tests; UI implementation |
| A01 | No/expired JWT cannot access protected API; recover same installation session and retry the original action/key | S2 + C1/S1 | Auth HTTP/integration + session adapter |
| A02 | Code entry creates PENDING; accept alone authorizes; reject/cancel/replace/expire consume relevant opportunity without linking; a new accept/reject/cancel command on a participant's closed request returns non-retryable 409 PAIRING_REQUEST_CLOSED; exact accepted-command replay preserves success/204, unrelated callers still receive generic 404 | C1 + E + S2 | Pairing production tests and two-device flow |
| A03 | At most one active Caregiver per Elder, one Caregiver can have independent Elder relations, role changes denied | S2 + C1 | Server transaction/auth tests |
| A04 | Wrong relationship/existence probing gets generic response; either participant revoke blocks later Caregiver access/replays; known revoked cache not shown authorized | S2 + C1/C3 | API + Repository access tests |
| A05 | After process recreation or 409 PAIRING_REQUEST_CLOSED, a participant can fetch known terminal pairing result/reason; rejected/expired/replaced/cancelled requests do not disappear as an indistinguishable empty Pending list; accepted relationships include matching participant summaries | C1 + E/S2 | Pairing detail/list serialization, Repository refresh and production API |
| P01 | Failed upload exposes no ready photo/partial Medication; retry/lost response returns one result; object-write/metadata-failure is staged/reconciled without asserting a distributed Storage/DB transaction | E + S2 | Photo adapter and production storage/API |
| P02 | Photo-content access authorized at fetch time, including revoked and unrelated callers; no public permanent Storage URL in view | C2 + S2 | HTTP access tests, real storage adapter |
| P03 | P0 confirmation uses an authorized ready photo, creates Medication + plan atomically; edits return Medication and plan so both caches update together; invalid field leaves previous state and no new event | C2 + S2 | Request mapping + Server transaction test |
| P04 | Wrong-Elder or not-ready photo is rejected with recoverable field/reason; form values retained | C2 + E/S2 | Repository mapping + production validation |
| D01 | Inclusive date range, unique HH:mm list, IANA zone and 2h/10m values; no silent zone migration | C2 + S1/S2 | Shared vectors + domain production tests |
| D02 | Matching known zones have no blocking warning; different/unknown context requires explicit acknowledgment/converted example | C2 + E | Mapping tests + UI evidence |
| D03 | Scheduled→Due→Snoozed→Due and Due/Snoozed→Taken; fixed ten minutes from recordedAt; repeat target deadline−1ms can return only while window open; target at/after deadline produces no repeat and deadline without timely Taken→Missed; no shortened delay or timer restart on late receipt; Remind later recorded at deadline rejected; Taken never downgraded by delayed snooze | E + S1/S2/C3 | Action/event contract vectors and production reducer tests |
| D04 | Timely local Taken received after deadline reconciles provisional Missed; Late taken remains attached to Missed on Android before sync as well as on Server, with independent Pending sync and recorded/received timestamps | S2 + E/C3/S1 | Fixtures plus service/repository reconciliation tests |
| D05 | Plan/text edits and deactivation preserve stored event snapshot, current Due/Snoozed and retained historical revision | C2 + S2/S1 | Production revision/Room tests |
| D06 | Invalid/overlapping plans atomically fail with field reason; exact overlap predicate must be reviewed first, not guessed from unrelated medications sharing a time | C2 + S2 | Agreed vectors then validator/transaction tests |
| S01 | Same action key and payload after lost response/restart/manual retry produces one result and matching receipt; a second Taken tap cannot add a second outcome | S1 + S2/E | Outbox/API production tests |
| S02 | Same key with changed target/payload returns IDEMPOTENCY_KEY_REUSED; revoked access is checked on replay; accepted update replay returns its receipt before current expectedRevision validation, so a lost success is not falsely a version conflict | S2 + S1 | Server stored-receipt/authorization and transactional concurrency tests |
| S03 | Local event change and outbox entry commit together; storage failure reports failure, never falsely Recorded/Pending | S1 + E | Real Room transaction + feature mapping tests |
| S04 | Offline/transient timeout/5xx remains Pending; terminal domain rejection preserves report as Needs attention; manual retry does not invent new ID | E + S1/S2/C3 | Repository tests + real sync adapter |
| S05 | Token recovery/429 retry retains the action and status; cache-write failure after a service commit is reconciled without duplicate creation | C1/C2 + S1/S2 | Session/online-commit adapter tests |
| S06 | Snapshot merge preserves local pending report, late history and immutable event identity; missing out-of-range item is not hard-deleted | S1 + E/C3 | Room merge tests |
| S07 | Action for offline-generated event without server event ID is resolved from retained revision/key to one canonical event; revision is not a second occurrence identity | S2 + S1/E | Real service/Room test |
| S08 | Older snapshot/receipt after a newer accepted action, newer refresh, environment/session change or known revocation cannot regress or reauthorize Room; opaque snapshotVersion is never lexicographically sorted; scoped single-flight/generation checks and read-after-write behavior hold | S1 + S2/E/C3 | Controlled request-order contract vectors, then real sync/Room tests |
| S09 | Capability sequence 2 applied before delayed sequence 1 stays current; replay uses same report/sequence, same sequence with different payload conflicts, receipt ack is separate from current last-known view | E/C3 + S1/S2 | Shared fixtures, Server report update and Android merge tests |
| R01 | Observations are Elder-scoped; switching selected Elder never exposes another Elder's stale data; feature caller cannot set another user's identity | C1/C2/C3 + E/S1 | Repository contract suites |
| R02 | Failed Caregiver save preserves prior confirmed plan/form; success with unresolved local cache does not falsely report an uncommitted failure or retry under a new key | C2 + S1/S2 | Repository adapter tests |
| R03 | Caregiver refresh yields last-successful-sync time/stale flag; capability is last-known/timestamped and separate from DoseStatus/SyncState | C3 + E/S1/S2 | Repository mapping + service snapshot tests |
| R04 | Pairing/photo/plan commands survive response loss and process recreation with the same persisted context; result distinguishes NOT_SUBMITTED/AWAITING_CONFIRMATION/CONFIRMED-cache-pending/REJECTED; resume cannot auto-submit an offline Caregiver edit; photo metadata is observable before any Medication exists | C1/C2/E + S1/S2/C3 | Public Repository/journal contract vectors then production adapters |
| U01 | Local Taken plus Pending, Synced, Needs attention, degraded and unavailable remain distinct in bilingual UI and accessibility copy; Late taken does not repaint Missed | E/C3 + C1/C2 | Mapping cases then UI/device tests |
| O01 | Process/boot persistence, no replay of Taken/expired events, deadline takes precedence over snooze including same-instant alarm and delayed delivery/recovery, focused API matrix and controlled demo timing | S1 + E/C3 | [Implement permissions, alarm/process/reboot recovery](https://github.com/BRMRs/group33/issues/18), not a Gate 1 fake |
| O02 | Render and local fallback use the same payloads; stored business data/photos survive service restart | S2 + S1/C3 | [Replace temporary server stores and deploy the selected stack](https://github.com/BRMRs/group33/issues/16), [Implement and rehearse the local demonstration fallback](https://github.com/BRMRs/group33/issues/20) |

## Approved T01–T07 subcases (extend the existing 33 cases)

| Source / case IDs | Required vectors and assertions | Proof / owners |
|---|---|---|
| T01 — D01/D03/D04/S07 | P0_TIME_V1; New York 2026-03-08 02:30 → 07:30Z (03:30−04:00), 2026-11-01 01:30 → 05:30Z once; non-one-hour gap; planned instant inclusive; deadline−1ms timely, deadline/deadline+1ms late; fixed 7,200,000 real ms; no frozen-time rewrite on TZDB difference | C2/E/S1/S2; identical Shared/Server and API 23/35 vectors, production resolver proof |
| T02 — D05/S03/S06/S07/S08/O01 | Only Elder generates real events; next-24-hour rolling generation; seven-day preview has no event ID/history; offline >24 hours and reboot replenish; existing SCHEDULED stays old after edit/deactivation; old-revision offline timely action registers atomically if needed; register/action either order, repeated key/new transport batch for same generation produces one canonical event; conflicting evidence preserves original and Needs attention | E/C2/S1/S2; Room transactions, Server registration/action production integration, controlled response ordering |
| T03 — D06/P03 | Same Elder/Medication/same resolved instant fails atomically; different Medication same instant allowed; same text is not identity; own prior revision excluded; inclusive end boundary; DST nominal-time collision; unbounded ranges not validated solely over downloaded dates; frozen events not duplicated after deactivation/new plan | C2/S2/S1; validator algorithm plus transaction tests |
| T04 — A02/A03/A05/S01 | Eight-digit leading-zero code; exact 10-minute server expiry; pending inherits expiry; consumed code never reopens after reject/cancel; replacement/new request needs new code; code hash collision regenerated; 5/min/install, 20/min/IP, 3 issuance/min/Elder and Retry-After recovery; cancel-first forbids accept, accept-then-revoke ends revoked, no second active relation; terminal metadata retained 30 days, dedup evidence through P0 | C1/E/S2; real clock-controlled pairing transactions and limiter |
| T05 — W01/S01/S02/S04/S05/R02/R04 | P0_CANONICAL_V1; object order/localTimes permutation equivalent after duplicate rejection; documented optional absent/null equivalent, missing required invalid; text exact; same-key different bytes/target rejected; accepted replay before current revision check; concurrent in-progress 409 retryable+Retry-After:1 stays awaiting; explicit fixed-precondition same-payload retry vs corrected new intent; no rejected auto-replay; P0 receipt retention; compound typed result refs | S1/S2 + feature author; serializers, HTTP headers, journal and actual DB uniqueness/receipt transaction |
| T06 — P01–P04/R04 | Valid JPEG/PNG at 5,242,880 bytes allowed, +1 rejected 413; unsupported 415, spoofed/undecodable/over-20-MP content rejected; multipart overhead not counted as file size; 10s connect/120s total unknown completion; object success/metadata failure, response loss, process death/original URI loss reuse retained private bytes/hash; READY never overwritten; cleanup needs >24h plus no reference/live lease and reconciliation | E/C2/S1/S2; real Storage/JDBC fault injection and target-device memory checks, both cloud/local adapters |
| T07 — S06/S08/S09/R03/O02 | Default 30-date history/seven-day preview not retention/offline limits; 90 inclusive query dates and 10,000 entity cap (+1 rejected, no truncation); primary Repeatable Read authorization/multi-table snapshot; read-after-write; old generation cannot merge; Room keeps old reports; capability seq+outbox atomic/reboot safe; cloud/local pending isolated | C3/S1/S2/E; real PostgreSQL/Room controlled transactions, not mocks alone |
| T07 — O02 final fallback | With cloud/network egress unavailable but local LAN intact, preprepared APK/dependencies and local Spring Boot+PostgreSQL+private-file adapter complete real two-device login→pair→photo→plan→reminder→action→feedback, survive server restart including photo bytes, then demonstrate offline action/recovery; no Render/Supabase/Auth/CDN/download needed, no final-state seeding | S2/S1/C3 + all feature owners; recorded local rehearsal and repeatable scripts |

[examples.json](examples.json) supplies illustrative baseline vectors and configuration values, including `snoozeBoundaryVectors` for D03/O01 and `closedPairingRequestResponse` for A02/A05. These are not executable acceptance. All 33 IDs remain planned; the subcases above expand their obligations without implying new passing tests. Explicitly report which subcases execute at Gate 1 and which require the downstream real adapter/device Issues.

## Future execution and evidence

- Shared: `./gradlew :shared:test`; Server: `./gradlew :server:test`; Android JVM: `./gradlew :android-app:testDebugUnitTest`. Test classes, JSON fixtures and their assertions must appear in the corresponding PR before claiming those cases pass.
- Real Room/instrumentation and device acceptance require the agreed device/emulator environment; ordinary unit tests cannot prove notification delivery or reboot behavior.
- Each PR states covered test IDs, uncovered downstream cases, commands/results and production adapters exercised. Android and Server reviewers sign the same fixture version.
- Document/fixture checks and scoped scaffold regressions must not close this Issue or label the full proposed cases as passing business tests. Report actual test classes/assertions separately from downstream P0 acceptance.
