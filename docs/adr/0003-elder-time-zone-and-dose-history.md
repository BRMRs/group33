# ADR-0003: Anchor reminder plans to the elder execution time zone and preserve dose history

## Status

Accepted for P0; follow-up timing and caregiver-authored entries supplemented by [ADR-0008](0008-photo-first-elder-reminders-and-caregiver-handoff.md). Current behavior below is ELDER_REMINDER_V2.

## Context

Caregiver and elder devices may use different time zones, and plans may be edited after dose events have already been generated. Reinterpreting old events after a time-zone or plan change would make the caregiver's history unreliable.

## Decision

Each reminder plan uses daily local times in an explicitly confirmed IANA Elder execution time zone, an inclusive start date, and an optional inclusive end date. A plan/date/time combination produces at most one dose event. Plan edits apply prospectively; history and an already Due or Snoozed event retain their original meaning. The response window remains two hours from the original planned time: [plannedAt, plannedAt + 7_200_000ms). At thirty minutes without a known medication confirmation, notify the Caregiver but keep Elder reminder/actions available; do not mark Missed. Only at the two-hour deadline withdraw the old reminder/photos/actions. Both thresholds remain anchored to the original time. `Remind later` requests the same event after a fixed ten minutes without extending that window; if the window ends first or at the same instant, the event expires without another reminder unless a timely Taken report is known. A timely Elder-reported `Taken` is an immutable report fact, while a locally recorded report made before the deadline may reconcile a provisional `Missed` result after delayed synchronization.

Caregiver contact requires a separate Taken / Not taken / Unable to confirm entry with actor/source and actual-time uncertainty. Append auditable corrections and flag conflicting delayed Elder reports; never impersonate Elder Taken or silently overwrite history. Help starts follow-up earlier and suppresses repeated prompting for that occurrence.

## Consequences

- A time-zone mismatch or unknown zone requires explicit caregiver confirmation with a converted example.
- The data model must retain planned time, report time, and synchronization time distinctly enough to explain outcomes.
- Complex recurrence, automatic travel-time-zone migration, and clock-tamper detection are outside P0.

## Technical clarification — 2026-10-16

[ADR-0005](0005-occurrence-generation-and-time-resolution.md) records approved T01/T02: Elder-only rolling 24-hour generation, all generated instances (including SCHEDULED) retaining original meaning, explicit generation evidence and P0_TIME_V1 gap/fold/deadline handling. This makes the existing immutable-generated-event rule explicit; it does not grant permission to rewrite future generated events.

The historical [Issue 4 decision](https://github.com/BRMRs/group33/issues/4#issuecomment-6106837203) retained the ten-minute delay and original-deadline rule; the 2026-10-17 ADR-0008 clarification retains the two-hour deadline and adds a separate thirty-minute notification. The earlier Glossary wording about an extra reminder inside the last ten minutes was inconsistent with that decision and has been corrected. Deadline takes precedence; the implementation must not shorten the delay or replay an expired reminder. The API contract defines the nominal target, nullable active snooze target and UI feedback. D03/O01 and V201 specify the current boundary; existing two-hour fixture assertions agree with duration but do not prove the thirty-minute notification or T−5/T+5 probes; add their vectors under V2-T01/T04.
