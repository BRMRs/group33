# C2 editor visual refresh — local proposal, 2026-11-06

Base: PR #110 at 4db15b775a85b143fa79cc6aeb29fc9d95f25800.
Branch: feature/51-calendar-editor. Refs #51. Not an approved shared design or a fix for #110's existing review blockers.

## References and decisions

- Medisafe official screenshot: https://get.medisafe.com/desktop/ — clear hierarchy and prominent primary action.
- MyTherapy store listing: https://play.google.com/store/apps/details?id=eu.smartpatient.mytherapy
- MyTherapy visual review: https://techguide.parkinsons.org.uk/catalogue/my-therapy/review — direct medication entry prompts.

These references inform hierarchy, not copied assets or a claim of usability superiority. Screenshots may represent different app versions. Preserve the repository's deep-blue primary, neutral surfaces, system type and existing shared color tokens. C1 should review integration of feature styling before publication.

The existing editor now groups photo verification, medication details, schedule and time-zone confirmation in four sections, with persistent labels, outlined focus, a high-contrast save action and readable timestamp examples. Dates retain ISO storage format and inclusive-boundary guidance. No automatic dose inference, photo replacement or authorization changes. The Debug toolbar is compact and remains visibly simulated.

## Verification

- Debug and Android test APK builds passed.
- PlanFormViewModelTest 9 and PlanSaveViewModelTest 12 passed.
- Existing PlanEditorViewTest 8 passed on emulator-5554, including save, required fields, labels, result reconciliation and cleanup; repeated after date hint/focus changes.
- A final one-line generation-guarded scroll reset was built and visually checked afterward; initial screen now shows the heading and photo section. This final scroll-only edit did not receive another device-suite run.
- English screenshot inspected at current emulator settings; date hint truncation and initial scroll position corrected. Chinese resources added; this iteration does not claim a completed Chinese/200%-font/TalkBack matrix or real backend acceptance.
- Local Debug launcher extra C2_FORM_PREVIEW opens the existing non-exported fixture through the exported Debug preview Activity; no Release launcher change.

Open on emulator:

```powershell
adb -s emulator-5554 shell am start -n com.group33.medicationreminder/.caregiver.CaregiverNavigationPreviewActivity --ez C2_FORM_PREVIEW true
```

Originally published against the #110 feature branch. After #110 merged, PR #113 targets main; the 2026-11-06 synchronization below supersedes the old dependency. No human approval is inferred.

## Calendar follow-up

Start/end fields now open a calendar-mode DatePickerDialog, with a calendar icon and no free-form keyboard input. Confirmation writes LocalDate ISO text; cancel preserves the prior value; the optional end picker supports No end date. Existing form validation still checks date ordering. The dialog is dismissed on clear and its callback is guarded against stale bindings/disabled fields.

Debug/test builds pass. All 9 PlanEditorViewTest cases pass on emulator-5554, including a new leap-day selection, cancellation and optional-end clearing test. The new platform-window test requires API29+; it does not establish API27 device acceptance. Initial Espresso execution was blocked by API37 InputManager compatibility; the test was moved to platform window access and corrected to await asynchronous dialog callbacks. No production logic was weakened for the test.

## Final publication checks

Android Debug JVM suite: 113 tests passed. lintDebug, assembleDebug, assembleRelease and assembleDebugAndroidTest passed. Platform buttons had invisible inherited styling; explicitly colored Cancel/No end date and a solid deep-blue Confirm now provide visible actions. Actual tapping selected 2026-11-10 and Confirm populated the field. The fixture performs no real save.

English [form](c2-editor-refresh/en-top.png), English [calendar](c2-editor-refresh/en-calendar.png), and Simplified Chinese [form](c2-editor-refresh/zh-top.png) are actual emulator captures. Chinese was explicitly selected with platform app locale for screenshot inspection, not proof of the parent cold-start language fix. The English drawn test image remains a labeled synthetic fixture.

Remaining #51: C1 shared-style/host review, full Chinese/English small-screen and 200% font matrix, TalkBack and API27 calendar confirmation checks, actual HTTP/device acceptance. Screenshots and tests are not human approval.
## Main synchronization — 2026-11-06

Merged main `9d5f6df` without rewriting the feature branch history. Preserved current main integration fixes and only this PR's existing scope. Repository policy and whitespace checks pass. Android Debug JVM suite: 119 tests, zero failures/errors/skips; lintDebug, assembleDebug and assembleRelease pass. Nine PlanEditorViewTest instrumentation cases also passed on emulator-5554 (API37); no API27/TalkBack/cloud acceptance was rerun.

The attempted full gate1Check on synchronized #113 on Windows could not pass the inherited POSIX photo-store/server tests (627 executed, 310 failures); this is not a green full local gate. Ubuntu CI must verify the final merge head. This update adds no new feature, human approval or main merge.

## Local form-usability follow-up — 2026-11-08

Implements the two non-blocking #51 suggestions: photo confirmation immediately precedes Save, and reminder times use a 24-hour picker with add/edit/remove rows. ISO HH:mm formatting is locale-independent; existing duplicate/required/invalid validation and original save/recovery semantics remain. Dialog callbacks are generation/enablement guarded and dismissed on clear or non-editable save state. English and Chinese labels and named accessibility actions are provided.

Final local Android JVM: 119 passed, zero failures/errors/skips. lintDebug, assembleDebug, assembleRelease and assembleDebugAndroidTest pass. Emulator API37 PlanEditorViewTest: 10/10 passed, including add/edit/remove, duplicate rejection, cancel, confirmation placement and dialog cleanup; existing date and save/recovery regressions remain green. Chinese actual layout inspected in the explicit synthetic Debug fixture. No API27/TalkBack/cloud/two-device result claimed; no real plan was saved. This follow-up is submitted for renewed review; prior approvals do not establish acceptance of these new UI changes. Final-head remote CI is tracked in PR Checks.

Chinese time-list layout: [actual Debug fixture](c2-editor-refresh/zh-times.png). The fixture data and save are simulated.
