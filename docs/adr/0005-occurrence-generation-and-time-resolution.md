# ADR-0005: Use Elder-only occurrence generation with frozen time resolution

## Status

Accepted by the project lead on 2026-10-16, baseline `T01-T07/2026-10-16-r1`, T01/T02. Recorded in [Issue 12](https://github.com/BRMRs/group33/issues/12) and the [technical decision record](../contracts/TECHNICAL_RECOMMENDATIONS.md). Supplements ADR-0002/0003; preserves unrelated product behavior. Response duration is superseded by [ADR-0008](0008-photo-first-elder-reminders-and-caregiver-handoff.md); current duration below is ELDER_REMINDER_V2.

## Context and trade-off

An offline Elder can generate an event from a previously confirmed revision while the Caregiver has saved a newer revision. Independent Server generation or first-network-arrival arbitration could change the same occurrence's historical meaning. Generating far into the future also freezes too many instances before a later edit.

## Decision

The P0 Elder execution installation is the sole generator of real Dose events. It rolls a next-24-hour generation window forward locally, including offline and after reboot. Server-generated or Android-computed seven-day previews are not real events and cannot claim canonical event identity. Room atomically retains generated events, frozen evidence and registration work; Server validates/accepts registrations and can atomically register with an action. Occurrence uniqueness remains `(planId, localDate, localTime)`, excluding revision/offset.

Every real generated event, including SCHEDULED, keeps its revision/text/nominal context/instant through edits and deactivation. Only ungenerated parts adopt a later synchronized plan. Retained old revision alone is never rejection; contradictory evidence preserves the local report for Needs attention instead of overwriting history. Generation timestamps are trusted P0 application evidence, not tamper-proof proof.

The existing P0_TIME_V1 DST resolution algorithm shifts nonexistent local time by the full DST gap, chooses the earlier instant once in a fold, and freezes the offset/instant. The response window is `[plannedAt, plannedAt + 1_800_000ms)`. This is the sole thirty-minute response interval; remove old reminder/actions at cutoff, not just a label change. Version/cutover of compiled r1 evidence is V2-T01 and must not silently reinterpret old records. The exact deadline is late; timely offline Taken remains reconcilable. Android API 23/35 and Server must pass the same vectors; different TZDB versions cannot silently move a frozen event.

Server contact monitoring and Caregiver follow-up/entries are permitted without making Server an independent real-event generator. Expected-plan alerts remain distinct from canonical event identity; association/audit is V2-T03.

## Consequences

- Registration/evidence, durable recovery and retained revision storage add protocol and test work.
- A reminder already generated for tomorrow can retain its old meaning after tonight's edit; UI must explain saved/not-delivered/retained-instance distinctions.
- 24 hours is not an offline expiry. Background local replenishment and recovery must continue beyond it.
- Concrete algorithms and production tests need six-role review and Android/Server sign-off. No implementation is delivered by this ADR.
