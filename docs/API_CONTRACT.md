# API contract

> Legacy compiled-contract reference (2026-10-17): this file describes r1 code/fixtures, not a frozen V2 API. Current product authority is [accepted V2 / #23](https://github.com/BRMRs/group33/issues/23), with its full document update in separate PR #25 and affected protocol/migration decisions in [#24](https://github.com/BRMRs/group33/issues/24). Use [onboarding](AGENT_START_HERE.md) before choosing work; this notice neither changes wire fields nor implements V2.

Base path: `/api/v1`

The contract is deliberately small. Any incompatible change requires Android and server review in the same pull request or linked pull requests.

## Current product authority and migration status

**ELDER_REMINDER_V2 is the sole current behavior**, accepted by the project lead in [ADR-0008](adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). All current duration/authorization/presentation rules below use that baseline. Compiled r1 DTOs, examples.json and tests are structural/legacy regression evidence and still require coordinated V2 migration; no new endpoint/schema is implemented or jointly signed by this document.

The [technical queue](ELDER_UI_TECHNICAL_REVIEW.md) lists V2-T01–V2-T06. It cannot restore an old response window or make required follow-up/alerts optional. Route/model tables remain review drafts; missing group/help/takeover/Caregiver-entry/contact/notification contracts must be completed under #12 before affected adapters freeze.

## Review status and companion specifications

The behavior decisions are confirmed. The HTTP routes, new fields and return shapes below are an implementation-contract **review draft** for [Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12), assigned to `BRMRs`. Existing development endpoints are identified separately. This document does not claim that the proposed endpoints or tests already exist or that the Android/Server review is complete.

- [Shared data models and migration](contracts/SHARED_MODELS.md)
- [Android feature Repository interfaces](contracts/REPOSITORY_INTERFACES.md)
- [Contract test plan and fixtures](contracts/CONTRACT_TEST_PLAN.md)
- [Six-person submission and review checklist](contracts/SUBMISSION_MATRIX.md)
- [Android/Server consistency review and remaining technical items](contracts/REVIEW.md)
- [T01–T07 approved technical decision record](contracts/TECHNICAL_RECOMMENDATIONS.md)
- [Gate 1 compiled contracts, test evidence and formal review queue](contracts/GATE1_REVIEW_PACKET.md)

Baseline `T01-T07/2026-10-16-r1`: the project lead approved all seven technical recommendations on 2026-10-16, explicitly including Elder-only generation with a rolling 24-hour window and a fully cloud-independent fallback. Unaffected choices remain requirements, not open product questions; ADR-0008 retains the two-hour response duration, adds a separate thirty-minute notification and scheduled T−5/T+5 probes, and replaces the Elder-only outcome-recording restriction and refresh-only alert sufficiency. Concrete declarations still require six-role review, named Android/Server sign-off and actual contract/adapter tests; approval is not evidence of implementation or passing tests.

Gate 1 now provides compiled P0 DTOs in `com.group33.shared.contract`, eight feature Repository Interfaces plus one recovery Interface, and local wire/Jackson/MVC/caller tests. Required missing/null fields, unknown properties and fractional integers are rejected by the tested transport configurations; documented optional omission/null equivalence is retained. `__contract-probe` exists only in test sources. None of the future P0 route table is made operational by those tests; production handlers/adapters and formal sign-off remain outstanding. GitHub-account mapping of the six roles is intentionally deferred.

## Service and synchronization boundary

The confirmed cloud deployment is Spring Boot on Render, with Supabase PostgreSQL for structured business data and Supabase Storage for Medication photos; see [ADR-0004](adr/0004-hosting-storage-and-explicit-sync.md). Android uses explicit Room ↔ Spring Boot API synchronization. It does not access PostgreSQL directly or mandate a Realtime database subscription. Current required alerts need a separate reviewed timely delivery path, not refresh-only behavior. Photos use authenticated multipart upload and an authorized content proxy, with the same active care relationship as other Elder-data access.

Render and the local demonstration fallback expose the same application behavior contract. Their concrete API base URLs and environment configuration remain deployment work. Supabase storage does not replace the agreed anonymous installation identity, JWT authentication, or Pairing authorization.

The local fallback runs the same Spring Boot artifact with local PostgreSQL and a private local-file Storage adapter; the complete two-device path must work without Render, Supabase, cloud authentication, cloud photo URLs or runtime dependency downloads. Sessions, Room, outbox and upload intents are environment-scoped. Switching environments does not migrate cloud identities or Pending actions; prepare APKs/dependencies and run a fresh real login/pairing/photo flow on the LAN. See [ADR-0006](adr/0006-cloud-independent-demonstration-fallback.md).

## Implemented development endpoints

### Health

```http
GET /health
```

Response:

```json
{
  "status": "ok",
  "service": "group33-server"
}
```

### Anonymous login

```http
POST /api/v1/auth/anonymous
Content-Type: application/json

{
  "installationId": "random-uuid-created-by-the-app",
  "role": "CAREGIVER",
  "displayName": "Demo caregiver"
}
```

Response `201 Created`:

```json
{
  "accessToken": "signed-jwt",
  "tokenType": "Bearer",
  "expiresInSeconds": 86400,
  "user": {
    "id": "server-user-id",
    "displayName": "Demo caregiver",
    "role": "CAREGIVER"
  }
}
```

The client creates `installationId` once and stores it in private app storage. It must not use an IMEI, advertising ID, or another hardware identifier. Repeating this request with the same installation ID restores the same anonymous user and obtains a new token. An installation cannot change roles.

All other `/api/v1` endpoints require:

```http
Authorization: Bearer <accessToken>
```

### List reminder plans (legacy development endpoint)

```http
GET /api/v1/schedules
Authorization: Bearer <accessToken>
```

This development endpoint uses the legacy `schedule` path/model wording. Elder reads filter by the authenticated subject's `elderId`. Caregiver reads consult `CareRelationshipAccess` for each Elder rather than trusting a stored `caregiverId`. The shipped adapter denies all relationships until real Pairing and persistent relationship lookup exist, so the current Caregiver list is empty. This is a fail-closed scaffold, not completed Pairing. The canonical product term is `Reminder plan`.

### Save a reminder plan (legacy development endpoint)

```http
POST /api/v1/schedules
Content-Type: application/json
Authorization: Bearer <accessToken>
```

The current endpoint accepts the shared `MedicationSchedule` shape and stores it in memory. Only a Caregiver may write. It checks relationship access before field validation, then requires `caregiverId` to match the JWT subject and validates non-blank IDs, unique valid `HH:mm` times, real ISO dates, inclusive date ordering and non-negative update timestamps. Invalid fields return `422 VALIDATION_FAILED`; an existing resource cannot be moved to another owner/Elder by reusing its ID. The shipped relationship adapter denies all access, so current Caregiver writes return generic `404 RESOURCE_NOT_ACCESSIBLE` without saving. Real Pairing, persistence, server-issued P0 resource identities and revision/idempotency behavior remain unimplemented.

## P0 behavior contract to implement

The following routes and model specifications form the approved technical baseline for joint implementation review; every implementation must preserve these behaviors:

### Pairing and authorization

- An elder exposes a temporary, single-use pairing code.
- A caregiver entering the code creates a pending request only; the elder must explicitly accept or reject it.
- An active relationship authorizes the caregiver to view the elder's existing reminder plans, Medication photo, and reported Dose event history, and to create, edit, or deactivate reminder plans.
- The elder may view their own data and report their own outcomes. A caregiver must not impersonate an Elder `Taken` or `Remind later` action. An actively authorized Caregiver instead records a separate audited follow-up outcome after contact: Taken / Not taken / Unable to confirm. Entry, actual-time uncertainty, revision/audit and conflicts require the V2-T03 contract.
- Either participant may revoke the relationship. Subsequent caregiver access and changes must be rejected.
- Pairing and relationship mutations require server confirmation and cannot complete offline.

### Medication photo and reminder-plan flow

- The P0 path requires the elder to submit one fictional Medication photo before the caregiver confirms the first Medication and Reminder plan.
- Photo upload, retrieval, and authorization failures must be observable and retryable without partial or duplicate Medication data.
- OCR output, if added later, is only a suggestion and never authoritative.

### Dose events and synchronization

- A Reminder plan uses daily local times in the Elder execution time zone, an inclusive start date, and an optional inclusive end date.
- A plan/date/time combination produces at most one Dose event. Edits and deactivation affect only ungenerated occurrences; all real generated events, including SCHEDULED, keep their original meaning. A computed preview is not a generated event.
- `Remind later` requests the same event after a fixed ten-minute delay from the locally recorded action time. The two-hour Response window takes precedence: if the next reminder time is at or after its deadline, no further reminder is presented and the event becomes Missed at the deadline unless a timely Taken report is known. The delay is never shortened to force a final reminder before the deadline.
- Elder actions are idempotent and retain a stable logical identity across retries. Repeated submissions must not create duplicate caregiver results.
- An offline Elder action is immediately local and `Pending sync`; it becomes `Synced` only after the shared service accepts it. A rejected action remains recorded as `Sync needs attention` for appropriate Caregiver/diagnostic mapping; ordinary Elder Home/reminder screens never expose technical sync states or retry tasks.
- Explicit sync remains the data mechanism. Help, thirty-minute nonresponse follow-up and scheduled two-failure connection alerts are mandatory current scope; manual refresh/deferrable synchronization alone is not sufficient delivery. V2-T04/T05 specify probe transport, response waits/races, notification channels, retries, acknowledgement and local fallback.

### Required V2 group, follow-up and device-contact behavior

Single-/multi-medicine reminders share three group actions. Freeze membership/order and exact original deadline; no per-drug Elder form. Help immediately requests follow-up and suppresses repeated prompting. At plannedAt + 1_800_000ms, notify/update Caregiver follow-up without removing Elder actions or deriving Missed; only at plannedAt + 7_200_000ms remove old reminder surfaces/actions; contact leads to mandatory Caregiver outcome entry, not completion. Retain actual/entry/receipt times, explicit unknowns and contradictory delayed facts without silently overwriting history. Connection checks occur at original planned time T−5 minutes and T+5 minutes, with per-attempt response waits. Notify only when both have no valid response; any valid response prevents that round's two-failure alert. Old sync/report replay does not count as a probe response. No all-day heartbeat or real-time loss detection is required, and cause is not inferred. A remote takeover is pending until device acknowledgement, while the local cutoff remains enforceable offline.

These requirements are binding; new route names, wire fields, transaction boundaries and delivery/versioning details are deliberately not invented in this docs-only PR. See V2-T01–T05 before implementing; existing per-occurrence action routes alone do not implement the group or caregiver closure contract.

### Error and privacy behavior

- Errors use a consistent JSON body containing `message` and must distinguish authorization, validation, upload/load, and synchronization failures where the user needs different recovery actions.
- The server must not reveal whether an unrelated elder exists when rejecting unauthorized access.
- IDs remain opaque strings; dates use `YYYY-MM-DD`; instants use epoch milliseconds; local times use zero-padded `HH:mm` plus an IANA time-zone ID.

## P0 HTTP interface to implement

Paths below are relative to `/api/v1`. E means the authenticated Elder acting for themselves; C means an actively paired Caregiver for the target Elder. All except login require Bearer authentication. Subject/role come from the verified token, not a request-selected user ID. All proposed mutations, including multipart upload and DELETE, use `Idempotency-Key`; login restores identity via installation ID. A successful idempotent retry returns the original accepted resource/receipt, never an additional business result. Replayed data is not necessarily the resource's latest state.

| Method and path | Caller | Request → success | Important failure behavior |
|---|---|---|---|
| `GET /me` | Any authenticated installation | → `200 UserView` | `401` invalid/expired identity |
| `POST /pairing-codes` | E | Empty JSON, explicit renew uses a new key → `201 PairingCodeView` | Repeated same request must not replace the code; a newly issued replacement invalidates old code/request |
| `DELETE /pairing-codes/{codeId}` | Owning E | → `204` | Invalidates pending opportunity/request; cannot target another Elder |
| `POST /pairing-requests` | Caregiver role, may be unpaired | `{code}` → `201 PairingRequestView` | Code entry grants no relationship; invalid/expired/used/replaced code cannot authorize |
| `GET /pairing-requests?status=…` | Participants | → `200 {items: PairingRequestView[]}` | Filter is optional; omit it to include own terminal requests, not only PENDING |
| `GET /pairing-requests/{requestId}` | Either participant | → `200 PairingRequestView` | Read terminal result/reason after rejection, expiry, cancellation or replacement; unrelated caller receives generic 404 |
| `POST /pairing-requests/{requestId}/accept` | Target E | Empty JSON → `200 CareRelationshipView` | Atomically accept once, invalidate code, enforce one active Caregiver per Elder |
| `POST /pairing-requests/{requestId}/reject` | Target E | Empty JSON → `200 PairingRequestView` | Invalidate code/request; no active relationship |
| `DELETE /pairing-requests/{requestId}` | Either participant | → `204` | Cancellation is server-confirmed and invalidates relevant code/request |
| `GET /care-relationships` | Participants | → `200 {items: CareRelationshipView[]}` | Own relationship states only; revocation is visible to the participant, not unrelated users |
| `POST /care-relationships/{relationshipId}/revoke` | Either participant | Empty JSON → `200 CareRelationshipView` | Revoke access immediately for subsequent service requests; never remove Elder's own rights |
| `POST /elders/{elderId}/medication-photos` | Owning E | Multipart `file` → `201 MedicationPhotoView` | Retryable upload; no partial Caregiver-usable Medication and no duplicate photo result |
| `GET /elders/{elderId}/medication-photos` | E or C | → `200 {items: MedicationPhotoView[]}` | Only authorized ready photos; not an invitation to add photo-gallery scope |
| `GET /medication-photos/{photoId}/content` | E or C | → `200` image bytes | Service checks current relationship before proxying private Storage content; no public permanent bypass |
| `POST /elders/{elderId}/reminder-plan-confirmations` | C | `ConfirmReminderPlanRequest` → `201 ConfirmReminderPlanResponse` | Atomic photo-reference + confirmed Medication + initial plan command for the P0 path; invalid input leaves no partial plan/event |
| `GET /elders/{elderId}/medications` | E or C | → `200 {items: MedicationView[]}` | No cross-relationship access |
| `GET /elders/{elderId}/reminder-plans` | E or C | → `200 ReminderPlanCollection` | `items` holds current plans; separate `planRevisions` holds retained versions, including those required by generated events |
| `PATCH /reminder-plans/{planId}` | C | `UpdateReminderPlanRequest` → `200 UpdateReminderPlanResponse` | Return both Medication and plan so an atomic text/plan edit can update both local views; mismatch `409`, validation `422` |
| `POST /reminder-plans/{planId}/deactivate` | C | `{expectedRevision}` → `200 ReminderPlanView` | New prospective revision, no hard-delete/history rewrite |
| `GET /elders/{elderId}/dose-events?fromDate=…&throughDate=…` | E or C | → `200 {items: DoseEventView[]}` | Inclusive local-date query in the Elder execution context; retain Late taken reports |
| `POST /elders/{elderId}/occurrence-registrations` | Owning E only | `OccurrenceRegistrationRequest` → `200 OccurrenceRegistrationResponse` | Batch registration of durably generated occurrences; idempotent, never a Server-generated preview; conflicting evidence does not rewrite history |
| `POST /elders/{elderId}/dose-actions` | Owning E only | `DoseActionRequest` → `200 DoseActionReceipt` | Stable logical action ID; accept timely offline reports after deadline; late report does not change Missed to Taken |
| `PUT /elders/{elderId}/reminder-capability` | Owning E only | `ReminderCapabilityReport` → `200 ReminderCapabilityReceipt` | Ack this report separately from latest capability; a delayed older report must not replace a newer observation |
| `GET /elders/{elderId}/snapshot?fromDate=…&throughDate=…` | E or C | → `200 ElderSnapshot` | Complete bounded snapshot for explicit Room sync, including required retained plan revisions and service receipt time |

### Pairing and access errors

Missing/expired Bearer credentials return `401`. Wrong-role operations on the caller's known resources return `403`. Requests for unrelated Elder/photo/plan/event resources use the same `404 RESOURCE_NOT_ACCESSIBLE` shape whether the resource exists or not. A participant's own invalid pairing flow may report `PAIRING_CODE_UNAVAILABLE`, `PAIRING_REQUEST_CLOSED` or `ELDER_ALREADY_PAIRED`, without disclosing unrelated Elder records.

A new accept/reject/cancel command against a participant's already closed pairing request returns `409 PAIRING_REQUEST_CLOSED`, `retryable=false`. The participant can read its terminal result through the detail route. An exact retry of an already accepted command instead replays its stored success after current access checks; a replayed successful cancellation remains `204`. An unrelated caller still receives generic `404 RESOURCE_NOT_ACCESSIBLE`, not the closed-state error.

T04 approved configurable defaults: cryptographically random eight-digit string (leading zeros retained), 10-minute validity, pending request inherits the original expiry. The first valid request atomically consumes the code; rejection/cancellation never reopens it. New requests need a new code. Limit attempts to 5/minute/installation and 20/minute/IP; code issuance to 3/minute/Elder. Return `429` with `Retry-After`; do not lock an existing relationship. Effective code hashes are unique; regenerate collisions, keep replayable code material private and out of logs. Retain minimal participant terminal-request metadata for 30 days and durable deduplication evidence for the P0 project. Serialize accept/cancel/revoke transitions: cancel-first prevents accept; accept-then-revoke ends REVOKED, never a second active relationship.

A caller can poll their known request ID for a terminal result. `closedReason` distinguishes code replacement/expiry and participant cancellation without adding a second active relationship state. Consuming a code never removes the participant's ability to see the request outcome. Before acceptance, no arbitrary Elder medication/history is returned; accepted relationship summaries contain both participants' display identities for the Caregiver's Elder list.

### Photo-to-plan transaction

The upload resource exists independently of a confirmed Medication, so the Elder can upload before the Caregiver enters its name/dosage. Only a successful complete upload exposes `MedicationPhotoView` to the Caregiver. The proposed content route proxies through Spring Boot and checks current authorization on every content request; a revoked Caregiver receives `404 RESOURCE_NOT_ACCESSIBLE`, while the Elder retains access to their own photo. S2 will implement Supabase Storage access behind it.

T06: accept decodable JPEG/PNG only, at most 5 MiB (5,242,880 file bytes) and initially 20,000,000 decoded pixels. Validate actual bytes, not only the declared MIME/extension. Return `413 PHOTO_TOO_LARGE` for file-byte overflow, `415 PHOTO_TYPE_UNSUPPORTED` for unsupported format; malformed/over-budget decoded content is `422 VALIDATION_FAILED` with a scoped field code. The multipart envelope allowance must not accidentally reject an otherwise valid boundary-sized file. Connect timeout defaults to 10 seconds, total image-request budget 120 seconds; timeout leaves completion unknown, not definitively rejected. No resumable-upload or image-editing scope is added.

Storage object upload and PostgreSQL are not one database transaction. Bind the stable operation/photo/object identity, upload privately as STAGING, then publish READY metadata and the accepted receipt in one database transaction after object availability is established. READY bytes cannot be blindly overwritten. A failed attempt may leave a private unreferenced object but no usable partial Medication or duplicate upload. Cleanup has a 24-hour threshold AND requires no READY/Medication reference, no live upload/recovery lease and reconciliation; age alone never authorizes deletion. Android retains private readable source bytes plus SHA-256 through unknown/rejected/pending recovery and process death, until READY confirmation and no unresolved intent. Both Storage adapters obey this protocol.

`reminder-plan-confirmations` is the dedicated P0 photo-handoff command: it carries `photoId`, confirmed Medication text and plan draft together. The service checks that the photo is ready and belongs to that Elder, then atomically commits Medication, plan revision and receipt; it does not materialize independent Server occurrences. A retry returns the same Medication/plan. This command does not declare that all future Medication records must have photos. Direct Medication creation and generic schedule-status PATCH are not part of this P0 route set.

T03 overlap means a repeated resolved planned instant for the same Elder and `medicationId` among active plans, including duplicate local times or DST-induced collisions. Different Medication IDs may share a reminder instant; identical names do not establish identity and the two-hour response window is not a dose-spacing validator. Exclude an updated plan's own previous revision; inactive plans do not occupy new configuration, but generated events remain protected. Return atomic `422 OVERLAPPING_PLAN` with authorized field/reason. Open-ended/DST validation must cover the supported plan range, not merely the current download horizon; algorithm and counterexamples require implementation review.

### Writes, revision checks and idempotency

- Scope each key by authenticated actor and operation; bind it to the full target and normalized request payload. Same key/same request returns the same accepted effect; same key/different payload returns `409 IDEMPOTENCY_KEY_REUSED`.
- `DoseActionRequest.actionId` equals the request's idempotency key. Room persists it once and reuses it after timeout, manual retry or process death. A second Taken action still cannot create a second outcome for the same occurrence.
- Capability reporting likewise persists `reportId` and uses it as the idempotency key for that report; it does not issue a new report identity merely because delivery is retried.
- Authentication and current authorization are checked on every request, including replay; a key cannot restore revoked Caregiver access.
- Processing order is authentication/current authorization → find a stored accepted receipt and verify its target/payload binding → replay it if present → otherwise validate `expectedRevision`/input → atomically commit business changes plus receipt. A lost successful response must not turn its retry into a version conflict just because the first attempt advanced the revision.
- A new stale save returns `409 PLAN_VERSION_CONFLICT` without partial changes. Concurrent same-key requests serialize on a unique durable operation record and cannot both create resources. This is defensive protection, not a new multi-writer P0 feature.
- Plan changes require network/service confirmation. Elder action recording succeeds locally first, and the service receipt changes synchronization state only after durable acceptance.
- `408`, `429` and transient `5xx`/network loss retain Pending sync and the original key. Token expiry triggers session recovery without changing the action identity; it does not immediately make the report non-retryable. Terminal domain rejection preserves the local action as Sync needs attention.
- The durable accepted receipt proves completion; a domain rejection never does. Manual retry preserves the original action/key and cannot promise success if the rejection cause remains. A corrected payload is a new explicit intent, not permission to rewrite an existing Elder report or silently reuse its key. The error's `retryable` flag does not override session-recovery handling for 401.
- Service/database transaction and durable receipt design is S2's implementation responsibility; outbox/event atomicity and persistent action identity is S1's infrastructure responsibility with feature-owner integration.

T05 adds the following mandatory rules:

- Use a random UUID per logical intent; bind actor/environment/operation/target and canonical payload under `P0_CANONICAL_V1`. Typed object field order is irrelevant. Validate local-time duplicates before sorting; preserve user text exactly. Normalize absent/null only for fields explicitly defined as equivalent; required missing fields fail. File identity includes exact bytes' SHA-256 and controlled MIME, never multipart boundary/filename.
- Keep accepted receipts, occurrence uniqueness and report evidence throughout P0; no automatic 24-hour eviction. A stored rejection is not an accepted receipt. Retain its identity, fingerprint and error separately.
- If same-key processing exceeds a bounded wait, return `409 OPERATION_IN_PROGRESS`, `retryable=true`, `Retry-After: 1`. Client remains AWAITING_CONFIRMATION and retries the original key; no new polling resource is needed.
- Unknown results/transient failures may auto-resume. Explicitly rejected online commands never blindly auto-resume. A user may explicitly retry the same payload/key after a precondition is fixed; changing content creates a new intent/key, while the original Elder report remains preserved. Current authorization and revision checks still apply if nothing was accepted.
- Accepted result references use `{kind, id}` with USER/PAIRING_CODE/PAIRING_REQUEST/CARE_RELATIONSHIP/PHOTO/MEDICATION/REMINDER_PLAN/DOSE_EVENT/CAPABILITY kinds; compound results retain their typed response, not an arbitrary client-selected reference.

### Error envelope and localization

The scaffold implements shared `ApiError` and `FieldError` types plus controller/security JSON handling with `message`, stable `code`, `retryable`, optional `fieldErrors`, optional `reason` and opaque `requestId`; the same ID is returned in `X-Request-ID`. Malformed JSON/missing required fields return `400 MALFORMED_REQUEST`; well-formed invalid login fields return `422 VALIDATION_FAILED`. Re-registering an installation with a different role returns `409 INSTALLATION_ROLE_CONFLICT`. Remaining P0 error cases below are still implementation requirements; see the [example fixtures](contracts/examples.json). `reason` is allowed for a participant's own pairing/validation recovery only; generic inaccessible-resource errors never contain an existence-revealing reason. Android maps codes/reasons to Chinese/English resource strings, never shows raw server messages as the only localized explanation, and retains a safe fallback for an unknown code. No token/photo content/dosage text is included in diagnostics.

| Status | Representative code | Caller action |
|---|---|---|
| `400` | `MALFORMED_REQUEST` | Correct serialization/input; do not mark a dose outcome |
| `401` | `UNAUTHENTICATED` | Restore session and retry the same operation identity |
| `403` | `ROLE_NOT_ALLOWED` | Show authorization failure, preserve local Elder report if applicable |
| `404` | `RESOURCE_NOT_ACCESSIBLE` | Show generic unavailable/unauthorized state; do not reveal existence |
| `409` | `PLAN_VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `ELDER_ALREADY_PAIRED`, `CAPABILITY_SEQUENCE_CONFLICT` | Refetch/correct or show specific own-flow conflict |
| `409` | `INSTALLATION_ROLE_CONFLICT` | Keep the installation's established role; do not retry as a role change |
| `409` | `PAIRING_REQUEST_CLOSED` | `retryable=false`; fetch the participant's terminal request result; do not silently create another request |
| `409` | `OPERATION_IN_PROGRESS` | `retryable=true`, `Retry-After: 1`; retain AWAITING_CONFIRMATION and original key |
| `413` / `415` | `PHOTO_TOO_LARGE`, `PHOTO_TYPE_UNSUPPORTED` | Preserve form/source; corrected file is a new explicit intent |
| `422` | `VALIDATION_FAILED`, `PHOTO_NOT_READY`, `PAIRING_CODE_UNAVAILABLE`, `OVERLAPPING_PLAN`, `SNAPSHOT_RANGE_TOO_LARGE` | Show field/recovery reason; save has no partial effect; never silently truncate a snapshot |
| `429` / `503` | `RETRY_LATER`, `SERVICE_UNAVAILABLE` | Honor retry guidance, retain Pending sync |

### Snapshot synchronization

T07 uses a full authorized snapshot, not a delta cursor or Realtime subscription. Default history is the latest 30 local dates; preview covers the next seven days without creating events. A snapshot request is limited to 90 inclusive local dates and 10,000 total returned entities (count each top-level list item, each nested late-report item and each non-null Elder/relationship/capability singleton). Oversize returns `422 SNAPSHOT_RANGE_TOO_LARGE`; no truncation or partial-success snapshot. These are view/download limits, not retention or offline-execution limits. The snapshot's related tables and authorization are read on the primary database in one read-only Repeatable Read transaction; writes retain their own locking/uniqueness/authorization constraints.

Android stores each successful snapshot atomically in Room, then updates last-successful-sync metadata. It preserves local pending/rejected actions and immutable event snapshots while merging accepted server results. An absent item in a bounded response is not a global deletion instruction. Before exposing a Caregiver snapshot, revalidate the relationship; after learning of revocation, stop presenting cached content as authorized. A Caregiver refresh may trigger HTTP; screen observation itself reads Room.

The client serializes command replay and snapshot merge for each installation/session/environment/Elder scope. Each fetch carries a local request generation and access-generation token: a response started before an accepted action merge, a newer fetch, a session/environment change or known revocation is discarded and refetched as needed. `snapshotVersion` is an opaque equality token, not a sortable integer/string; server time is not an ordering authority. Refresh completion never lowers a locally acknowledged Taken to an older Due or reauthorizes a revoked cache. Local pending/rejected evidence survives independent of this fetch ordering. A replayed receipt acknowledges the action, not the latest entire event: it must not replace newer event facts or late reports.

The service's snapshot read after an accepted write must include that committed effect or a newer valid state; a stale replica/HTTP cache cannot satisfy this contract. Authenticated snapshots and relationship/receipt status responses must not be reused from a shared cache across users. Ready photo metadata is included even before its Medication/plan exists; structured snapshots contain references, not image bytes. Query dates use each event's retained local date/context, not a later device time-zone setting.

Capability reports have a monotonically increasing `observationSequence` persisted in Room in the same transaction as their outbox entry per Elder installation. A retry reuses both sequence and report ID across process/reboot. Server stores the highest observed sequence; delayed lower sequences are acknowledged but not applied, and same-sequence/different-payload is a conflict. `ReminderCapabilityReceipt` separates acknowledgment from the current capability. No reinstall/device migration is introduced. P0 does not automatically purge business Room rows, generated evidence, pending/rejected reports or accepted receipt evidence; synchronized image bytes alone may be evictable cache. Known revocation prevents authorized presentation, without inventing a new physical-deletion rule.

### T01/T02 occurrence generation and reconciliation

The Elder execution installation is the sole generator of real occurrences. It atomically persists frozen events/evidence and durable registration work in Room for a rolling next 24 hours, replenished locally during execution/recovery, including while offline beyond 24 hours. Server-computed seven-day previews never claim canonical event identity. Existing SCHEDULED/DUE/SNOOZED/completed events retain their revision/text/instant after plan edits or deactivation; only ungenerated parts adopt the later synchronized revision. UI distinguishes saved plan, not-yet-delivered plan and generated instances retaining old meaning.

`OccurrenceGenerationEvidence` retains the nominal tuple, `generationId`, `planRevision`, `generatedAtEpochMillis`, execution zone, planned instant, chosen offset, resolution-policy version and source confirmed snapshot context. The batch registration route uses Idempotency-Key; returned items correlate `generationId` with one canonical `DoseEventView`. `DoseActionRequest` always carries the same evidence, allowing atomic registration plus action acceptance when registration has not arrived. The service validates self/plan/retained confirmed revision and consistent frozen evidence; revision/offset never enter the occurrence unique key. Old revision alone is not rejection, and network arrival order never decides historical meaning. Contradictory evidence is a scoped validation rejection with reason, preserving the local record as Needs attention, never silent overwrite. Local timestamps/source context are reconciliation evidence, not tamper-proof authorization.

The existing r1 two-hour duration matches this clarification, but does not prove the separate thirty-minute notification or two-probe checks. The existing `P0_TIME_V1` DST algorithm resolves gaps/folds; V2 protocol/cutover is tracked in V2-T01 rather than silently reusing a legacy evidence identity. It resolves DST gaps by shifting forward by the gap duration, folds to the earlier instant once. Keep nominal date/time and offset; freeze the original instant/deadline. The response interval is `[plannedAt, plannedAt + 7_200_000ms)`: deadline−1ms is timely, deadline and later is Late taken, before Due is rejected. Snooze remains the same event and never extends this interval. Server JDK 17 and Android API 23/35 must pass identical vectors with explicit resolver/core-library desugaring support; TZDB differences are reconciled explicitly, never by rewriting stored instants. Golden vectors: New York 2026-03-08 02:30 → 03:30−04:00 / 07:30Z; 2026-11-01 01:30 → −04:00 / 05:30Z once.

For a valid `REMIND_LATER` action recorded inside that interval, compute the nominal target as `recordedAtEpochMillis + 600_000`, never from Server receipt time. Set `snoozedUntilEpochMillis` to that target only when it is strictly before the response deadline; otherwise leave it null and expire the Snoozed event at the original deadline. At execution/recovery, present a repeat only if the event is still non-final and the response window is open. Deadline processing wins over an alarm due at the same instant. A timely offline Remind later arriving after expiry can be acknowledged as an action fact but must not revive an expired notification; a timely Taken still supersedes it. Remind later recorded at or after the deadline is rejected, whereas a late report received from legacy/delayed evidence is retained separately. The current Elder UI offers neither action after cutoff; completion then is a Caregiver entry, not a late-action screen. Elder UI before cutoff shows the actual next reminder time, or briefly says that the response window ends before another reminder can occur.

## Contract rules

- IDs are opaque strings.
- Dates use `YYYY-MM-DD`.
- Local reminder times use zero-padded `HH:mm` plus the confirmed IANA Elder execution time zone.
- Instants use epoch milliseconds.
- The server validates that a caregiver has an active care relationship with the elder before accessing data.
- The client never chooses a user ID; it uses the ID returned by anonymous login.
- A JWT proves the caller's current anonymous identity, not a caregiver-elder relationship.
- OCR text is never treated as confirmed medication data.
- Errors use a consistent JSON body containing `message`.

T01–T07 choices are approved, but full contract-code/adapter PRs still await the six roles' contributions, named Android/Server sign-off on the same version and actual tests. Ordinary implementation Issues remain open. The scoped scaffold corrections described above do not complete those P0 requirements or imply formal sign-off.
