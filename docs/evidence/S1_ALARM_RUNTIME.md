# S1 durable alarm runtime — scope and API 35 evidence

Date: 2026-11-11  
Owner: BRMRs (S1)  
Branch: `feature/24-s1-alarm-runtime`  
Issues: [#18](https://github.com/BRMRs/group33/issues/18), [#24](https://github.com/BRMRs/group33/issues/24)
Draft implementation: [PR #119](https://github.com/BRMRs/group33/pull/119), stacked on [PR #116](https://github.com/BRMRs/group33/pull/116)

## Delivered S1 boundary

This slice turns a frozen, confirmed occurrence group into an Android alarm that remains valid without a running UI or network connection.

- Room atomically stores one reminder group and all same-instant Medication occurrences. Actions always apply to the whole group.
- The original planned instant, two-hour deadline, generation evidence, Medication text and execution zone are frozen. Snooze never moves the original deadline.
- AlarmManager schedules T−5 probe, T reminder, T+5 probe, T+30 follow-up and T+2h expiry. Exact access uses `setExactAndAllowWhileIdle`; denied access reports `DEGRADED` and uses an inexact fallback instead of claiming failure or Missed.
- A high-importance alarm notification uses the default alarm sound, `USAGE_ALARM`, vibration, a full-screen intent and exactly Taken / Snooze 10 / Help actions. API 23 explicitly carries sound on the notification; API 26+ carries it on the channel.
- Taken, Snooze and Help first commit one stable action ID and the group transition in the same Room transaction. Pending action and phase facts survive process death/reboot; WorkManager retries only when a real host dispatcher is installed and never treats a queued fact as Server acceptance.
- BOOT_COMPLETED, first unlock, package replacement, exact-access change, wall-clock and time-zone changes reload Room. Recovery expires old groups first, catches up an open group at most once per boot, and never reopens Taken, Help, caregiver-taken-over or expired groups.
- The reminder database and pending outbox are excluded from cloud backup/device transfer so installation-scoped actions cannot replay on a restored installation.

The E/C2 integration seam is `ReminderCoordinator.register(ReminderGroup)`. A `ReminderGroup` contains the real confirmed `OccurrenceGenerationEvidence` and Medication snapshot for every same-instant item. This branch is stacked on PR #116 and binds its confirmed-plan cache to the S1 runtime; the old debug-only alarm path is removed so only one component can ring. The authenticated application host supplies `ReminderActionDispatcher` and `ReminderPhaseDispatcher`; each receives durable rows rather than transient notification extras.

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

- 10/10 Room and alarm device tests passed: atomic two-Medication registration, execution-scope isolation, due/notification claims, fixed deadline, snooze second reminder, action outbox, event deduplication, exact five-second background alarm, alarm-channel sound/usage/importance, action termination and once-per-boot recovery.
- Notification permission denied phase passed, followed by a separate granted phase against the same stored occurrence. The denied occurrence remained `RINGING` without a notification; granting permission recovered it.
- Exact-alarm access denied phase passed after the system restarted the app: capability was `DEGRADED`, registration used the inexact AlarmManager fallback, and the group was not marked Missed.
- Process-death probe passed with no app/UI launch between kill and due. Before the verifier started, `dumpsys notification` already contained the expected group tag, importance 4, three actions, `mSoundNotificationKey` and `mVibrateNotificationKey`.
- Real Android 15 emulator reboot/first-unlock probes passed with no app/UI launch between reboot and due. The scheduled probe restored the future reminder; a separate production-duration Snooze probe rang after the original ten-minute target (about 5.5 seconds late and never early), without restarting the delay from reboot/unlock. Before either verifier started, system evidence already showed importance 4, three actions, alarm sound, vibration and `USAGE_ALARM`. The Snooze verifier also proved the original two-hour deadline was unchanged and sentinel Taken/expired groups remained terminal with no notification; the recovered group could then be atomically Taken.
- The clean two-emulator Android 15 chain passed end to end: caregiver confirmed a real plan, Elder AlarmManager reached the due instant and rang, the Elder page exposed the grouped action, Taken committed locally, survived a process restart, uploaded with the original stable action ID, and appeared as accepted Taken on the caregiver page. Pairing revocation/denial phases also passed.
- A separate clean 19/19-phase Android 15 chain passed with `--offline-action-recovery`: after the real due alarm, the Elder installation disabled Android networking before Taken. The UI acknowledged only the local commit, the S1 Room outbox retained the original action as `PENDING`, and the instrumentation process ended. After host-restored connectivity and a new process, recovery uploaded that same action ID exactly once (`dispatchAttempts = 1`, `ACKNOWLEDGED`); the caregiver then loaded the server-accepted Taken time. No local success was relabelled as server acceptance.

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

- PR #116 supplies the current Elder dose panel and Taken/Snooze controls, and this branch now supplies its real-plan/runtime binding. E still owns the designed full ringing-page treatment, understandable permission explanation, settings-return and degraded/unavailable experience, Help interaction, and large-text/320dp UI evidence.
- C3/S2 still own caregiver capability/follow-up presentation and the production probe/follow-up/Help endpoints. Taken/Snooze upload is verified in the local real HTTP chain; Help currently ends local prompting but is deliberately marked permanent-failure by the host adapter because no server contract exists yet.
- Force stop, pre-first-unlock execution, uninstall/clear-data and vendor-specific background restrictions remain the accepted platform limitations in #18.
- This PR does not close #18 or #24. Production dispatcher wiring, E UI integration, non-author review and merge remain required.
