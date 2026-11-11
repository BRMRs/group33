# S1 durable alarm runtime — scope and API 35 evidence

Date: 2026-11-11  
Owner: BRMRs (S1)  
Branch: `feature/24-s1-alarm-runtime`  
Issues: [#18](https://github.com/BRMRs/group33/issues/18), [#24](https://github.com/BRMRs/group33/issues/24)

## Delivered S1 boundary

This slice turns a frozen, confirmed occurrence group into an Android alarm that remains valid without a running UI or network connection.

- Room atomically stores one reminder group and all same-instant Medication occurrences. Actions always apply to the whole group.
- The original planned instant, two-hour deadline, generation evidence, Medication text and execution zone are frozen. Snooze never moves the original deadline.
- AlarmManager schedules T−5 probe, T reminder, T+5 probe, T+30 follow-up and T+2h expiry. Exact access uses `setExactAndAllowWhileIdle`; denied access reports `DEGRADED` and uses an inexact fallback instead of claiming failure or Missed.
- A high-importance alarm notification uses the default alarm sound, `USAGE_ALARM`, vibration, a full-screen intent and exactly Taken / Snooze 10 / Help actions. API 23 explicitly carries sound on the notification; API 26+ carries it on the channel.
- Taken, Snooze and Help first commit one stable action ID and the group transition in the same Room transaction. Pending action and phase facts survive process death/reboot; WorkManager retries only when a real host dispatcher is installed and never treats a queued fact as Server acceptance.
- BOOT_COMPLETED, first unlock, package replacement, exact-access change, wall-clock and time-zone changes reload Room. Recovery expires old groups first, catches up an open group at most once per boot, and never reopens Taken, Help, caregiver-taken-over or expired groups.
- The reminder database and pending outbox are excluded from cloud backup/device transfer so installation-scoped actions cannot replay on a restored installation.

The E/C2 integration seam is `ReminderCoordinator.register(ReminderGroup)`. A `ReminderGroup` contains the real confirmed `OccurrenceGenerationEvidence` and Medication snapshot for every same-instant item. The authenticated application host supplies `ReminderActionDispatcher` and `ReminderPhaseDispatcher`; each receives durable rows rather than transient notification extras.

## Product rules covered

- Same planned instant is one group and one notification; there is no per-Medication action.
- T+30 produces a durable follow-up phase but does not close the Elder reminder.
- T+2h is the original fixed deadline. Deadline wins over a snooze target at or beyond it.
- Taken, Help and confirmed caregiver takeover end local prompting.
- Notification denial is `UNAVAILABLE`; exact-alarm denial is `DEGRADED`; neither changes the occurrence to Missed.
- Duplicate alarms, notification delivery claims and action IDs are idempotent. A failed notification claim is released so granting permission can recover the same still-open group.

## API 35 evidence

The project owner chose API 35-only permission/device acceptance for this PR on 2026-11-11; the previously proposed API 31/33 image runs were stopped. This does not rewrite the wider issue's historical device matrix.

Automated API 35 instrumentation:

- 9/9 Room and alarm device tests passed: atomic two-Medication registration, due/notification claims, fixed deadline, snooze second reminder, action outbox, event deduplication, exact five-second background alarm, alarm-channel sound/usage/importance, action termination and once-per-boot recovery.
- Notification permission denied phase passed, followed by a separate granted phase against the same stored occurrence. The denied occurrence remained `RINGING` without a notification; granting permission recovered it.
- Exact-alarm access denied phase passed after the system restarted the app: capability was `DEGRADED`, registration used the inexact AlarmManager fallback, and the group was not marked Missed.
- Process-death probe passed with no app/UI launch between kill and due. Before the verifier started, `dumpsys notification` already contained the expected group tag, importance 4, three actions, `mSoundNotificationKey` and `mVibrateNotificationKey`.
- Real emulator reboot probe passed with no app/UI launch between reboot and due. Before the verifier started, the same system evidence already existed; the later verifier confirmed the persisted group was `RINGING` at/after its original planned instant and could be atomically Taken.

Deterministic policy tests: `S1PolicyTest` 33/33 and `CommandRecoveryPolicyTest` 5/5 passed. They cover T/T+30/T+2h boundaries, ten-minute snooze, both probe windows and their 60-second response waits, late/duplicate facts, retry identity, recovery and unknown-delivery behavior.

Repository checks:

```text
python3 scripts/check_repository_policy.py                 PASS
./gradlew test                                             PASS
./gradlew :android-app:lintDebug                           PASS
./gradlew :android-app:assembleDebug                       PASS
```

No real person, Medication or health data was used; fixtures are explicitly fictional.

## Deliberately not claimed by S1

- E still owns the long-form Elder ringing page, understandable permission explanation, settings-return experience, large-text/320dp UI evidence and binding the confirmed-plan cache to `ReminderGroup` registration.
- C3/S2 still own caregiver capability/follow-up presentation and the production authenticated endpoints. A locally pending WorkManager item is not claimed as visible to the caregiver.
- Force stop, pre-first-unlock execution, uninstall/clear-data and vendor-specific background restrictions remain the accepted platform limitations in #18.
- This PR does not close #18 or #24. Production dispatcher wiring, E UI integration, non-author review and merge remain required.
