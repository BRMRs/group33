# C1 / #124 paired device comparison

2026-11-17; Refs [#15](https://github.com/BRMRs/group33/issues/15#issuecomment-6922291973). Local evidence only; no remote comment, PR approval or full UI acceptance is recorded here.

The overview/header assertion and tablet fast-reverse timeout both reproduce on pristine #124. They do not require the C1 date adaptation to occur. This does not determine whether their cause is production scrolling, gesture construction or test timing. One additional API35 timeout occurred only in the combined group and remains unresolved.

## Exact source and artifacts

- Original: #124 `a377a7df0731d48fc052514b61d37955e014f06d`, still the PR head when checked. The review worktree was clean before building it.
- Combined: that exact original plus [124-date-consumer.patch](124-date-consumer.patch), SHA-256 `5a706f23cfd842ccd44c46d7d9b630f3eff4e38a8a7964f3ca9343b8d37713a4`; resulting Git tree `4d62324626fabea997179e1cfbb20fc32d3f3c8b` was verified using an alternate index. A tree ID is not a published commit.
- C1 consumer delivery: local commit `59c9425`, based on main `6114071`. The patch separately contains the consumer/unit tests, nullable shared-host date handling and three scoped-date entry tests. Original scroll assertions and gesture helpers are unchanged.
- Both variants used the identical local Gradle init script to set applicationId `com.group33.medicationreminder.c1review124`; namespace stayed unchanged. No manual-control fixture was added. The installed production package was untouched.

| Variant | APK | SHA-256 |
|---|---|---|
| Original | app | `78c4bf7109c07150972eea4b949c920b9563eb518187117409edf593970a5f85` |
| Original | test | `7b7ccc2667a1250a9e45c2258a47dc0fc4f8c0a5dfaa5d7343d9ba876a30a0e6` |
| Combined | app | `09741087b7505eeabbb4ecc63c3e0da1d493798f073352b57969968503df1cfa` |
| Combined | test | `98841a629d980b3b0aec2f05688dd489b8b580d7af412d59556b93823b437545` |

These identify the new comparison round, not every historical run. Older installed API35 APKs and earlier logs remain separately archived; historical device conditions are not retroactively inferred from this round.

## Device conditions and isolation

| Condition | API35 emulator | Mi Pad 4 Plus / API27 |
|---|---|---|
| Device | Existing `Group33_PR110_C1`, `sdk_gphone64_x86_64` | Existing tablet, `9c69cae2` |
| Physical display / density | 1080 x 2400, 420 dpi | 1200 x 1920, 320 dpi |
| Observed test Activity window | Portrait, 1080 x 2400 | Landscape, 1920 x 1200 |
| System language / font scale | en-US / 1.0 | zh-CN / 1.0 |
| Navigation | Gesture, `navigation_mode=2` | Temporary buttons, `force_fsg_nav_bar=0`; original 1 restored after each variant |

Recorded build fingerprint, display, density, font, locale and navigation metadata match between original and combined runs on each device. Window snapshots confirm the above Activity bounds. Insets remain controlled by the existing fixture. Its narrow bilingual/large-text case explicitly uses a 320 x 480 dp root and fontScale 2.0; those overrides are not device-wide changes. Existing navigation tests establish their own language state.

Before each invocation, only the isolated test application's data was cleared; the screen was awakened and the unsecured keyguard dismissed. Window focus was sampled about every two seconds. Valid runs show the test Activity; normal launch/teardown transitions may show null focus or the launcher. Original/combined device flows could run concurrently on separate devices; no build was running during the valid rounds.

Initial bootstrap attempts are excluded: an emulator System UI ANR dialog intercepted touch injection, and the tablet was not ready to display its Activity. Those attempts were stopped, preserved and repeated after recovery. They are not counted as original-code failures or full-group results.

## Results after device recovery

The group contains `CaregiverFeedbackEntryTest`, `CaregiverNavigationUiTest` and `CaregiverFeedbackContentTest`: 18 original tests, or those 18 plus three C1 date tests in the combination.

| Invocation | Original | Combined |
|---|---|---|
| API35 overview/header single test | 0/1; expected scrollY 0, actual 355 | 1/1 |
| API35 related group | 16/18 | 18/21 |
| API27 button-navigation fast-reverse single test | 1/1 | 1/1 |
| API27 button-navigation related group | 17/18 | 20/21 |

API35 original group failures:

- `overviewContentUsesOnlyTheSharedPageScroll`: expected scrollY 0, actual 397.
- `bilingualLargeTextHistoryHasOneScrollOwnerAndRecyclesLongData`: 5000 ms timeout.

API35 combined group failures:

- The same overview assertion: expected scrollY 0, actual 85.
- The same bilingual/large-text 5000 ms timeout.
- `dateFieldSideDragsScrollWithoutChangingTheStartRegion`: additional 5000 ms timeout, absent from this original group. It remains a combination-only observation until further diagnosis; isolated passes do not erase it.

Both tablet groups failed only `fastReverseAndLastRowDetailsRemainReachable` with a 5000 ms timeout. Both isolated fast-reverse runs passed, so group/order/timing sensitivity remains relevant. No `INJECT_EVENTS` failure occurred in these button-navigation rounds; this does not establish MIUI gesture-navigation acceptance.

The additional API35 date-side timeout was followed up with the same exact APKs and a fresh isolated app state each time: two original single runs and two combined single runs all passed (2/2 each). Logs use `*-api35-date-side-followup-1/2`. The combined group failure remains unresolved; the four isolated passes are not substituted for that group result.

All three new date integration checks and all four existing C1 navigation checks passed in both combined device groups. No production scroll code, timeout or assertion was changed to obtain these results.

## Reproduction and local evidence

Both variants were built in the existing `code/review` worktree with:

```powershell
.\gradlew.bat -I <same-isolated-application-id.init.gradle> :android-app:assembleDebug :android-app:assembleDebugAndroidTest --offline --max-workers=1 --no-parallel --no-daemon
```

The original and combined builds passed. The shared local init script is:

```groovy
allprojects {
    plugins.withId('com.android.application') {
        androidComponents.finalizeDsl { extension ->
            extension.defaultConfig.applicationId = 'com.group33.medicationreminder.c1review124'
        }
    }
}
```

After installing the matching app/test APKs with `adb -s <serial> install -r -t <apk>`, each invocation uses:

```powershell
adb -s <serial> shell pm clear com.group33.medicationreminder.c1review124
adb -s <serial> shell am instrument -w -r -e class <selection> com.group33.medicationreminder.c1review124.test/androidx.test.runner.AndroidJUnitRunner
```

The exact group selection is:

```text
com.group33.medicationreminder.integration.CaregiverFeedbackEntryTest,com.group33.medicationreminder.caregiver.CaregiverNavigationUiTest,com.group33.medicationreminder.caregiver.CaregiverFeedbackContentTest
```

Singles append `#overviewContentUsesOnlyTheSharedPageScroll`, `#fastReverseAndLastRowDetailsRemainReachable` or `#dateFieldSideDragsScrollWithoutChangingTheStartRegion` to `com.group33.medicationreminder.integration.CaregiverFeedbackEntryTest`. Device waking, focus checks and tablet navigation save/restore are required preparation, not changed assertions.

Local raw evidence is retained under `.git/c1-local-recovery-20261117/comparison-124-20261117/`: exact APKs, source inputs, commands/device metadata JSON, raw instrumentation logs, sampled focus, window snapshots, build logs and the local runner. Valid originals use `baseline-*-ready-r2-*`; combinations use `combined-*-ready-r1-*`. These local artifacts are not automatically uploaded with this document. APKs and raw logs should accompany a later authorized handoff; the aggregate pass counts alone are insufficient.

## Remaining boundary

C3 can investigate the reproduced original scrolling/test failures using this exact baseline. C1/C3 still need to investigate the additional combined date-side timeout. This evidence does not approve #124 or establish a green UI regression. After these tests, [S1 recorded the date rule and ownership](https://github.com/BRMRs/group33/issues/15#issuecomment-6939380941); the production provider and reviewed concrete interface remain a separate S1/S2 dependency. Main has since advanced to `bbe4997` via #116, which is not part of either tested input above. No production date source is registered by this work.
