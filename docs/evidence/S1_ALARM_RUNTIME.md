# S1 durable alarm runtime — scope and API 35 evidence

Date: 2026-11-17
Owner: BRMRs (S1)
Branch: `feature/24-s1-alarm-runtime`
Issues: [#18](https://github.com/BRMRs/group33/issues/18), [#24](https://github.com/BRMRs/group33/issues/24)
Implementation: [PR #119](https://github.com/BRMRs/group33/pull/119), incorporating the reviewed boundary corrections from [PR #121](https://github.com/BRMRs/group33/pull/121) and stacked on [PR #116](https://github.com/BRMRs/group33/pull/116)

## Delivered S1 boundary

This slice turns a frozen, confirmed occurrence group into an Android alarm that remains valid without a running UI or network connection.

- Room atomically stores one reminder group and all same-instant Medication occurrences. Actions always apply to the whole group.
- The original planned instant, two-hour deadline, generation evidence, Medication text and execution zone are frozen. Snooze never moves the original deadline.
- AlarmManager schedules T−5 probe, T reminder, T+5 probe, T+30 follow-up and T+2h expiry. Exact access uses `setExactAndAllowWhileIdle`; denied access reports `DEGRADED` and uses an inexact fallback instead of claiming failure or Missed.
- An Android 15-compatible `mediaPlayback` foreground service is the only audible source. It loops the device alarm tone with `AudioAttributes.USAGE_ALARM`, holds a playback wake lock and uses an alarm-stream tone fallback only when a vendor exposes no readable alarm URI. The high-importance notification channel is deliberately silent, so the service and notification cannot double-play the same reminder.
- The foreground notification and player share one fixed identity. Repeated AlarmManager delivery, notification refresh and process recovery update that same instance instead of posting or playing a second reminder. Taken / Snooze 10 / Help remain stable PendingIntent actions.
- Taken, Snooze and Help first commit one stable action ID and the group transition in the same Room transaction. Pending action and phase facts survive process death/reboot; WorkManager retries only when a real host dispatcher is installed and never treats a queued fact as Server acceptance.
- Taken, Help, a valid full ten-minute Snooze, confirmed caregiver takeover and the original deadline stop playback immediately after the durable local transition; they do not wait for Server delivery.
- BOOT_COMPLETED, first unlock, package replacement, exact-access change, wall-clock and time-zone changes reload Room. Android 15 recovery goes through an exact-alarm broadcast because a boot receiver may not directly start a media-playback foreground service. Cold-process audio recovery selects only rows already persisted as `RINGING`; it never promotes a stale `SCHEDULED` or `SNOOZED` row. Full alarm/reboot recovery may separately catch up a genuinely due row through AlarmManager. Taken, Help, caregiver-taken-over and expired groups never reopen.
- The reminder database and pending outbox are excluded from cloud backup/device transfer so installation-scoped actions cannot replay on a restored installation.

The E/C2 integration seam is `ReminderCoordinator.register(ReminderGroup)`. A `ReminderGroup` contains the real confirmed `OccurrenceGenerationEvidence` and Medication snapshot for every same-instant item. This branch is stacked on PR #116 and binds its confirmed-plan cache to the S1 runtime; the old debug-only alarm path is removed so only one component can ring. The authenticated application host supplies `ReminderActionDispatcher` and `ReminderPhaseDispatcher`; each receives durable rows rather than transient notification extras.

## Product rules covered

- Same planned instant is one group and one notification; there is no per-Medication action.
- T+30 produces a durable follow-up phase but does not close the Elder reminder.
- T+2h is the original fixed deadline. Deadline wins over a snooze target at or beyond it.
- Taken, Help and confirmed caregiver takeover end local prompting.
- Notification denial is `UNAVAILABLE`; exact-alarm denial is `DEGRADED`; neither changes the occurrence to Missed.
- Duplicate alarms, notification delivery claims, foreground-service starts and action IDs are idempotent. A consumed same-boot notification claim does not prevent a killed process from restoring audio for the same still-`RINGING` row.

## API 35 evidence

The project owner chose API 35-only permission/device acceptance for this PR on 2026-11-11; the previously proposed API 31/33 image runs were stopped. This does not rewrite the wider issue's historical device matrix.

Automated API 35 instrumentation:

- The new foreground-service suite passed 5/5 targeted alarm/runtime cases on Android 15: exact background due delivery; one silent notification plus one looping `USAGE_ALARM` player; idempotent repeated starts; Taken, Help, valid Snooze, takeover and expiry stop; and sticky/cold recovery selecting only durable `RINGING` rows.
- The same 5/5 suite passed on the connected Xiaomi Mi Pad 4 Plus physical device (Android 8.1/API 27). Its alarm stream was initially zero, which explained the earlier property-only run producing no audible output; acceptance used 7/15 without application-side volume mutation.
- Separate process-death probes passed on Android 15 and the physical Xiaomi device. The instrumentation process ended before due; AlarmManager created `RINGING`, the foreground service began playback in a new process, and the verifier atomically recorded Taken.
- The `human-audible` gate looped the alarm for ten seconds and then stopped on each target. S1 owner Rui Zheng explicitly confirmed hearing both the Xiaomi physical device and Android 15 output on 2026-11-17. This manual result is required evidence and is not inferred from a channel, service, focus or playback property.

- The preceding 10/10 Room and alarm suite remains historical evidence for atomic registration, scope isolation, fixed deadline, action outbox, event deduplication and recovery. Its earlier channel-sound assertion was replaced: the channel is now silent and the service owns alarm audio.
- Notification permission denied phase passed, followed by a separate granted phase against the same stored occurrence. The denied occurrence remained `RINGING` without a notification; granting permission recovered it.
- Exact-alarm access denied phase passed after the system restarted the app: capability was `DEGRADED`, registration used the inexact AlarmManager fallback, and the group was not marked Missed.
- Process-death delivery no longer relies on notification sound/vibration keys. Before the verifier starts, the foreground notification carries the durable group identity and the active player uses `USAGE_ALARM`; the separate human gate proves that output is actually audible.
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
