# ADR-0008: Adopt the photo-first elder reminder and caregiver follow-up baseline

## Status

**Accepted product decision — ELDER_REMINDER_V2**, initially confirmed on 2026-10-16, with the project lead's **2026-10-17 timing clarification controlling all current rules** in [Issue #23](https://github.com/BRMRs/group33/issues/23). This replaces both the earlier proposal and its mistaken thirty-minute withdrawal/continuous-contact-monitoring requirements. Current behavior is two-hour Elder withdrawal, a separate thirty-minute Caregiver notification, and two scheduled connection checks. It does not claim implemented features, named Android/Server protocol sign-off, or release acceptance.

Partially supersedes ADR-0002/0004 on Elder sync presentation and refresh-only sufficiency, ADR-0003/0005 on separate nonresponse follow-up and caregiver outcome reporting; their original two-hour response duration is retained, and Issues #4/#5/#7/#9/#11 on those affected requirements. Unaffected pairing, authorization, offline execution, generation, frozen historical context, stack and accessibility decisions remain in force. The old choices are not optional alternatives.

## Context

The target Elder can recognize familiar original boxes, gather medicines and take them independently, but should not perform complex reading, per-medicine reporting or synchronization management. The Caregiver configures plans and handles exceptions. Earlier prototypes confused this division and hid recognition content behind oversized actions.

## Decision

1. Home is quiet: brief state/next reminder and Photograph a box; no daily medicine list, technical sync state or generic Help.
2. Reminder recognition is photo-first. Six original-box photos use two columns/three rows and stable order, with necessary confirmed quantity/instructions attached. Names/strengths do not dominate. Photos do not prove identity, dose or ingestion.
3. Both single- and multi-medicine reminders expose exactly three group actions: **Medicines taken / Remind me in 10 minutes / I need help**. No per-drug selection, partial-intake form or mandatory second confirmation. Help is available in the reminder, not Home.
4. **The Elder response interval is [T, T + 7_200_000ms), where T is the original planned time.** At T+30 minutes, if no medication confirmation is known, notify the Caregiver and create/update follow-up, but keep the Elder reminder and its actions available. At the two-hour cutoff remove old alarm/photos/Taken/snooze surfaces. Thirty minutes is neither expiry nor Missed. Snooze does not reset either threshold.
5. Snooze is exactly ten minutes from the local action time, never from receipt time, and never extends the original deadline. If its target reaches/passes the deadline, do not promise or shorten another reminder; deadline wins ties. Help starts follow-up immediately and stops repeated prompting for that occurrence.
6. After cutoff/help/takeover, the Elder does not register an outcome or reopen old reminders. At recovery, apply the same original cutoff before any catch-up notification. Timely offline reports retain their original time and may reconcile provisional nonresponse after receipt.
7. A Caregiver contact is **Contact made — record outcome**, not completion. The Caregiver records **Taken / Not taken / Unable to confirm**, with actor/source, actual time or explicit unknown, entry time and receipt time. Unknown remains actionable. Partial/ambiguous intake is not forced into all-taken/all-not-taken. No Elder return-to-phone step is required.
8. Caregiver entries are separate auditable facts, not forged Elder actions. Preserve earlier records and contradictory delayed reports; require explicit review rather than last-arrival overwrite. Elder report status and Caregiver follow-up outcome remain distinguishable. Missed means no timely Elder report known, not proven non-intake.
9. **Connection checks are scheduled per reminder: first at T−5 minutes, recheck at T+5 minutes.** Here “after five minutes” means five minutes after the original reminder, not five minutes after the first probe. Only if both attempts have no valid response after their bounded response waits, notify the Caregiver of a connection problem. Any valid response in the round prevents the two-failure alert; cached sync/old report replay is not a probe response. There is no all-day heartbeat, real-time loss detection or cause diagnosis requirement. Reconnection does not close unresolved medication follow-up.
10. Caregiver follow-up, supplement entry and the scheduled two-failure connection alert are required current-version scope, not optional P1. Data sync may remain explicit Room–Server; refresh alone does not replace triggered notifications. Prior demo two-minute detection and discussion proposals for continuous monitoring, supported-device surveillance, SMS or fixed delivery budgets are not approved requirements.
11. Local offline operation and its cutoff continue from durable cached state. Remote takeover is not claimed applied until acknowledged by the Elder installation. Offline help is locally queued, not falsely delivered. The Caregiver sees truthful freshness, received evidence, delivery and acknowledgement distinctions.
12. Keep technical Pending sync / Synced / Sync needs attention out of ordinary Elder Home/reminder screens. Retain them in data/diagnostics and map Caregiver-known information truthfully; remote UI cannot know an unsent local action.
13. Use one shared product identity, tokens, components and bilingual resources. System font scaling including 200% remains an accessibility check, not an Elder setting task. Current C geometry is rejected; finish one shared reference component rather than more prototype variants.

Page/component rules are in [ELDER_UI_BASELINE.md](../ELDER_UI_BASELINE.md); the old UI document is an entry point to that single baseline, not another specification.

## Authoritative timing example

For an 18:00 reminder: 17:55 first connection check; 18:05 recheck; after the second response wait, notify only when both attempts have no valid response. At 18:30 without known medication confirmation, notify/update Caregiver follow-up but do not withdraw Elder actions. At 20:00 apply the two-hour expiry. Help, a recorded Taken or acknowledged Caregiver takeover can end prompting earlier. Probes are once per reminder group, not per box. Response wait length, late-reply races and trigger/recovery transport remain technical review items, not permission to change these times.

Repeated causes must update/deduplicate follow-up and notifications; a thirty-minute timer must not create a second identical unanswered case after Help or known Caregiver resolution. Unknown Server receipt is not proof the Elder did not take medicine.

## What remains technical, not a product choice

[ELDER_UI_TECHNICAL_REVIEW.md](../ELDER_UI_TECHNICAL_REVIEW.md) lists V2-T01–V2-T06 with required reviewers, deliverables and tests: protocol/version migration, frozen group transactions, caregiver record/audit APIs, the scheduled probes' transport/response waits and races, alert delivery/recovery, and shared-layout acceptance.

These questions cannot turn the thirty-minute notification into Elder expiry, alter the two-hour cutoff or T−5/T+5 check times, remove supplement entry, add all-day surveillance, defer required triggered alerts to P1, add per-drug Elder reporting, or expose Elder sync management. Named cross-client review is still required before freezing affected DTOs/adapters. Unaffected work can continue.

## Migration and evidence boundary

Existing compiled r1 declarations, JSON examples and tests are **legacy structural/regression evidence, not V2 acceptance**. The r1 two-hour field/assertion now agrees with the clarified response duration; it does not prove the separate thirty-minute alert, scheduled probes, grouped actions or caregiver closure. This docs-only change does not alter those files or imply V2 is implemented; #12 must migrate code/fixtures/tests together under V2-T01.

Do not rewrite accepted historical reports, generate competing Server Dose events, or silently relabel old policy evidence. New interactions use the V2 cutoff; old installed clients/retained records need an explicit compatibility/cutover plan before deployment. Do not confuse the thirty-minute follow-up threshold with the two-hour response deadline.

At least Android S1 and Server S2 perspectives review the same protocol revision, with E/C2/C3 reviewing behavior and C1 reviewing shared resources/authorization. Actual members remain to be recorded; no signatures are fabricated. Existing implementation Issues remain open.

## Alternatives rejected and consequences

Text-first dashboards, per-box wizards/selection, missing snooze, Elder sync troubleshooting and treating contact as completion all transfer avoidable burden to the Elder. Three tall full-width actions also fail if they hide the photos. Larger targets alone do not establish accessibility.

Simpler Elder UI increases Caregiver, audit, monitoring and delivery obligations; those costs are accepted product scope, not hidden by the prototype. Both time thresholds are workflow boundaries, **not medical advice about taking/skipping/replacing a medicine**.

Only decision documents enter the integration PR. The [frozen prototype evidence](https://github.com/BRMRs/group33/tree/60b035a72255cf0c3f11d15f900438ca0809637c/android-app/prototypes/due-reminder-prototype) stays on its own branch and is not a final layout or production implementation.
