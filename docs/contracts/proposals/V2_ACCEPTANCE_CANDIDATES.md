# V2 cross-client acceptance candidates

Status: **IN_REVIEW / NOT ACCEPTED**. Refs #24, PR #131. Owner: S2.
This packet answers [C3's projection feedback](https://github.com/BRMRs/group33/pull/131#pullrequestreview-5952701915)
and [E's action/Snooze feedback](https://github.com/BRMRs/group33/pull/131#issuecomment-6974680169).
It adds review scenarios using existing proposal DTOs, without defining a new response wire,
production reducer, error enum, endpoint, database/Room schema or Android UI.

`v2-acceptance-candidates.json` is a **test/review envelope**, not a production response.
`expected`, local states, eligibility flags and rejection descriptions belong only to that envelope.
Only entries in `wire` are existing proposal DTO payloads. All identities are fictional.
Tests check those payloads through strict kotlinx and the actual Boot ObjectMapper, and check
the consistency of the candidate expectations. They do **not** exercise a production projection,
concurrent correction, Room/outbox recovery, notification scheduling or Server rejection handler.

## Scenarios and observable outcomes

| ID | Input and expected observation | Basis / remaining decision |
|---|---|---|
| V2-R01 | Frozen expected group, no canonical events, authorized caregiver UNKNOWN follow-up. Return the group-linked record, caregiver source, revision, entry/receipt times and explicit unknown actual time. Do not manufacture an Elder event or Taken. | ADR-0008 §7–8; C3. Exact projection wire and current-authorization transaction need S1/Server review. |
| V2-R02 | Revision 1 says Taken; accepted correction revision 2 says Not taken. A contradictory timely Elder Taken arrives later; then its original command receipt is replayed. Current follow-up remains revision 2, while both caregiver versions and Elder evidence remain visible with their own sources/times. | ADR-0008 §8. Receipt is original acknowledgement, not the latest authorized projection. No new conflict-resolution task is forced on the caregiver. |
| V2-E01 | A Help action has alternative locally recorded, pending-sync, Server-accepted and Server-rejected observations. Each can be represented independently of SENT / DEVICE_RECEIVED / VISIBLE / READ delivery evidence. No delivery stage proves sync acceptance, Help delivery or ingestion. A remote observer cannot see an unsent local action. | ADR-0008 §11–12; E. These are alternative observations, **not** an accepted→rejected state transition. Local state labels are not new wire enums. |
| V2-E02 | Offline Snooze uses original action time + 600,000ms and original T + 7,200,000ms deadline. Receipt time does not restart either timer. A target equal to or beyond cutoff has no active repeat target. A timely action received after expiry retains its fact without reopening. At/after-cutoff actions are not offered. | ADR-0008 §4–6; API_CONTRACT T01/T02. The existing boundary is retained, not redefined by this proposal. |
| V2-E03 | Server rejects a locally recorded Snooze while original window remains open. Retain the local record and rejection; do not mark accepted/Taken. **Candidate:** refresh/re-evaluate current group, authorization, execution scope, time and final/help/takeover facts before restoring the original actionable prompt. Never add ten minutes from rejection/receipt or create a new occurrence. | **PENDING E + S1 + non-author Server review.** Rejection reason taxonomy, atomic refresh/rollback, deduplication, retry identity and exact UI transition remain unsettled. |
| V2-E04 | Rejection recovery sees cutoff, known Taken/Help, acknowledged takeover, revoked authorization, or obsolete execution scope. It must not blindly reopen the old prompt, even when a cached local window looked open. | Guard cases for V2-E03; same pending review. This is not a general policy to suppress already authorized offline operation merely because the network is unavailable. |

V2-E02 includes target deadline−1ms / deadline / deadline+1ms, action at cutoff,
late receipt and an ordinary delayed receipt. T+30min remains a caregiver follow-up threshold;
none of these vectors turns it into Elder expiry. Original group/order/evidence is retained.

## Decision worksheet before freezing the affected implementation

1. **C3 + S1 + non-author Server:** confirm R01/R02 current-projection shape, retained revision/source
   fields, current relationship authorization and how audit evidence is paged within existing budgets.
   The fixture is not permission to trust a caller-supplied actor or projection.
2. **E + S1 + non-author Server:** confirm E01 independent local/Server/delivery facts and how rejected,
   pending or unknown completion is represented internally. Keep technical sync management out of
   ordinary Elder Home/reminder UI; caregiver/diagnostics may display truthful freshness.
3. **E + S1 + non-author Server:** decide E03 rejection reasons and transitions, including transient
   transport failure versus terminal rejection, response loss, stale response and process death.
   Candidate copy: EN “Please choose again”; ZH “请重新选择”. Show the original photos/actions only
   after revalidation permits them. This is review copy, not shipped resources or accessibility evidence.
4. **S1 + Server:** confirm stable action identity on unknown-completion retry, no automatic new intent,
   immutable original receipt and no old receipt overwriting current facts. A user making a fresh
   choice after an explicitly rejected action is a separate reviewed transition, not a transport retry.

Record attributable conclusions at the final reviewed commit. Existing approvals on d50877e cover
the ownership correction; they do not automatically approve this packet or a production state machine.
Until then the affected adapters remain blocked on these decisions. Unrelated work may continue.

## Later implementation acceptance

The [source map and deterministic verification plan](V2_CONSISTENT_READ_PLAN.md) identifies the
existing read seams and remaining gaps. Its CR01–CR20 cases are planned implementation checks,
not additional passing tests or evidence that the read projection is already implemented.

After joint acceptance, run these exact inputs against the real authorized read projection and
Room/outbox/Server action paths, including reordered delivery, duplicate receipt, concurrent correction,
response loss and restart. E03/E04 additionally require actual prompt/cancellation recovery and
bilingual device/accessibility checks. Passing fixture/wire tests cannot replace those checks.
