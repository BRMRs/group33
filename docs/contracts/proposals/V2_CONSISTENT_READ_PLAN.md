# V2 consistent-read implementation plan

Status: **IN_REVIEW / IMPLEMENTATION PREPARATION**, Refs #24/#14/#15/#17/#16.
Owner: S2. Required conclusions: S1/Android, C3/projection, qualified non-author Server,
C1/authorization and C2/retained plan context where affected. No signature is supplied here.
Prepared against PR #131 `27a60c01c0771485a7c4008c938963404dd5a565`, containing main
`bbe4997034ecb837d2093f4e73267cc530976f29`. Sources: [API contract](../../API_CONTRACT.md#snapshot-synchronization),
[ADR-0008](../../adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md),
[lead implementation order](https://github.com/BRMRs/group33/issues/24#issuecomment-6940259272),
[C3/E acceptance candidates](V2_ACCEPTANCE_CANDIDATES.md).

This is a source map, transaction design and executable-test **plan**. It adds no endpoint,
DTO, reducer, SQL/Room schema, runtime flag or implementation test. Scenario IDs below are
planned acceptance, not passed tests. #130/#131 acceptance still precedes freezing affected
production fields. Their successful serialization suites do not prove this read path exists.

## Existing seams and exact reuse boundaries

Paths below are relative to the repository root at the stated source revision.

| Concern | Existing source / symbol | Reuse and gap |
|---|---|---|
| Principal | `server/src/main/kotlin/com/group33/server/pairing/VerifiedPairingActorResolver.kt`, `resolve` | Resolves a Spring-verified JWT against stored anonymous identity/role. Reuse this current authentication boundary for its existing runtime; it does not implement #96 phone/account/session rules. Caller body actor/scope is never authority. |
| Transaction | `server/src/main/kotlin/com/group33/server/pairing/PostgresPairingCodeStore.kt`, `planReadTransaction` | One DataSource connection, read-only Repeatable Read, local lock/statement timeout, rollback and close. Reuse the lifecycle, not several independent service calls. Current callback exposes only `PlanReadTransaction`; full-snapshot queries are not yet exposed. |
| Relationship | `PlanReadTransaction.activeRelationship`; JDBC `pairing_relationship` | Authorize Elder self or active related caregiver within the same snapshot as returned business rows. Do not use an earlier relationship list or replay receipt as permission. |
| Plans and retained text | `server/src/main/kotlin/com/group33/server/plan/PlanConfirmationStore.kt`, `medications`/`plans`/`revisions`; JDBC `medication`, `reminder_plan`, `reminder_plan_revision` | Keep immutable event revision/text after later edits/deactivation. Existing list queries load all rows and are not a 10,000-entity budget implementation. |
| READY photos | `server/src/main/kotlin/com/group33/server/photo/PhotoMetadataStore.kt`, `readyPhotos`; JDBC `medication_photo` | Include READY metadata before any plan exists. Need a read-only accessor in the shared snapshot; calling the existing photo service would create a separate transaction. No image bytes, object keys or permanent URLs in the structured projection. |
| Occurrences and current actions | `server/src/main/kotlin/com/group33/server/occurrence/OccurrenceRegistrationStore.kt`; JDBC `registered_occurrence`, `recorded_dose_event` | `recordedDoseEvents` joins **from recorded rows** to registrations. Registrations with no recorded action are omitted. Do not reuse that result as a complete event list. Plan a registration-rooted authorized range query, preserving evidence and overlaying current facts only through the accepted reducer. |
| Existing Debug reads | `server/src/main/kotlin/com/group33/server/occurrence/DebugDoseGroupController.kt`, `DebugDoseGroups.read` | Uses guarded write transaction and legacy status mapping. It is neither full read-only snapshot nor V2 follow-up/Snooze arbitration. Keep its scope separate; no silent routing of V2 through it. |
| Baseline response | `shared/src/main/kotlin/com/group33/shared/contract/SyncContracts.kt`, `ElderSnapshot` | Current DTO includes photos/plans/revisions/events/relationship/capability. It has no V2 group/follow-up/audit fields. Do not silently append a candidate wire or relabel r1 fixtures. |
| Capability | [#56](https://github.com/BRMRs/group33/pull/56) → [#76](https://github.com/BRMRs/group33/pull/76) → [#77](https://github.com/BRMRs/group33/pull/77) → [#103](https://github.com/BRMRs/group33/pull/103) | Chain remains unmerged in this check. Ordered reports and immutable receipts need final integration; future snapshot must read current accepted capability on its own snapshot connection. No second capability Store or receipt-as-current shortcut. |
| V2 group/follow-up | `shared/src/main/kotlin/com/group33/shared/contract/proposal/V2GroupContracts.kt` | Proposal DTOs only; no persisted group/current correction/audit reader exists on this baseline. An expected frozen group without canonical events must still support its authorized caregiver follow-up. Do not fabricate an event to satisfy a join. |
| Execution-zone context | [#130](https://github.com/BRMRs/group33/pull/130) | Separate proposed contract. Default latest 30 local dates needs accepted execution context; unknown/unavailable context cannot fall back to caregiver/device/plan zone. Explicit user dates retain their defined rules. |

The plan-read mechanism also exists in the source of historical [#98](https://github.com/BRMRs/group33/pull/98),
which is **closed without merge**. Its code is visible on the stated baseline; that does not mean
#98 itself merged or its prior test evidence was rerun here. Source presence and PR disposition
are separate facts. No dependency branch is merged or cherry-picked by this preparation.

## One request, one primary snapshot

1. Validate public request syntax and inclusive date ordering with bounded parsing. Resolve the
   verified principal and current installation/session context through the applicable accepted runtime.
   No caller-supplied actor, executionScopeId or environment can select a database or grant access.
2. Acquire one configured **primary** connection. Set read-only and Repeatable Read before business
   reads, establish local timeouts and disable autocommit. The existing `SELECT set_config(...)` may
   establish the MVCC snapshot before the authorization SELECT; do not assume authorization creates it.
   Replica lag or a shared HTTP cache cannot satisfy read-after-accepted-write.
3. On this connection, read the required Elder/relationship context and authorize the target. For an
   inaccessible target preserve the existing non-disclosing `RESOURCE_NOT_ACCESSIBLE` boundary.
   Resolve any new identity/session-table transaction requirements with #96 before its runtime cutover.
4. Read READY metadata, medications, current plans and required retained revisions, registered events
   in their retained local-date range, current action evidence and accepted capability through that
   **same** connection. V2 group/follow-up/audit accessors remain conditional on accepted contracts and
   migrations. Do not compose HTTP collection responses or nested service transactions.
5. Materialize detached values and evaluate the accepted projection without receipt replay mutating
   it. Preserve event medication text/revision, original group ordering and source-specific times.
   No external Storage fetch, notification send, outbox write or remote provider call inside the read.
6. Check the complete response budget before success, close result sets/statements, rollback the
   read transaction and close/return the connection on success or failure. Do not expose a lazy JDBC
   sequence after close. Pool state must be restored by the adapter/pool contract before reuse.
7. Return one complete authorized response with `Cache-Control: no-store`. Capture service receipt
   time consistently; it is not an event ordering authority. `snapshotVersion` is an opaque equality
   token; its concrete generation and any V2 envelope remain review decisions, not a cursor protocol.

An in-flight read can legitimately observe its original pre-revocation snapshot. A new read whose
snapshot begins after revocation must reject. Client-known revocation/session/scope/access-generation
changes must discard an older response. This does not promise impossible mid-query freshness or permit
presenting known-revoked cached data. After an acknowledged write, start a new primary snapshot to obtain
that committed effect or a newer valid state; do not reuse a transaction that began before the write.

## Date, entity and execution budgets

- Preserve the accepted **90 inclusive local-date** maximum. Reject inverted or oversized ranges;
  do not treat 90 days as 90 elapsed 24-hour intervals across DST. Query each event's retained local
  date; current device timezone cannot move history. Seven-day previews do not create occurrences.
- Preserve **10,000 total returned entities**: each top-level list item, each nested late report,
  and each non-null Elder/relationship/capability singleton counts. Required retained revisions and
  READY photos count even when no current plan refers to them. At 10,000 succeed; at 10,001 return
  `422 SNAPSHOT_RANGE_TOO_LARGE`, with no truncated success or partial Room replacement.
- V2 group/member/follow-up/audit representation must explicitly map into that existing total budget
  in same-head review. Do not decide to exclude nested V2 items or silently introduce audit pagination
  into a full-snapshot response. A separate history endpoint would need its own reviewed contract.
- Plan bounded queries with a remaining-budget-plus-one sentinel and bounded nested materialization,
  using overflow-safe counters. A single database row containing an unbounded JSON report array is
  not safe merely because SQL row count is bounded. Legacy unbounded list methods need an adapter
  change, not an assumption that the existing five-second statement timeout bounds memory.
- Retain current lock/statement timeouts, bounded acquisition/socket handling and deterministic ordering.
  Statement timeout is not an overall multi-query request deadline. Review a total request/cancellation
  budget and query plan for the real adapter; do not invent a new accepted timeout value here.
- Any query, budget or decoding failure aborts the whole response. No fallback-to-memory, stale cache,
  partial sibling lists or empty-on-database-error success. Retryable failure follows reviewed error mapping.

## Deterministic verification plan — not executed by this document

Use the real PostgreSQL adapter with independent connections and latch/barrier coordination, fictional
data, bounded waits and finally-block cleanup. Avoid real-minute sleeps and tests that only inspect
`Connection` properties. Observe actual database settings and results. Keep inherited tests attributable.

| ID | Controlled input / interleaving | Required observation and layer |
|---|---|---|
| CR01 | Inspect actual DB transaction settings; try an UPDATE inside the read callback | Read-only Repeatable Read; PostgreSQL rejects write (`25006`); rollback/close verified. Existing plan-read tests are precedent, not proof of the new snapshot. |
| CR02 | Reader pauses after authorization; writer commits plan edit, retained revision and revocation before reader resumes | Writer completes without reader holding issuance guard. Reader sees one old coherent authorized version; next caregiver request rejects; Elder new read sees new versions. |
| CR03 | Write an accepted command and commit before starting reader; test a lagging-replica/cache substitute separately | Primary read includes committed effect/newer state; stale substitute cannot pass. A pre-write reader is not used as the causal check. |
| CR04 | Register an occurrence but create no `recorded_dose_event`; include a second event with a current action | Complete snapshot retains both; no missing unacted event and no stale registration receipt replacing the action. |
| CR05 | R01 fixture: frozen group + UNKNOWN caregiver fact, no canonical events | Authorized group-linked follow-up/source/uncertainty retained; no invented Elder event. Requires V2 projection implementation. |
| CR06 | R02 fixture: correction revision 2; deliver late Elder Taken and replay original receipt | Revision 2 remains effective, all source facts/audit preserved. Repeat with reordered arrival and fresh read after each commit. |
| CR07 | Edit/deactivate plan after generation; query the retained event | Original medication text/revision/offset/instant survive; current plan can show its newer state separately. |
| CR08 | READY photo without medication/plan; also STAGING and rejected photos | READY metadata appears, unpublished rows and private object keys/bytes do not. No object-store request is made. |
| CR09 | Another Elder, unrelated caregiver, revoked relationship, forged body identity, wrong session/environment | Non-disclosing authorization failure; no sibling medical rows or existence leakage. Real phone/session cases await #96 runtime. |
| CR10 | Capability update between component reads, including an older receipt with a different current report | One coherent snapshot, no second-connection capability read; original receipt never overwrites current accepted sequence. Await capability chain. |
| CR11 | Exact 90/91 inclusive local dates, inverted range, DST transition and event whose original zone differs from device | 90 valid/91 rejected, retained local dates stable, no preview-generated canonical event. |
| CR12 | Exactly 10,000 and 10,001 counted entities; vary nested reports, optional singletons, revisions and photo-only rows | Exact inclusive entity budget; all-or-nothing `422` above limit. Repeat for accepted V2 nested representation when defined. |
| CR13 | Huge nested report payload and multiple sibling query results approach remaining budget | Bounded materialization/sentinel detects overflow before unbounded decode; no partial success. Validate real query plan/memory limits. |
| CR14 | Separate writer holds issuance guard while an authorized read runs | Read finishes without acquiring that guard; keep photo-content/write revocation locking behavior intact. |
| CR15 | Real table lock, cancelled slow statement, mapper exception and callback exception | Controlled failure, all resources released, next authorized read succeeds; no partial medical writes or polluted pool state. |
| CR16 | Pause query then cancel/request disconnect; repeat success/failure on a pooled connection | Cancellation/deadline policy is bounded; connection is cleaned up and next borrower gets correct isolation/read-only/autocommit state. |
| CR17 | Capture HTTP bytes and headers for success/denial/budget/database failure | Existing error envelope and non-disclosure retained, no-store headers, complete payload only; exact new route/error details require contract acceptance. |
| CR18 | Android fetch starts; then accepted action, newer fetch, known revocation or scope switch; old response arrives last | Discard/refetch via request/access generation. Atomic Room merge preserves pending/rejected/local immutable evidence. Requires S1 real adapter/device checks. |
| CR19 | Timezone unavailable or registry skew; explicit user range versus default latest 30 dates | No guessed default dates; explicit range follows accepted #130 rules. Unknown context does not destroy raw evidence. |
| CR20 | API/DB process restart after accepted write and before fresh read | No stale in-memory projection/receipt substitute; fresh authorized primary read retains committed state. |

## Implementation slices and review exits

1. **This PR preparation:** source map, gap list, query/transaction design and CR01–CR20 plan only.
   C3/S1/Server confirm V2 response shape, source/audit semantics and nested entity counting; E/S1/Server
   retain the separate Snooze rejection worksheet. Missing decisions do not justify synthetic fields.
2. **Read infrastructure slice after acceptance:** a narrow server-private read seam with bounded
   accessors on one connection, complete registration-rooted event read, authorized primary transaction
   and database tests. Reuse existing plan/relationship lifecycle; no second capability store. Name the
   new seam only in its implementation review; no speculative signature is frozen by this document.
3. **Projection + transport slice:** agreed V2 group/follow-up/current facts, fresh opaque snapshot token,
   HTTP/error/header/budget and three-runtime wire checks. Add only a forward migration if the accepted
   persistent design actually needs one; never reserve a version or alter applied migration history here.
4. **Android integration slice:** S1 owns Room merge/request generation, C3 current/evidence presentation,
   E reminder recovery, C2 retained text and C1 current authorization. Real HTTP/DB races, restart and
   bilingual device checks establish acceptance; documentation or serializer success does not.

Do not mark these slices Done, close #24/#15, submit another person's approval or merge main from this
plan. The lead retains integration/release authority. Rollback of this preparation is a document revert;
there is no deployed or persistent state change.
