# Android Repository interfaces — Gate 1 review draft

> Version boundary (2026-10-17): snippets and invariants below describe legacy r1 declarations. Current product authority is [V2 / #23](https://github.com/BRMRs/group33/issues/23), with affected protocol/migration work in [#24](https://github.com/BRMRs/group33/issues/24). In particular, legacy Elder reporting/sync presentation is not a V2 UI requirement. Use [onboarding](../AGENT_START_HERE.md) to choose unaffected scoped work; no V2 adapter or contract freeze is claimed here.

These Interfaces now compile for [Implement shared HTTP contracts, feature Repository interfaces, and contract tests](https://github.com/BRMRs/group33/issues/12), under `android-app/src/main/kotlin/com/group33/medicationreminder/core/data/contract/`. Model names follow [Shared models](SHARED_MODELS.md). S1 owns shared infrastructure; each feature author owns their mapping/integration/tests. See [current code and evidence](GATE1_REVIEW_PACKET.md). No production adapter is registered by this change.

Baseline `T01-T07/2026-10-16-r1` is approved. The snippets match compiled declarations and have test-only caller harnesses. Six-role contributions and actual Android/Server sign-off remain required; GitHub-account mapping is deferred by the project lead.

## Interface invariants

- Business-data UI observes Room-backed `Flow<Cached<T>>`, keyed by Elder when applicable; it does not observe a global mutable list or call DAOs/HTTP directly. Session and language interfaces use their private device-local storage adapter rather than requiring tokens/settings in the business cache.
- A read observation does not itself promise network refresh. Explicit commands or background synchronization update Room; observers receive the resulting state.
- Online-only commands use `OnlineCommandResult<T>` to distinguish service-confirmed success, unknown completion, definitive rejection and no submission. A timeout is AWAITING_CONFIRMATION until reconciled with the same idempotency key. A successful service commit with failed local cache update remains CONFIRMED/REFRESH_REQUIRED. Do not replace the previous confirmed plan optimistically or call either case an ordinary failed save.
- An Elder action returns success after atomic local event/outbox persistence, before any HTTP is required. A timely Taken can show Taken plus Pending sync; a Late taken report stays attached to effective Missed plus its independent Pending sync. Local storage failure must not claim the action was recorded.
- A shared command journal creates/persists `CommandContext`; feature methods persist its bound request before HTTP. UI single-flight/duplicate-tap handling keeps the context but cannot be the only duplicate defense. A process-restored UI can observe/resume the journal entry without inventing a new ID. WorkManager and manual retry reuse the same stored Elder action.
- Cancellation, lost response, expired token and process recreation never silently erase a locally committed action. Cache merge does not overwrite pending facts with an older server status.
- Typed errors and access/sync/capability metadata remain visible to feature mapping code. Backend-only `message` is not the localization contract.
- A ViewModel uses these interfaces regardless of whether the adapter is a test fake or Room+HTTP. Worker scheduling, token secrets and network response handling remain behind internal seams.

## Proposed Kotlin-shaped interfaces

The snippets describe the public Interface, including adapter invariants above. Flow/result/helper types and all nine Interfaces are now declared in code; real persistence/network guarantees still require the downstream production adapters. Scripted test doubles do not discharge those guarantees.

```kotlin
interface AuthRepository {
    fun observeSession(): Flow<SessionView>
    suspend fun login(role: UserRole, displayName: String): Outcome<UserView>
    // Restores the same installation identity; not a role-change operation.
}

interface PairingRepository {
    fun observeRequests(): Flow<Cached<List<PairingRequestView>>>
    fun observeRelationships(): Flow<Cached<List<CareRelationshipView>>>
    suspend fun issueCode(command: CommandContext): OnlineCommandResult<PairingCodeView>
    suspend fun cancelCode(codeId: String, command: CommandContext): OnlineCommandResult<Unit>
    suspend fun requestPairing(code: String, command: CommandContext): OnlineCommandResult<PairingRequestView>
    suspend fun respond(requestId: String, accept: Boolean, command: CommandContext): OnlineCommandResult<PairingDecisionResult>
    suspend fun cancelRequest(requestId: String, command: CommandContext): OnlineCommandResult<Unit>
    suspend fun revoke(relationshipId: String, command: CommandContext): OnlineCommandResult<CareRelationshipView>
    suspend fun refresh(): Outcome<Unit>
}

interface MedicationPhotoRepository {
    fun observePhotos(elderId: String): Flow<Cached<List<MedicationPhotoView>>>
    suspend fun submit(source: PhotoSource, command: CommandContext): OnlineCommandResult<MedicationPhotoView>
    suspend fun loadContent(photoId: String): Outcome<PhotoContent>
    suspend fun refresh(elderId: String): Outcome<SyncSummary>
    // submit uses the current Elder identity; viewing checks access through HTTP.
}

interface ReminderPlanRepository {
    fun observePlans(elderId: String): Flow<Cached<List<ReminderPlanView>>>
    fun observeMedications(elderId: String): Flow<Cached<List<MedicationView>>>
    fun observePreview(elderId: String, range: LocalDateRange): Flow<Cached<List<PlanPreview>>>
    suspend fun confirm(elderId: String, draft: ConfirmReminderPlanRequest, command: CommandContext): OnlineCommandResult<ConfirmReminderPlanResponse>
    suspend fun update(planId: String, draft: UpdateReminderPlanRequest, command: CommandContext): OnlineCommandResult<UpdateReminderPlanResponse>
    suspend fun deactivate(planId: String, expectedRevision: Long, command: CommandContext): OnlineCommandResult<ReminderPlanView>
}

interface DoseEventRepository {
    fun observeEvents(elderId: String, range: LocalDateRange): Flow<Cached<List<DoseEventState>>>
    fun observeLocalAction(actionId: String): Flow<LocalActionReceipt?>
    suspend fun recordAction(occurrence: OccurrenceKey, kind: DoseActionKind): Outcome<LocalActionReceipt>
    suspend fun retryAction(actionId: String): Outcome<RetryQueued>
    // Actor, clock and persistent identity come from the adapter, not a caller-selected user/status.
}

interface CaregiverDashboardRepository {
    fun observeOverview(elderId: String, range: LocalDateRange): Flow<Cached<ElderOverview>>
    suspend fun refresh(elderId: String, range: LocalDateRange): Outcome<SyncSummary>
}

interface ReminderCapabilityRepository {
    fun observeCapability(elderId: String): Flow<Cached<ReminderCapabilityView?>>
    suspend fun reportLocalCapability(): Outcome<CapabilityQueued>
    // Reads local platform capability and queues an idempotent report; no proof of notification delivery.
}

interface LanguageSettingsRepository {
    fun observeChoice(): Flow<LanguageChoice>
    suspend fun setChoice(choice: LanguageChoice): Outcome<Unit>
    // FOLLOW_SYSTEM / ENGLISH / SIMPLIFIED_CHINESE; device-local, no new server API.
}

// Shared recovery infrastructure for the eight feature interfaces above.
interface OnlineCommandRepository {
    suspend fun begin(kind: OnlineCommandKind, targetId: String?): Outcome<CommandContext>
    fun observeCommands(): Flow<List<OnlineCommandRecord>>
    suspend fun resume(operationId: String): Outcome<OnlineCommandRecord>
    suspend fun retryRejected(operationId: String): Outcome<OnlineCommandRecord>
    // Explicit user retry of the exact original payload after fixing a precondition; never automatic.
}
```

`SessionView` exposes signed-in user/session readiness, not access-token text. `PhotoSource`/`PhotoContent` are Android-owned references/streams behind a photo adapter, not filesystem paths in Shared or a permanent authorized URL; an upload attempt retains its local identity for retries. `PairingDecisionResult` tags accept/reject explicitly. `DoseEventState` allows an offline occurrence without a canonical server event ID. RetryQueued/CapabilityQueued mean durable local queueing, not shared-service acceptance. `ElderOverview` composes Medication/event/capability data and last-sync state from Room; it does not need a second HTTP dashboard implementation.

`OnlineCommandRepository` creates only local attempt metadata, scoped to installation/session/environment. begin is not a business write. Feature adapters bind and canonicalize input under P0_CANONICAL_V1; NOT_SUBMITTED commands never auto-dispatch as offline Caregiver edits/Pairing. resume handles attempted AWAITING_CONFIRMATION or confirmed cache reconciliation. retryRejected requires explicit user intent, exact original payload/key and current online authorization; it re-evaluates a fixed precondition, not a cached rejection as success. A content correction calls begin with a new intent, preserving the old record. A definitive rejection never background-replays. OPERATION_IN_PROGRESS remains AWAITING_CONFIRMATION and honors Retry-After:1. Keep accepted evidence throughout P0.

`PairingRepository.refresh()` reads the caller's terminal as well as pending requests or polls known request IDs; accepted/rejected/expired states are observable after process recreation. Photo refresh loads authorized ready metadata into Room (also included in a full ElderSnapshot), resolving how the Caregiver obtains the photo ID before the first Medication exists. Photo content is not placed unconditionally in the structured snapshot. Plan-update response merges Medication and plan together; a receipt for an older revision cannot overwrite a newer local confirmed revision.

Action replay and snapshot merge share a serialized scope with request/access generation checks. An obsolete snapshot started before an accepted action, newer refresh or known revocation is not merged. A delayed accepted receipt can still acknowledge its matching command/action in the same scope, but its historical resource/event cannot replace newer state. Responses from a different session/environment never update the current scope. Opaque snapshot tokens and wall-clock timestamps are not sorted to guess freshness. Capability views apply observationSequence monotonically. Every feature mapper follows these rules; they are not additional tasks handed entirely to S1.

## Approved T01–T07 adapter obligations

- S1's internal generation/scheduler seam, coauthored by E/C2, materializes the next 24 hours only on the Elder execution installation, persists frozen occurrence+generation evidence+registration work atomically, and replenishes offline across days/reboot. Registration HTTP is internal sync work, not a new caller-controlled generation Repository. recordAction uses the existing frozen record and can inline its evidence before registration acknowledgment. No caller chooses another Elder, UTC offset or revision to rewrite an event.
- observePreview derives seven-day candidates from confirmed cached plans without persisting them as real events; overlay/exclude actual generated occurrences so the UI never presents a replacement for a frozen SCHEDULED event. C2 owns saved/not-delivered/retained-instance explanations; E/C3 own local/history interpretation. No independent Server preview may claim canonical event identity. P0_TIME_V1 and [API time vectors](../API_CONTRACT.md) apply everywhere.
- C2 owns duplicate-instant/overlap and DTO mapping with S2 validation; same Medication collision rejects atomically, different medications may coincide. Null/omitted field semantics follow SHARED_MODELS, not generic JSON equality.
- C1/E implement eight-digit/10-minute pairing, consumed-code and terminal-recovery mappings; Server owns authoritative expiration, rate limit and serialized state transitions. Replayed responses never reset a countdown.
- E/C2 photo adapters preflight JPEG/PNG, 5 MiB and 20 MP; retain readable private bytes/hash across URI expiry, process death and unknown/rejected attempts. S2 validates again, stages privately and publishes READY+receipt transactionally. Connect/total budget 10s/120s; timeout does not mean definite rejection. Changing bytes creates a new intent, not a retry under the old key.
- C3/S1 snapshot/history defaults are 30 local dates, preview seven days; query cap 90 inclusive dates/10,000 entities, oversize fails explicitly. No out-of-range deletion or automatic business/report/evidence purge. S1 persists capability sequence and its outbox in one Room transaction; same-report retry keeps ID/sequence.
- S2 provides primary read-only Repeatable Read snapshots and both Supabase/private-local-file Storage adapters behind the same internal Interface. S1 isolates base URL/session/Room/outbox by environment; switching never redirects cloud pending work to local. The complete local two-device flow has no cloud runtime dependency; every feature author owns its local-environment integration fixes.

## Feature ownership and real adapters

| Interface | Feature author(s) and concrete contribution | S1/S2 integration |
|---|---|---|
| Auth/Pairing | C1 writes contract, feature DTO/domain mapping, consent/login ViewModels and tests; E writes own consent mapping/cases | S1 session/private storage + shared HTTP; S2 real authentication/pairing/authorization |
| MedicationPhoto | E writes submit mapping/state/tests; C2 writes viewing/loading/access mappings/tests | S1 common transport support; S2 protected photo API/Storage |
| ReminderPlan | C2 writes Repository contract, confirmation/version/error mapping and tests | S1 common Room/network transactions; S2 plan/business validation/transaction |
| DoseEvent | E writes recording/confirmation mapping and tests; C3 writes history/late-report mapping and tests | S1 atomic local recorder/outbox; S2 deduplication/reconciliation |
| Dashboard | C3 writes Room-composed overview/Refresh contract, stale/access mapping and tests | S1 sync coordinator; S2 snapshot/capability endpoints |
| ReminderCapability | E writes permission-state mapping; C3 writes timestamped caregiver notice mapping | S1 platform adapter/queue; S2 stored last-known report |
| LanguageSettings | C1 writes choice persistence and resources; all feature owners own their strings/tests | S1 reviews device-local persistence/session isolation |
| OnlineCommand (shared recovery) | C1/C2/E retain contexts and implement registered feature replay/result mappings; C3 integrates pending/cache-recovery notices | S1 journal/protocol; S2 idempotent accepted receipts |

The adapter seam is where behavior actually varies: test fake versus production Room+HTTP, and platform reminder scheduling behind a test scheduler. Avoid one interface per HTTP endpoint or exposing SQL/worker queues to ViewModels. S1 reviews common schemas and infrastructure adapters; E/C1/C2/C3 implement their functional adapters/mappers and integration fixes in separate PRs against agreed interfaces.

## Internal implementation plan, not caller API

1. Room caches confirmed plans/revisions/events and stores local event update + outbox entry atomically.
2. An internal sync coordinator refreshes authorized snapshots and replays pending commands using the same request identity. WorkManager calls it; the Caregiver refresh command calls the same coordinator.
3. A scheduler adapter reloads persisted occurrences after process/reboot recovery and never treats work scheduling as proof of delivery.
4. S2 accepts action receipts transactionally and returns one canonical event. Feature mappers preserve local recorded time, server receipt time and last sync time separately.

Caller harnesses exercise all nine Interfaces; Room/HTTP/platform adapter tests later prove production behavior. Shared wire/Boot conversion tests run against actual declared DTOs. No Room entity, feature HTTP client, worker, alarm, P0 controller or business operation is implemented in this Gate 1 turn.
