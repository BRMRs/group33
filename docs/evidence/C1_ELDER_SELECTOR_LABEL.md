# Current Elder field accessibility label

2026-11-27. Refs #19, U01/W02 scoped checks. Base main
`08f901ac4e44393dc850459caf3924e463f3f5c9`. Agent-assisted C1 implementation and
verification; no human accessibility sign-off or whole-Issue completion.

## Problem and change

The visible Current Elder/当前长者 title was not associated with the selector.
The enclosing TextInputLayout disables its hint, so the Android accessibility
node exposed no field label. The repair adds only `android:labelFor` linking the
existing translated title to `current_elder_value`. No duplicate description,
new translation, business state, permission, API, schema or authorization change.

## Reproduced before, verified after

Physical MI PAD 4 PLUS, Android8.1/API27, original system font1.0,1200x1920,density320.
Only an isolated new package `com.group33.medicationreminder.c1labeln27` was used.
The external init changes applicationId only; no private configuration is committed.

`CaregiverElderLabelTest` reads the actual platform accessibility tree for the
foreground test package, with view-ID reporting enabled temporarily and restored.
The two localized field-label assertions fail on the unchanged main layout:

| Method | Before | After |
|---|---|---|
| englishSelectorExposesItsFieldLabel | expected Current Elder, actual null | pass |
| chineseSelectorExposesItsFieldLabel | expected 当前长者, actual null | pass |
| LocalLanguageSettingsRepositoryTest.choiceSurvivesNewRepositoryInstance | pass | pass |

The test APK is byte-identical before and after:
`0aade6af2ebc1e0cbee521f0928f674e30845ac34b1d12bb46f88b6c6e3c1fce`.
Baseline product APK:
`6620b1c08b96743f67b08aa8dee434b01d0bcdfdaf157f359e8158889a2d5e00`.
Fixed product APK:
`e8371540bf05e6025a2923f85c32ddb27dfb8df3f263687441b3dea9a12e85de`.
Final instrumentation:3 unique completions,status0,`OK (3 tests)`. This is metadata
and preference persistence, not spoken TalkBack order, active account/business
retention, 200% system-font, grayscale, production phone or two-device acceptance.

## Commands and checks

```text
gradlew -I <private-isolation-init> :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
adb -s <selected-device> shell am instrument -w -r -e class com.group33.medicationreminder.caregiver.CaregiverElderLabelTest,com.group33.medicationreminder.core.data.LocalLanguageSettingsRepositoryTest com.group33.medicationreminder.c1labeln27.test/androidx.test.runner.AndroidJUnitRunner
```

Fixed build exit0 in2m34s:150 tasks,73 executed/10 cached/67 up-to-date.
Debug277/Release276 JVM reports have0 failures/errors/skips; all three APKs built.
Debug lint has0 errors/59 warnings. Server/Shared were not independently rerun
for this one-layout change; full same-head CI remains a publication/review gate.
Raw baseline/fixed instrumentation, build logs and hashes remain privately under
`.git/c1-progress-20261127/`. No screenshot of another app or real personal/medical
data is committed. Repository policy, whitespace and complete file checksums
are checked before publication.

## Scope, review and rollback

The [C1 acceptance audit](C1_ACCEPTANCE_AUDIT_20261127.md) records the broader
#10/#19 evidence and remaining work; it is not an accepted replacement specification.
The start/evidence handoff is recorded in Issue#19 comment7221036139 and
Issue#10 comment7221051478. Required current-head non-author review and applicable
E accessibility perspective remain; only the lead/recorded delegate merges.
Revert the layout association/test and regenerate checksums to roll back; no
data migration or deployed configuration changed.
