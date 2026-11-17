# C1 caregiver language navigation regression

Date: 2026-11-13 (Asia/Shanghai). Refs #19; C3 report on #124.

The original reproduction and results below are historical. The 2026-11-17
current-main verification is recorded separately at the end of this document;
older APK hashes and CI results do not describe the updated branch.

## Affected sources and cause

The reported test and locale code are already in `main` through PR #110
(`9d5f6df`). The test source is identical at current main `6114071`, C1 login
PR #88 (`66c67de`), C3 overview PR #122 (`c455006`) and C3 date-entry PR #124
(`450f895`). This correction is isolated on `fix/19-c1-language-switch`, based
on main `6114071`; it does not modify the pending login, pairing or C3 feature
branches.

The old setup clears `language_settings` and calls
`AppCompatDelegate.setApplicationLocales(en)` before an Activity delegate exists.
On API 33+, that call alone does not reliably replace the framework's restored
application locale. Clearing the preference file also does not reset the
already-created language repository's in-memory choice. The existing production
`Group33Application.applyLanguage(choice, context)` already handles the framework
locale through the supplied Application context.

A saved Chinese choice reproduces the reported mismatch. Adding only a strict
initial-language assertion to the old test demonstrates that it starts in Chinese:

```text
Test must start in English expected:<[en]> but was:<[zh]>
Tests run: 1, Failures: 1
```

The subsequent click correctly switches that Chinese screen to English. The old
test nevertheless expects Chinese because it assumes setup succeeded. This is a
test initialization defect; this reproduction does not show a broken user-facing
language switch or a regression introduced by C3's date entry.

## Correction and retained assertions

- Set the explicit English choice through the shared repository and apply it on
  the main thread through the production context-aware helper.
- Restore the original repository choice and framework locale in teardown.
- Wait for a resumed Caregiver Activity with the requested actual resource locale
  before checking UI. A lifecycle callback and a 10-second failure limit replace
  assumptions about asynchronous configuration/recreation; there is no fixed sleep
  or assertion retry. The callback is always removed.
- Keep the strict Chinese button, Chinese Plans label and selected Settings checks.
  Also check the initial English screen and the reverse switch to English.
- Add a recreation case that checks the saved Chinese choice through a new
  repository instance and retains Settings after Activity recreation.

Production Activities, locale implementation, strings and authentication behavior
are unchanged. This covers W02/U01 language/navigation subchecks, not full product
or Gate 1 acceptance.

Android references: [AppCompatDelegate locales](https://developer.android.com/reference/androidx/appcompat/app/AppCompatDelegate#setApplicationLocales(androidx.core.os.LocaleListCompat))
and [Espresso asynchronous synchronization](https://developer.android.com/training/testing/espresso/idling-resource).

## Actual device results

Windows, JDK 17.0.17, Android SDK 35; Android 15/API 35 x86_64 emulator.
The existing test AVD was launched with `-read-only -no-snapshot -no-window` on
port 5560. All preference fixtures and installed APKs were confined to that
disposable runtime; no physical device, cloud service or phone SDK was used.

| Revision and starting condition | Actual outcome |
| --- | --- |
| Unmodified main, clean/default English | Navigation 3/3 pass |
| Unmodified main, saved Chinese preference before process launch | Navigation 2/3 pass; original expected Chinese / actual English failure |
| Old setup plus only the initial-language diagnostic, saved Chinese | 0/1 pass; initial en / actual zh failure before any click |
| Corrected test, saved Chinese | Navigation 4/4 pass; 22.101 seconds |
| Corrected test, saved English | Navigation 4 + shared login 2 + repository persistence 1 = 7/7 pass; 41.463 seconds |

The two corrected runs contain 7 distinct test methods and 11 successful
executions. They are not 11 distinct test cases. All corrected cases executed;
there were no skips.

The production APK was unchanged between the failing and passing device runs:
SHA-256 `02a7b3d7c34d7a01d6822c68229d7ce3dea0e4ed9ab8a97c0ecba5bad743484e`.
The fixed test APK SHA-256 was
`56956b005630d32ac0a2e2e5e1ae52950f5a4b26e2c7dfbe5d01a1ebcb62a0b8`.

## Reproduction

Build and install both APKs into a disposable API 35 emulator:

```text
./gradlew :android-app:assembleDebug :android-app:assembleDebugAndroidTest
adb -s emulator-5560 install -r -t android-app/build/outputs/apk/debug/android-app-debug.apk
adb -s emulator-5560 install -r -t android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk
```

To reproduce the restored-language condition, stop the test app, place this
fictional preference fixture at `/data/local/tmp/group33-language-fixture.xml`,
and copy it as the Debug app into `shared_prefs/language_settings.xml` using
`adb shell run-as com.group33.medicationreminder`. The `shared_prefs` directory
exists after an initial test launch. Do this only on a disposable test install.

```xml
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map><string name="choice">SIMPLIFIED_CHINESE</string></map>
```

```text
adb -s emulator-5560 shell am force-stop com.group33.medicationreminder
adb -s emulator-5560 push saved-chinese.xml /data/local/tmp/group33-language-fixture.xml
adb -s emulator-5560 shell run-as com.group33.medicationreminder cp /data/local/tmp/group33-language-fixture.xml shared_prefs/language_settings.xml
adb -s emulator-5560 shell am instrument -w -e class com.group33.medicationreminder.caregiver.CaregiverNavigationUiTest com.group33.medicationreminder.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat with `ENGLISH` in the fixture and include
`com.group33.medicationreminder.SharedLoginNavigationTest` and
`com.group33.medicationreminder.core.data.LocalLanguageSettingsRepositoryTest`
as comma-separated classes for the 7-case related-language run.

## Repository checks and limits

Both Debug APKs build successfully. Repository policy and `git diff --check`
pass; all 365 review manifest entries match, including this evidence document.
The changed test and contribution-record digests are updated.

The required combined command was actually attempted:

```text
./gradlew gate1Check test :android-app:lintDebug :android-app:assembleDebug --offline --no-daemon --max-workers=1 --console=plain
```

It exited 1 on Windows, not green: Debug lint, APK assembly and all 124 Android
Debug unit tests passed, but Server had 627 executions with 310 failures. The
failures include Windows `UnsupportedOperationException` at the existing
`Files.setPosixFilePermissions` fixture, local photo-store `UNAVAILABLE` errors
and dependent Spring-context failures. Server and Shared sources are byte-for-byte
unchanged from main. These failures are retained and not skipped or changed by
this test-only correction; Linux CI must supply the full repository check.

Release Android and Shared unit tests were run separately because the Server
failure stopped the combined task graph: the command exited 0. All 124 Release
Android tests actually executed and passed (zero failures/errors/skips). Shared's
18 passing tests came from the Gradle cache, not a new execution. Linux CI status
is recorded with the PR.

No C3 full-feature device suite, physical/API 23 device, server, PostgreSQL,
phone-authentication or complete bilingual end-to-end acceptance is claimed by
these device runs. C3 can apply this focused test correction without changing the
feature implementation or relaxing the failed assertion. Formal review remains
with the assigned people.

## 2026-11-17 current-main verification

Following the [maintainer's requested update](https://github.com/BRMRs/group33/pull/127),
the existing branch was merged locally with main
`bbe4997034ecb837d2093f4e73267cc530976f29`. The old PR head `fc18993` remains
in ancestry; no reviewed history was rewritten. The merge had no source conflict.
`CaregiverNavigationUiTest.kt` is byte-identical to that reviewed correction.
Relative to the new main, the PR still changes only the navigation test,
contribution record, this evidence and their artifact hashes. Production locale,
UI, identity, Shared and Server code have no additional PR changes.

Actual commands on Windows/JDK 17, with `--offline --max-workers=1 --no-parallel --no-daemon`:

```powershell
.\gradlew.bat gate1Check test :android-app:lintDebug :android-app:assembleDebug :android-app:assembleDebugAndroidTest --continue
```

Android Debug 130/130, Android Release 129/129 and Shared 18/18 tests passed with
zero failures/errors/skips. These task executions were not marked FROM-CACHE.
Lint reported 0 errors/41 warnings; app/test APKs, Server bootJar and schemaMigrationDist
built. The combined command exited 1 because Server reported 311 failures out of
628 tests, including Windows POSIX-permission failures and dependent initialization
errors. No Server test or assertion was modified, skipped or counted as green.
The updated head's Linux CI remains pending publication; the historical `fc18993`
CI result above does not satisfy final-head validation.

API35 device validation used the existing AVD in a disposable
`-read-only -no-snapshot -no-window` runtime on port 5560, after the build finished.
The app used its standard applicationId `com.group33.medicationreminder` only in
that disposable emulator. Before each invocation, fictional preferences were set
to the named starting choice and the API35 framework locale was explicitly set
and read back (`zh-CN` / `en`) with `cmd locale`. The test then had to reset its
own initial locale through the unchanged production helper. Window focus was
sampled during each invocation.

| Starting condition | Actual result |
|---|---|
| Saved Chinese preference and framework locale | Navigation 4/4 passed |
| Saved English preference and framework locale | Navigation 4 + shared login 2 + language persistence 1 = 7/7 passed |

These are seven distinct methods and eleven successful API35 executions, with
zero failures/skips. The emulator was closed without saving its disposable state.
An earlier cold boot showed an unrelated system-app ANR before testing; it was
stopped and was not recorded as a test result.

API35 APK SHA-256:

- app: `993989fcc5b087990cd788c44cecfe0f8998ee60b3f2cb6f5bb237f2fe6e7b46`
- test: `313e25b75935296e9d504c18ceb0dfacb688c1a3810db1b36b861224263e452e`

API27 physical compatibility was run on the connected Mi Pad 4 Plus using the
isolated applicationId `com.group33.medicationreminder.c1review127`. Its APKs were
built with a local Gradle init script changing only the applicationId, not source
or namespace. The original installed application was not cleared, replaced or
launched by these commands. Only the isolated test packages and their temporary
fixture were removed afterwards.

```powershell
.\gradlew.bat -I .git/c1-local-recovery-20261117/pr127-sync/tablet.init.gradle :android-app:assembleDebug :android-app:assembleDebugAndroidTest --offline --max-workers=1 --no-parallel --no-daemon
python .git/c1-local-recovery-20261117/pr127-sync/run_language_regression.py 9c69cae2
```

| API27 starting condition | Actual result |
|---|---|
| Saved Chinese preference | Navigation 4/4 passed |
| Saved English preference | Navigation 4/4 and persistence 1/1 passed; shared login 0/2 passed |

The second invocation therefore failed overall: 5/7 passed, 2 failed. The English
login case attempted to scroll a phone field whose layout was not ready (0x0);
the Chinese login case failed with `InjectEventSecurityException` /
`INJECT_EVENTS`. One separate diagnostic invocation of the unchanged login class
passed the English case but still failed the Chinese case (1/2). Both original
failures remain recorded; the diagnostic does not replace the failed group.
No navigation assertion was relaxed and no login-test or production change was
added to this PR. The tablet's Chinese-login automation failure remains unresolved;
API27 related-language coverage is not fully green. API27 has no framework
per-app locale command, so these physical runs verify saved-preference compatibility,
not the API33+ restored-locale counterexample validated on API35.

API27 isolated APK SHA-256:

- app: `75f9cda7313248d7000f9e512c471a4b80ca01c9cbf7e9d2a71d7bf3f5971be6`
- test: `3d2987793cd8ec78ea3b8eca02c83f17f4aedbf2ea3d996b97bfd069d2831ffd`

Exact commands, source inventory, APKs, window samples and raw local reports are
retained under `.git/c1-local-recovery-20261117/pr127-sync/`; they are not committed.
This check does not approve C3 #124, complete #15, validate a phone service or
replace final-head CI and the maintainer's renewed review.
