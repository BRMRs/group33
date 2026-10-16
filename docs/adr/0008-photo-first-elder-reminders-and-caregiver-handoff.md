# ADR-0008: Adopt the photo-first elder reminder and caregiver follow-up baseline

## Status

**Accepted product decision — ELDER_REMINDER_V2**, explicitly confirmed by the project lead on 2026-10-16 in [Issue #23](https://github.com/BRMRs/group33/issues/23). This replaces the earlier proposal and is the sole current product behavior. It does not claim implemented features, named Android/Server protocol sign-off, or release acceptance.

Partially supersedes ADR-0002/0004 on Elder sync presentation and refresh-only sufficiency, ADR-0003/0005 on the response deadline and Elder-only outcome reporting, and Issues #4/#5/#7/#9/#11 on those affected requirements. Unaffected pairing, authorization, offline execution, generation, frozen historical context, stack and accessibility decisions remain in force. The old choices are not optional alternatives.

## Context

The target Elder can recognize familiar original boxes, gather medicines and take them independently, but should not perform complex reading, per-medicine reporting or synchronization management. The Caregiver configures plans and handles exceptions. Earlier prototypes confused this division and hid recognition content behind oversized actions.

## Decision

1. Home is quiet: brief state/next reminder and Photograph a box; no daily medicine list, technical sync state or generic Help.
2. Reminder recognition is photo-first. Six original-box photos use two columns/three rows and stable order, with necessary confirmed quantity/instructions attached. Names/strengths do not dominate. Photos do not prove identity, dose or ingestion.
3. Both single- and multi-medicine reminders expose exactly three group actions: **Medicines taken / Remind me in 10 minutes / I need help**. No per-drug selection, partial-intake form or mandatory second confirmation. Help is available in the reminder, not Home.
4. **The sole response interval is [plannedAt, plannedAt + 1_800_000ms).** At the exact thirty-minute boundary, absent a timely report, withdraw this occurrence's alarm, photos and old Taken/snooze actions and create Caregiver nonresponse follow-up. There is no remaining two-hour action window.
5. Snooze is exactly ten minutes from the local action time, never from receipt time, and never extends the original deadline. If its target reaches/passes the deadline, do not promise or shorten another reminder; deadline wins ties. Help starts follow-up immediately and stops repeated prompting for that occurrence.
6. After cutoff/help/takeover, the Elder does not register an outcome or reopen old reminders. At recovery, apply the same original cutoff before any catch-up notification. Timely offline reports retain their original time and may reconcile provisional nonresponse after receipt.
7. A Caregiver contact is **Contact made — record outcome**, not completion. The Caregiver records **Taken / Not taken / Unable to confirm**, with actor/source, actual time or explicit unknown, entry time and receipt time. Unknown remains actionable. Partial/ambiguous intake is not forced into all-taken/all-not-taken. No Elder return-to-phone step is required.
8. Caregiver entries are separate auditable facts, not forged Elder actions. Preserve earlier records and contradictory delayed reports; require explicit review rather than last-arrival overwrite. Elder report status and Caregiver follow-up outcome remain distinguishable. Missed means no timely Elder report known, not proven non-intake.
9. **Device-contact loss has its own short detection/alert path; it does not wait for the thirty-minute dose timer.** Alert the Caregiver to contact the Elder without guessing battery/network/power state. Reconnection does not close an unresolved medication follow-up; outcome entry does not prove device recovery.
10. Caregiver follow-up, supplement entry and independent contact-loss alerting are required current-version scope, not optional P1. Data sync may remain explicit Room–Server; refresh alone is not sufficient alert delivery. The two-minute simulation is not a production threshold or SLA.
11. Local offline operation and its cutoff continue from durable cached state. Remote takeover is not claimed applied until acknowledged by the Elder installation. Offline help is locally queued, not falsely delivered. The Caregiver sees truthful freshness, received evidence, delivery and acknowledgement distinctions.
12. Keep technical Pending sync / Synced / Sync needs attention out of ordinary Elder Home/reminder screens. Retain them in data/diagnostics and map Caregiver-known information truthfully; remote UI cannot know an unsent local action.
13. Use one shared product identity, tokens, components and bilingual resources. System font scaling including 200% remains an accessibility check, not an Elder setting task. Current C geometry is rejected; finish one shared reference component rather than more prototype variants.

Page/component rules are in [ELDER_UI_BASELINE.md](../ELDER_UI_BASELINE.md); the old UI document is an entry point to that single baseline, not another specification.

## What remains technical, not a product choice

[ELDER_UI_TECHNICAL_REVIEW.md](../ELDER_UI_TECHNICAL_REVIEW.md) lists V2-T01–V2-T06 with required reviewers, deliverables and tests: protocol/version migration, frozen group transactions, caregiver record/audit APIs, contact monitoring thresholds, alert delivery/recovery, and shared-layout acceptance.

These questions cannot restore a two-hour window, remove supplement entry, defer mandatory alerting to P1, add per-drug Elder reporting, or expose Elder sync management. Named cross-client review is still required before freezing affected DTOs/adapters. Unaffected work can continue.

## Migration and evidence boundary

Existing compiled r1 declarations, JSON examples and tests are **legacy structural/regression evidence, not V2 acceptance**. In particular WireContractTest currently asserts the retired duration. This docs-only change does not alter those files or imply V2 is implemented; #12 must migrate code/fixtures/tests together under V2-T01.

Do not rewrite accepted historical reports, generate competing Server Dose events, or silently relabel old policy evidence. New interactions use the V2 cutoff; old installed clients/retained records need an explicit compatibility/cutover plan before deployment. There must not be a second active legacy response window offered to the Elder.

At least Android S1 and Server S2 perspectives review the same protocol revision, with E/C2/C3 reviewing behavior and C1 reviewing shared resources/authorization. Actual members remain to be recorded; no signatures are fabricated. Existing implementation Issues remain open.

## Alternatives rejected and consequences

Text-first dashboards, per-box wizards/selection, missing snooze, Elder sync troubleshooting and treating contact as completion all transfer avoidable burden to the Elder. Three tall full-width actions also fail if they hide the photos. Larger targets alone do not establish accessibility.

Simpler Elder UI increases Caregiver, audit, monitoring and delivery obligations; those costs are accepted product scope, not hidden by the prototype. Thirty minutes is a workflow boundary, **not medical advice about taking/skipping/replacing a medicine**.

Only decision documents enter the integration PR. The [frozen prototype evidence](https://github.com/BRMRs/group33/tree/60b035a72255cf0c3f11d15f900438ca0809637c/android-app/prototypes/due-reminder-prototype) stays on its own branch and is not a final layout or production implementation.
