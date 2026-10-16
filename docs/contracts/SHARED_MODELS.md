# Shared data models — Gate 1 review draft

Reading status (2026-10-17): the declarations below describe the compiled **r1 legacy contract**, not a frozen ELDER_REMINDER_V2 model. Current product authority is [#23](https://github.com/BRMRs/group33/issues/23) and the accepted ADR-0008 in [separate Draft PR #25](https://github.com/BRMRs/group33/pull/25); version/cutover, group identity and Caregiver audit models remain technical work in [#24](https://github.com/BRMRs/group33/issues/24). Follow [the onboarding version boundary](../AGENT_START_HERE.md) before proposing a change; this notice does not change wire fields or reopen product decisions.

Status: specification baseline `T01-T07/2026-10-16-r1` for [Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12). P0 wire declarations now compile under `shared/src/main/kotlin/com/group33/shared/contract/` and are exercised by Shared/Server/Android tests; see [Gate 1 review packet](GATE1_REVIEW_PACKET.md). Existing `ApiError`, `User` (aliased as UserView) and DoseStatus are reused without breaking scaffold callers. The seven choices remain approved; six-role contribution and formal Android/Server sign-off remain outstanding. DTO declarations are not domain validators or business implementation.

## Layer ownership

| Layer | Contains | Excludes | Authors |
|---|---|---|---|
| `shared` | Serializable request/response models, stable wire enums and structured errors; no framework-specific storage state | Android Context, Room entities, Spring/JPA, JWT signing secrets, UI resource IDs | S2 maintains wire definitions; E/C1/C2/C3 author/review their feature fields; S1 reviews sync and mapping |
| Android domain/Repository | Cached data, local action receipts, sync state, feature results, user-visible data mappings | Raw HTTP exceptions or DAOs exposed to Activities | Each feature owner; S1 maintains common primitives |
| Room | Cached confirmed revisions/events, local actions/outbox, sync metadata, session storage adapter as appropriate | Being the remote business authority | S1 schema coordination; feature owners contribute their entities/mappers and tests |
| Server persistence | Stored identity/relationship, photo metadata, plans/revisions/events, action receipts/idempotency | Trusting client role/user ID/plan timestamps as authority | S2 |

Names ending `Request`, `Response`, `View`, `Receipt` are wire declarations, not database entities. `Schedule` remains legacy code wording; new product models use Reminder plan. `ContractProtocol.json` explicitly encodes nullable defaults and rejects unknown fields/enum coercion; the Boot mapper agrees on tested null/default/integer behavior.

## Common types and invariants

- `Id`: opaque non-empty string. User/resource IDs originate at the service. A locally generated **action ID** or idempotency key is a request identity, not permission to choose a user or overwrite a resource ID.
- `LocalDate`: ISO `YYYY-MM-DD`; `LocalTime`: zero-padded `HH:mm`; `TimeZoneId`: valid IANA identifier; `InstantMillis`: JSON integer epoch milliseconds. No platform date class is serialized directly.
- `Revision`: positive service-controlled integer. `expectedRevision` prevents a stale overwrite. An occurrence's historical plan revision remains available after edits.
- Optional fields are explicitly `null` in canonical fixtures; list responses use `{items: []}`, not null. Kotlin defaults/null omission and Jackson/kotlinx behavior must be jointly pinned by serialization tests.
- User-entered medication/dosage/instructions are strings, displayed as entered and never automatically translated. Blank required text, invalid dates/times/zones and duplicate local times fail validation. Approved limits/defaults are in [API contract](../API_CONTRACT.md); configuration is not permission to silently change acceptance.
- Canonical request version is `P0_CANONICAL_V1`: object order ignored; localTimes sorted only after duplicate validation; exact user text preserved. Optional `endDate`, `instructions` and `UpdateReminderPlanRequest.medication` have absent/null equivalence (no end date, no instructions, and no Medication update respectively). A supplied MedicationText is a complete confirmed text value; its null instructions clears instructions. Required-field absence fails. Future omit/no-change versus null/clear fields must remain distinguishable.

## Identity, pairing and relationship wire models

| Model | Required fields | Optional fields / constraints |
|---|---|---|
| `AnonymousLoginRequest` | `installationId`, `role: ELDER/CAREGIVER`, `displayName` | Preserve existing development request shape; one installation role is fixed |
| `AuthResponse` | `accessToken`, `tokenType`, `expiresInSeconds`, `user: UserView` | Token is private session material, not logged or placed in general UI state |
| `UserView` | `id`, `role`, `displayName` | May reuse current `User` via explicit mapping |
| `PairingCodeView` | `id`, `code`, `expiresAtEpochMillis` | Eight numeric characters, including leading zeros; 10-minute initial lifetime. Code shown only to its Elder; replay does not extend lifetime |
| `RequestPairingRequest` | `code` | Typed input for `POST /pairing-requests`; code is not authorization |
| `PairingRequestView` | `id`, `elderId`, `caregiver: UserView`, `status`, `expiresAtEpochMillis` | Status PENDING/ACCEPTED/REJECTED/CANCELLED/EXPIRED; nullable `closedReason` and `relationshipId`. Reasons ELDER_REJECTED/ELDER_CANCELLED/CAREGIVER_CANCELLED/CODE_REPLACED/CODE_EXPIRED; accepted request has relationshipId, no failure reason. Replaced code closes its request as CANCELLED with CODE_REPLACED. No arbitrary Elder medical data before acceptance |
| `CareRelationshipView` | `id`, `elderId`, `caregiverId`, `elder: UserView`, `caregiver: UserView`, `status: ACTIVE/REVOKED`, `createdAtEpochMillis` | `revokedAtEpochMillis`; both identity summaries must match their IDs and be visible only to the participants; enables the Caregiver's Elder list without another profile endpoint |

First valid request consumes the code and inherits its expiry. Reject/cancel never reopens it; code replacement closes its pending request. Serialize acceptance/cancellation/revocation and preserve one active relationship. Terminal-request metadata is queryable for 30 days; smaller deduplication evidence lasts through P0. Inactive relationships do not become authorization merely because a client cached an ACTIVE view. Rate limits and protected code persistence follow T04 in the API contract.

## Medication, plan and photo wire models

| Model | Required fields | Optional fields / constraints |
|---|---|---|
| `MedicationPhotoView` | `id`, `elderId`, `contentType`, `createdAtEpochMillis` | Published metadata means complete/ready object; no permanent public bucket URL. Bytes use the authorized content route |
| `MedicationText` | `displayName`, `dosageText` | `instructions` |
| `MedicationView` | `id`, `elderId`, `displayName`, `dosageText`, `active` | `instructions`, `photoId`; optional photo here does not remove the P0 photo-handoff prerequisite |
| `ReminderPlanDraft` | `localTimes: List<LocalTime>`, `startDate`, `executionTimeZoneId` | `endDate` inclusive or null; at least one unique local time; no PRN/complex recurrence |
| `TimeZoneAcknowledgement` | `executionTimeZoneId`, `mismatchAcknowledged` | `caregiverTimeZoneId`, `observedElderTimeZoneId` may be null; selected execution zone must resolve to an IANA zone |
| `ConfirmReminderPlanRequest` | `photoId`, `medication: MedicationText`, `plan: ReminderPlanDraft`, `timeZoneAcknowledgement` | Atomic dedicated P0 path; Elder comes from route and Caregiver from JWT |
| `ConfirmReminderPlanResponse` | `medication: MedicationView`, `plan: ReminderPlanView` | First success and retry identify the same resources |
| `ReminderPlanView` | `id`, `elderId`, `medicationId`, `revision`, `localTimes`, `startDate`, `executionTimeZoneId`, `active`, `effectiveFromEpochMillis`, `confirmedAtEpochMillis` | `endDate`; full current revision |
| `PlanRevisionView` | Same scheduling/identity fields as `ReminderPlanView`, plus `medicationSnapshot: MedicationText` | Frozen historical revision. Kept for occurrences generated before a later edit, including offline-generated events |
| `UpdateReminderPlanRequest` | `expectedRevision`, `plan: ReminderPlanDraft`, `timeZoneAcknowledgement` | Optional confirmed `medication: MedicationText` update; apply transactionally and preserve all prior event snapshots |
| `UpdateReminderPlanResponse` | `medication: MedicationView`, `plan: ReminderPlanView` | Both are the accepted transaction result, so Room can refresh both views together; immutable prior event snapshots remain unchanged |
| `DeactivateReminderPlanRequest` | `expectedRevision` | Typed input for deactivation; no hard-delete/history rewrite |
| `ReminderPlanCollection` | `items: List<ReminderPlanView>`, `planRevisions: List<PlanRevisionView>` | Do not place historical revisions into the current-plan list; included revisions cover the returned plans and generated-event context |

`ItemsResponse<T>` is the typed `{items: []}` wrapper for simple collection routes, distinct from ReminderPlanCollection's additional retained-revision list. Required lists cannot decode as null.

When the execution and Caregiver contexts match and are known, the UI does not demand mismatch acceptance. When they differ or an observed context is unknown, show both contexts/converted example and require explicit confirmation. An unknown context does not permit saving a plan without a resolved execution zone. A device-zone change alone never rewrites a confirmed plan.

These fields capture confirmation, not medical validation. T03 rejects repeated resolved instants for the same Elder/medicationId across active plans, including DST collisions; different medications may coincide and equal drug text is not identity. Own prior revision is excluded when editing; existing generated events remain frozen. T06 photos are JPEG/PNG, ≤5,242,880 bytes and initially ≤20,000,000 decoded pixels. Internal STAGING is not public ready metadata; Android retains private source bytes/hash until acceptance and resolution.

## Occurrence and action wire models

`OccurrenceKey` is the normalized tuple `{planId, localDate, localTime}`. Its uniqueness excludes revision and UTC offset: a plan/date/time yields at most one Dose event even after edits or a daylight-saving repetition. A frozen event records which plan revision and planned instant actually produced it. An offline local event may not yet have a canonical server `id`; local joins use the tuple until acknowledgment supplies it.

| Model | Fields and meaning |
|---|---|
| `OccurrenceGenerationEvidence` | `generationId` (installation-generated UUID), `occurrence: OccurrenceKey`, `planRevision`, `generatedAtEpochMillis`, `executionTimeZoneId`, `plannedAtEpochMillis`, `resolvedOffsetSeconds`, `resolutionPolicyVersion: P0_TIME_V1`, `sourceSnapshotVersion`, `sourceSnapshotServerAtEpochMillis`. Frozen from a confirmed revision; source snapshot context is not an authorization token or tamper-proof timestamp |
| `OccurrenceRegistrationRequest` | `items: List<OccurrenceGenerationEvidence>`; owning Elder route and batch Idempotency-Key; no client-chosen canonical event IDs |
| `OccurrenceRegistrationReceipt` | `generationId`, `receivedAtEpochMillis`, `event: DoseEventView`; correlates one registration with the canonical event |
| `OccurrenceRegistrationResponse` | `items: List<OccurrenceRegistrationReceipt>`; replay preserves accepted result, not latest global state |
| `DoseEventView` | `id`, `elderId`, `occurrence: OccurrenceKey`, `planRevision`, `medicationId`, `medicationSnapshot`, `executionTimeZoneId`, `plannedAtEpochMillis`, `responseDeadlineEpochMillis`, `generationEvidence: OccurrenceGenerationEvidence`, `status`, nullable `snoozedUntilEpochMillis`, nullable `takenRecordedAtEpochMillis`, `lateTakenReports: List<LateTakenReport>`, `serverUpdatedAtEpochMillis`; duplicated frozen fields must agree with evidence |
| `DoseActionRequest` | `actionId`, `occurrence`, `planRevision`, `generationEvidence: OccurrenceGenerationEvidence`, `kind: TAKEN/REMIND_LATER`, `recordedAtEpochMillis`; actionId equals Idempotency-Key. Outer tuple/revision must equal evidence. Atomic registration+action works even before separate registration; caller cannot choose another Elder |
| `LateTakenReport` | `actionId`, `recordedAtEpochMillis`, `receivedAtEpochMillis`; preserves the report alongside Missed |
| `DoseActionReceipt` | `actionId`, `receivedAtEpochMillis`, `classification: TIMELY_TAKEN/LATE_TAKEN/REMIND_LATER/SUPERSEDED`, `event: DoseEventView`; a replay returns the stored receipt, while later refresh supplies newer event state |

`DoseStatus` remains exactly SCHEDULED/DUE/SNOOZED/TAKEN/MISSED. `Late taken` is a report attached to Missed, not a sixth dose state. `Pending sync` does not go into this enum. The service derives deadline as original planned instant + two hours; Remind later does not alter it. A timely recorded Taken may arrive after deadline and still reconcile provisional Missed. A stale Remind later receipt never downgrades an accepted Taken; a later snapshot governs current shared state.

For Remind later recorded inside the Response window, the nominal repeat time is `recordedAtEpochMillis + 600_000`. `snoozedUntilEpochMillis` is that target only if strictly before the original deadline; it is null when no repeat can occur before expiry. Such an action can remain SNOOZED until the deadline, then becomes MISSED unless a timely Taken is known. The deadline wins a tie; no shortened-delay or post-deadline notification is created. Server receipt time never restarts the delay. See D03/O01 and `snoozeBoundaryVectors` in the fixtures.

`P0_TIME_V1`: preserve nominal tuple; gap shifts forward by the entire gap, fold chooses earlier instant once. `[plannedAt, responseDeadline)` is left-closed/right-open; deadline equals Late taken and pre-Due actions fail. Freeze instant/offset/policy through TZDB changes. API 23/35 and Server use identical golden vectors.

T02: Elder execution installation alone generates real events in a rolling next 24 hours; Room atomically persists generation/evidence and registration work, continuously replenishing offline. A seven-day preview is a computed Android view, NOT DoseEventView and not immutable history. All real generated events, including SCHEDULED, keep their original context on edit/deactivation. Retained old revision alone never rejects an offline report. Conflicting evidence is preserved for Needs attention rather than first-network-arrival overwrite. Server validates the authorized confirmed version and supplies canonical IDs, not an independent competing generator.

## Capability and snapshot wire models

| Model | Fields and meaning |
|---|---|
| `ReminderCapabilityReport` | `reportId`, positive persisted `observationSequence`, `observedAtEpochMillis`, `deliveryCondition: AVAILABLE/DEGRADED/UNAVAILABLE`, `notificationPermissionGranted`, `channelEnabled`, `exactAlarmAccess: NOT_REQUIRED/GRANTED/DENIED`, `reasons: List<String>` |
| `ReminderCapabilityView` | Report fields plus `receivedAtEpochMillis`; all are last-known reports, never real-time or notification-delivery proof |
| `ReminderCapabilityReceipt` | `reportId`, `receivedAtEpochMillis`, `applied: Boolean`, `current: ReminderCapabilityView`; lower-sequence reports can be acknowledged without replacing current. Reject same-sequence/different-payload; a retry preserves sequence and ID |
| `ElderSnapshot` | `elder: UserView`, nullable own `careRelationship`, ready `photos: List<MedicationPhotoView>`, `medications`, `reminderPlans`, `planRevisions`, `doseEvents`, nullable `capability`, `fromDate`, `throughDate`, `snapshotVersion`, `serverAtEpochMillis` |
| `ApiError` | `code`, `message`, `retryable`, `requestId`, optional `fieldErrors: List<FieldError>` and `reason`; `FieldError` has stable `field` and `code`. Reason is never an existence oracle on inaccessible resources |

`snapshotVersion` is service-generated and opaque, not a client-side timestamp cursor or sortable version. Serialize sync per actor/environment/Elder and discard responses invalidated by a newer request/action/access generation. A snapshot includes every revision needed to interpret the returned events and current plans in its range. A rejected Caregiver snapshot is not an empty authorized snapshot. Successful Room merge preserves local action evidence and generated historical event snapshots. Capability ordering uses its persisted observation sequence rather than the device wall clock or request arrival order.

T07 bounds: history default 30 local dates, preview seven days, at most 90 inclusive query dates and 10,000 returned entities with the counting rule in API_CONTRACT; oversize is `422 SNAPSHOT_RANGE_TOO_LARGE`, never truncated success. Snapshot authorization/lists come from one primary read-only Repeatable Read transaction. Capability sequence+outbox persist atomically through reboot. Scope data by environment/installation/Elder. P0 keeps business rows, generated evidence and pending/rejected reports; date windows are not retention or offline expiry. Cloud/local use the same models, migrations and authorization; only JDBC/Storage/environment adapters vary.

## Android-only model drafts

Caller-facing types below now compile in `android-app/.../core/data/contract/RepositoryModels.kt`. `GeneratedOccurrenceRecord`/`OutboxEntry` describe later internal persistence work and are not implemented storage records. No Room or journal adapter is provided. CommandContext scopes bind environment/installation/user; exact request binding and persistence must be enforced by later feature adapters.

| Model | Meaning and required information |
|---|---|
| `Cached<T>` | `value`, `hasLocalData`, `lastSuccessfulSyncAtEpochMillis?`, `isStale`, `access: AUTHORIZED/UNKNOWN/REVOKED`; connectivity is separate. Cached authorized data may be stale while temporarily offline; known revocation denies presentation |
| `SyncState` | `PENDING_SYNC`, `SYNCED`, `NEEDS_ATTENTION` for a recorded action; last error/retry metadata separate; temporary offline alone does not change Pending to Needs attention |
| `LocalActionReceipt` | Stable `actionId`, `occurrence`, `kind`, `classification: TIMELY_TAKEN/LATE_TAKEN/REMIND_LATER`, `recordedAtEpochMillis`, `syncState`; returned only after local event + outbox commit. A local Late taken report leaves effective dose status Missed while its report is Pending sync |
| `DoseEventState` | `occurrence`, nullable `serverEventId`, frozen occurrence fields, `effectiveStatus: DoseStatus`, nullable `lastAcceptedStatus: DoseStatus`, `localReports: List<LocalActionReceipt>`. A timely pending Taken can overlay a provisional server Missed; a late report cannot. Allows a new offline occurrence before a server event ID exists |
| `PairingDecisionResult` | Updated request plus nullable accepted relationship, tagged ACCEPTED/REJECTED; avoids treating rejection as missing/unknown success |
| `Outcome<T>` | Success with value or typed failure for reads, local actions and settings; not a two-valued substitute for an ambiguous online write result |
| `CommandContext` | App-generated UUID `operationId`, bound to actor/environment/operation/target and canonical payload under `canonicalizationVersion: P0_CANONICAL_V1`, persisted before first send; unchanged on resume. Not a user-selected resource ID |
| `OnlineCommandResult<T>` | Tagged by `status`: CONFIRMED (`operationId`, `value`, `cacheState: APPLIED/REFRESH_REQUIRED`), AWAITING_CONFIRMATION (`operationId`), REJECTED (`operationId`, typed service error), or NOT_SUBMITTED (`operationId`, local error). Confirmed with pending cache refresh is not a failed service save |
| `OnlineCommandRecord` | Scoped `operationId`, `commandKind`, nullable `targetId`, `state: NOT_SUBMITTED/AWAITING_CONFIRMATION/CONFIRMED/REJECTED`, nullable accepted `resultRef`, `cacheState`, nullable error; exposes no secrets/raw request payload to UI. Its private journal holds the original payload/key for recovery |
| `OutboxEntry` | Request identity/payload and scope, attempts, retry metadata, accepted receipt or terminal error; persisted across restart, never regenerated for retry |
| `CommandResultRef` | `kind: USER/PAIRING_CODE/PAIRING_REQUEST/CARE_RELATIONSHIP/PHOTO/MEDICATION/REMINDER_PLAN/DOSE_EVENT/CAPABILITY`, opaque `id`; accepted typed compound results retain all their resource references |
| `GeneratedOccurrenceRecord` | Android-owned frozen generation evidence, event state and durable registration identity/status; an accepted canonical event ID may initially be absent. Never expose arbitrary generation/context edits to the ViewModel |
| `PlanPreview` | Android-computed date/time/instant and revision context for an ungenerated candidate; not a DoseEventView, no canonical ID or recorded outcome |

`CommandContext.operationId` becomes HTTP Idempotency-Key. The journal retains the original payload privately through process death; UI never receives secrets/raw transport payload. `409 OPERATION_IN_PROGRESS` with retryable=true and Retry-After:1 stays AWAITING_CONFIRMATION. Keep accepted receipts, keys and evidence throughout P0. A stored rejection is separate from acceptance: explicit retry may reuse original key/payload after a precondition is fixed, while corrected content is a new intent. Never auto-resume a definitive rejection or invent a replacement Elder report. Previously unattempted offline Caregiver/Pairing writes remain disallowed.

Remaining interface helper types have these minimal shapes: `SessionView` contains auth readiness and nullable `UserView`, never a token; `LocalDateRange` has inclusive `fromDate/throughDate` with valid order; `LanguageChoice` is FOLLOW_SYSTEM/ENGLISH/SIMPLIFIED_CHINESE; `RetryQueued` and `CapabilityQueued` identify the durably queued existing action/report; `SyncSummary` includes scope, completion/partial outcome, last successful sync and typed error; `PhotoSource` is a readable device-local stream reference for one retained upload attempt, while `PhotoContent` supplies MIME type and readable bytes/stream. `ElderOverview` composes current Elder identity, matching events/medication text, last-known capability and last-sync/stale/access data from Room. These types belong to Android, not Shared HTTP models.

`OnlineCommandKind` identifies ISSUE_PAIRING_CODE, CANCEL_PAIRING_CODE, REQUEST_PAIRING, RESPOND_PAIRING, CANCEL_PAIRING_REQUEST, REVOKE_RELATIONSHIP, SUBMIT_PHOTO, CONFIRM_PLAN, UPDATE_PLAN or DEACTIVATE_PLAN. The registered feature adapter owns its payload/target validation. The common command journal is recovery infrastructure, not a caller-controlled arbitrary HTTP transport.

These Android-only objects are not serialized as arbitrary server Dose event state. A client sends Elder action facts; it does not PATCH the server to a chosen `status`.

## Migration from the scaffold

| Existing contract | Required draft change | Review owners |
|---|---|---|
| `MedicationSchedule` without zone/version | Introduce `ReminderPlanView` + retained revisions + confirmation/update requests; retain the legacy wire shape during migration while validating fields and denying unverified access | C2/S2/S1 |
| `DoseEvent(scheduleId, plannedAt, status, confirmedAt?)` | Add occurrence tuple, frozen revision/text, deadline, reported/received times, late reports and receipt identity | E/C3/S1/S2 |
| `Medication.photoUrl` | Move to authorized `photoId` metadata/content retrieval; do not reinterpret a public URL as authorization | E/C2/S2 |
| `CareRelation` without lifecycle | Add pending-pairing model and ACTIVE/REVOKED state; authenticate and authorize separately | C1/E/S2 |
| `ApiError`/`FieldError` replace the old unused `ErrorResponse(message)` | Scaffold envelope is implemented; complete P0 feature errors and localized recovery mapping | All feature owners; S2 maintains transport format |
| Legacy `MedicationRepository` now has caregiver/Elder/date-scoped flows | Migrate preview queries to the full feature-scoped Room-backed interfaces, explicit commands and local acknowledgment | C1/C2/C3/E/S1 |

Contract PRs migrate remaining serializers, callers and tests together; partial renames must not break the build. The scoped error-model migration is covered by Shared serialization and Server transport regressions. Fail-closed legacy endpoints and scoped preview queries still do not constitute the complete P0 authorization/data path.
