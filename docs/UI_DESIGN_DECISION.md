# UI design decision — current ELDER_REMINDER_V2 entry point

The sole current detailed UI specification is [Elder UI baseline](ELDER_UI_BASELINE.md), governed by [Accepted product ADR-0008](adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). The project lead explicitly selected the new version on 2026-10-16; earlier conflicting rules from Issue #7 and this document are superseded, not selectable alternatives.

The existing Android layouts and archived Web prototype are placeholders/evidence, not approved pixel references. This document remains the common entry point so old links do not lead developers to a second rule set.

## Required behavior

| Area | Current requirement |
| --- | --- |
| Elder Home | Quiet state/next reminder and Photograph a box; no daily medicine dashboard, sync indicators or generic Help |
| Reminder | Photo-first original-box overview; six boxes in two columns/three rows, stable order, attached necessary quantity/instructions |
| Elder actions | One-step Medicines taken / Remind me in 10 minutes / I need help; same group behavior for single/multiple medicines |
| Partial intake / missing box | No Elder per-drug/quantity selection; Help starts Caregiver follow-up |
| Response window | Exactly thirty minutes from the original planned instant, left-closed/right-open; exact cutoff removes alarm/photos/old actions and starts nonresponse follow-up |
| Snooze | Exactly ten minutes, original cutoff unchanged; no repeat at/after cutoff, no shortened delay |
| Caregiver contact | Contact made is still pending outcome entry; Caregiver records Taken / Not taken / Unable to confirm without asking the Elder to return to the app |
| Device-contact loss | Independent short detection/alert path, never waits for the ordinary thirty-minute timer; production threshold/transport needs technical review |
| Synchronization | No technical sync burden on Elder Home/reminder; Caregiver sees only known received/freshness/delivery/acknowledgement facts |
| Recovery | No stale reminder replay after cutoff/help/acknowledged takeover; timely offline evidence and conflicting records preserved |

There is no active two-hour response window or mandatory two-step Taken confirmation. Follow-up/entry/contact-loss alerting is current required scope, not an optional future enhancement.

## Shared identity and accessibility retained

- One calm, high-contrast product: white/cool-neutral surfaces, deep-blue primary, restrained elevation/moderate rounding, system fonts and Material Rounded icons. Roles differ in density/scale, not brand.
- Scheduled gray, Due blue, Snoozed purple, Taken green, Missed dark gray, warning amber, error red; text plus non-color cue. A report is not verified ingestion; unknown is not green success.
- Chinese roles are 长者 / 照护者; use Reminder plan / 用药提醒计划. Default locale follows device (Chinese → Simplified Chinese, otherwise English); persistent override offers system/English/简体中文.
- Elder body ≥18sp, supporting ≥16sp, key status ≥24sp; Elder targets ≥56×56dp, main action ≥64dp high; Caregiver ≥48×48dp. Critical actions include text.
- Normal-text contrast ≥4.5:1, large text ≥3:1; test TalkBack, grayscale, long text, system 200% font, 360dp portrait and 320dp compatibility. Font scaling is not an extra Elder choice.
- Dark mode, dynamic color, separate themes/brands, custom fonts, decorative avatars, tablets/landscape optimization and complex motion remain deferred.

## Reference component, not further prototype iteration

The current C photo area hides rows behind oversized actions and is not accepted. Default six-box use should target complete, recognizable same-screen photos without shrinking targets, clipping quantity or forcing per-drug checks. The remaining geometry/device check is jointly owned under #19; it does not authorize each feature to choose its own layout.

C1 centrally integrates tokens/styles/localized resources; E reviews the Elder accessibility profile; feature owners reuse common components and submit state needs rather than clone styles. Review requirements and PR checks are in the baseline.

## Technical review and implementation state

[Technical review queue](ELDER_UI_TECHNICAL_REVIEW.md) defines the remaining implementation decisions. Those do not reopen product behavior. Code, fixtures and actual device/notification evidence are not updated by this docs-only PR. Required Android/Server sign-off and the normal non-author PR review remain outstanding.
