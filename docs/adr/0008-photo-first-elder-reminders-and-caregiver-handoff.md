# ADR-0008: Photo-first grouped elder reminders with caregiver exception handling

## Status

Proposed — 2026-10-16. Product intent confirmed by the project lead in the prototype discussion and recorded in [Issue #23](https://github.com/BRMRs/group33/issues/23). Cross-client contract amendments and delivery scope are **not accepted** by this record. No named Android/Server approval, implementation or production delivery evidence is claimed.

This ADR deliberately distinguishes confirmed product intent from protocol acceptance. Until the affected amendments are reviewed, the existing accepted ADRs/contracts retain precedence and affected implementation is gated, rather than each developer choosing a different rule. Unaffected work continues.

## Context

Repeated prototypes optimized large controls and exposed domain detail while leaving elders to identify medicines from names, report partial intake, inspect synchronization and navigate hidden photos. This shifted care-management work onto the least suitable participant. The preferred direction is C's recognizable-box overview, supporting gathering several boxes before taking them together, with caregiver intervention for uncertainty.

The current C v2 is not an approved pixel reference: its action area leaves only one complete row visible. A runnable state simulation and absence of clipping do not establish older-adult usability or reliable remote alerting.

## Product decision to carry into implementation review

1. Keep Home quiet with a photo-capture entry, not a daily medicine dashboard or generic Help button.
2. Use actual-box photos as the reminder's main recognition aid. Six boxes use two columns/three rows in stable order. Necessary quantity/instructions stay attached to each photo; box images do not prove identity, dose or ingestion.
3. Offer one-step grouped Medicines taken, a fixed ten-minute Remind later, and I need help. Remove per-medicine selection/partial-intake forms and mandatory second confirmation. Snoozing is autonomous ordinary behavior, not a help request.
4. End the elder reminder UI thirty minutes from its original planned time if still unconfirmed. Remove stale alarm content and actions and do not replay after recovery. Snoozing never extends that cutoff. Help stops repeated prompting for this occurrence and starts caregiver follow-up.
5. Caregiver contact alone does not complete follow-up. Record Taken / Not taken / Unable to confirm with actor/source, actual time or explicit unknown, registration time and receipt time. Preserve history and unresolved uncertainty; do not fabricate an Elder report.
6. Monitor device contact separately from dose nonresponse. An independent, reviewed detection and notification path is required. No message cannot distinguish power loss, network loss, background limitations or a service fault. The prototype's two minutes is illustrative, not an SLA.
7. Keep technical synchronization work off ordinary elder screens. Caregivers receive truthful freshness, acceptance and device-acknowledgement information. Locally queued does not mean received, delivered or read.
8. Share product tokens, localized labels and reusable components across roles. Retain system font scaling/accessibility support; a reviewer font control is not a new elder task.

The complete page/component baseline and prohibited patterns are in [ELDER_UI_BASELINE.md](../ELDER_UI_BASELINE.md). Current geometry is explicitly rejected; a finite reference-component check remains, not another open-ended prototype round.

## Required amendments before acceptance

| Gate | Existing source / conflict | Required resolution and reviewers |
| --- | --- | --- |
| G1 — UI presentation | Issue #7 and `docs/UI_DESIGN_DECISION.md`: text-first, two-step Taken, elder sync presentation | C1/E review; update the old UI document and append the supersession to #7. Preserve unrelated visual/accessibility rules |
| G2 — time semantics | ADR-0003/0005 and P0_TIME_V1 use a two-hour Response window | C2/E/S1/S2 choose whether thirty-minute UI withdrawal is a separate timer or the canonical window changes. Update glossary, API, fixtures and exact-boundary tests together; never simply map withdrawal to Missed |
| G3 — grouped action | Existing plans/events are per medication; no frozen group transaction | E/C2/C3/S1/S2 define exact membership, common planned time, generation evidence, identity/idempotency, atomic acceptance or explicit partial-result handling, and immutable historical context. Group only plans confirmed to belong to this reminder; do not infer medical compatibility |
| G4 — help/takeover/supplement | Existing Elder-only timely Taken is final | E/C3/C1/S1/S2 define authorized caregiver append-only entries, unknowns, supersession/audit, contact vs closure, acknowledgement, retries and contradictory delayed Elder reports. Do not spoof Elder identity or silently replace accepted records |
| G5 — device and remote alerts | ADR-0002/0004 permit explicit sync without realtime push; remote missed-dose notification is P1 in the scope document | Lead + C3/S1/S2 select milestone, authenticated contact evidence, monitoring/delivery mechanisms, detection and notification budgets, caregiver-offline behavior, retry/escalation/dedup/recovery. No background worker or freshness timestamp alone proves prompt delivery |
| G6 — finite UI verification | C v2 hides boxes; placeholder public photos are not elders' own medicines | C1/E lock one shared reference component and tokens, verify on target device with approved photo fixtures and both languages; document unresolved real-user recognition/accidental-tap risk |

G1/G6 can prepare reference resources without inventing G2–G5 fields. Fake adapters must use reviewed interfaces. These gates do not authorize expanding all P0 work silently.

## Safety and integrity invariants

- Thirty minutes is a product handoff, not a recommendation to take, skip or replace a medicine.
- Stop locally at cutoff using retained state even while offline. A remote caregiver cannot force an offline phone to stop before receiving an instruction. Display pending acknowledgement truthfully; never replay an expired or taken reminder after recovery.
- Neither lack of action nor lack of device contact proves non-intake. Server expected-plan monitoring must not generate canonical Dose events contrary to ADR-0005.
- Keep planned time, elder action time, actual intake time (possibly unknown), caregiver entry time and Server receipt time distinct. Do not infer actual intake from entry time.
- Preserve timely offline reports and conflicting caregiver reports with original sources. A UI outcome label is not permission to change canonical Taken/Missed meanings.
- Authorization comes from verified active relationships, not a selected actor field. Photos stay private; demonstration data remains fictional even when public real-box images are used as fixtures.

## Alternatives rejected

- Text-first dashboards and daily lists: reading and management load on Home.
- Per-box wizard, checkbox or moved-card reporting: many decisions, memory and partial-intake bookkeeping.
- Removing Remind later: incorrectly treats being busy as requiring help.
- Showing all sync states on the elder path: technical burden without improving the elder's immediate task.
- Three tall full-width buttons by default: touch size alone does not justify hiding the recognition target.
- Treating contact as completion, or marking every partial/unknown case as all Taken/all Not taken: loses uncertainty and accountability.
- Promoting prototype CSS/JavaScript directly: browser simulations do not implement durable Android reminders, authorization or notifications.

## Consequences and rollout

The elder path becomes simpler, while caregiver workflow and system reliability become more demanding. UI simplicity does not remove backend/outbox, monitoring, auditing or notification work. Scope must be budgeted explicitly.

Accept this ADR only after named reviewers record the same reviewed commit and G1–G5 decisions, update conflicting accepted sources and affected Issue acceptance criteria, and identify required tests. G6 must have a bounded acceptance owner and evidence plan; passing real-device/user tests is still an implementation/release gate, not assumed by accepting the decision. Existing #12/#13/#14/#17/#18/#19/#21 remain open; this document does not waive their tests or review requirements.

Publish conclusions separately from the [archived prototype](https://github.com/BRMRs/group33/tree/60b035a72255cf0c3f11d15f900438ca0809637c/android-app/prototypes/due-reminder-prototype). Only decision documents belong in the eventual main-branch integration. Do not merge the evidence branch or use its README as current requirements.
