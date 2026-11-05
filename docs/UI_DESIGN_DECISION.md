# UI design decision — current ELDER_REMINDER_V2 entry point

The sole current detailed UI specification is [Elder UI baseline](ELDER_UI_BASELINE.md), governed by [Accepted product ADR-0008](adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). The project lead selected the new version on 2026-10-16 and clarified timing on **2026-10-17**; earlier conflicting rules from Issue #7 and this document are superseded, not selectable alternatives.

For role-scoped implementation use [onboarding](AGENT_START_HERE.md) and [technical coordination #24](https://github.com/BRMRs/group33/issues/24); this docs PR does not implement the UI.

The existing Android layouts and archived Web prototype are placeholders/evidence, not approved pixel references. This document remains the common entry point so old links do not lead developers to a second rule set.

## Required behavior

| Area | Current requirement |
| --- | --- |
| Elder Home | Quiet state/next reminder and Photograph a box; no daily medicine dashboard, sync indicators or generic Help |
| Reminder | Photo-first original-box overview; six boxes in two columns/three rows, stable order, attached necessary quantity/instructions |
| Elder actions | One-step Medicines taken / Remind me in 10 minutes / I need help; same group behavior for single/multiple medicines |
| Partial intake / missing box | No Elder per-drug/quantity selection; Help starts Caregiver follow-up |
| Elder response window | Two hours from original planned time T, [T, T+7_200_000ms); only at this cutoff remove old alarm/photos/actions |
| Nonresponse notification | T+30 minutes without known medication confirmation notifies Caregiver; Elder actions remain available until the two-hour cutoff unless Taken/Help/acknowledged takeover ends prompting earlier |
| Snooze | Exactly ten minutes, original cutoff unchanged; no repeat at/after cutoff, no shortened delay |
| Caregiver contact | Contact made is still pending outcome entry; Caregiver records Taken / Not taken / Unable to confirm without asking the Elder to return to the app |
| Connection check | First at T−5 minutes, recheck at T+5 minutes; notify only if both have no valid response after their waits. No all-day heartbeat or real-time loss monitoring; transport/wait/race handling needs review |
| Synchronization | No technical sync burden on Elder Home/reminder; Caregiver sees only known received/freshness/delivery/acknowledgement facts |
| Recovery | No stale reminder replay after cutoff/help/acknowledged takeover; timely offline evidence and conflicting records preserved |

The thirty-minute notification is not expiry or Missed; the Elder response window remains two hours. Mandatory two-step Taken confirmation is removed. Follow-up/entry/contact-loss alerting is current required scope, not an optional future enhancement.

## Shared identity and accessibility retained

- One calm, high-contrast product: white/cool-neutral surfaces, deep-blue primary, restrained elevation/moderate rounding, system fonts and Material Rounded icons. Roles differ in density/scale, not brand.
- Scheduled gray, Due blue, Snoozed purple, Taken green, Missed dark gray, warning amber, error red; text plus non-color cue. A report is not verified ingestion; unknown is not green success.
- Chinese roles are 长者 / 照护者; use Reminder plan / 用药提醒计划. Default locale follows device (Chinese → Simplified Chinese, otherwise English); persistent override offers system/English/简体中文.
- Elder body ≥18sp, supporting ≥16sp, key status ≥24sp; Caregiver body ≥16sp, supporting ≥14sp. Elder targets ≥56×56dp, main action ≥64dp high; Caregiver ≥48×48dp. Critical actions include text.
- Normal-text contrast ≥4.5:1, large text ≥3:1; test TalkBack, grayscale, long text, system 200% font, 360dp portrait and 320dp compatibility. Font scaling is not an extra Elder choice.
- Dark mode, dynamic color, separate themes/brands, custom fonts, decorative avatars, tablets/landscape optimization and complex motion remain deferred.

## Reference component, not further prototype iteration

The current C photo area hides rows behind oversized actions and is not accepted. Default six-box use should target complete, recognizable same-screen photos without shrinking targets, clipping quantity or forcing per-drug checks. The remaining geometry/device check is jointly owned under #19; it does not authorize each feature to choose its own layout.

C1 centrally integrates tokens/styles/localized resources; E reviews the Elder accessibility profile; feature owners reuse common components and submit state needs rather than clone styles. Review requirements and PR checks are in the baseline.

## Technical review and implementation state

[Technical review queue](ELDER_UI_TECHNICAL_REVIEW.md) defines the remaining implementation decisions. Those do not reopen product behavior. Code, fixtures and actual device/notification evidence are not updated by this docs-only PR. Required Android/Server sign-off and the normal non-author PR review remain outstanding.
