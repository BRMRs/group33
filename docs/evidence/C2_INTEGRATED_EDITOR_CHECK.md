# C2 integrated editor verification — 2026-11-15

Local acceptance build: latest fetched main `6114071`, OCR #126 `155e52a`, and calendar/layout #113 `cedb09a`. #113 is OPEN, not merged into main. This integration is not a claim that all open team PRs are included.

## User-confirmed scope

Preserve amount per dose as an independent field. Add a daily reminder-count wheel with matching time rows; use 24-hour hour/minute wheels. Preserve existing chosen times when increasing/decreasing count and validate duplicates. The picker offers 1–24 reminders (existing larger lists are retained). Newly added times are editable defaults, not inferred dosage or medical guidance. Cancel must not apply changes; clearing context dismisses dialogs.

Reuse #113 calendar and cards. Photo confirmation is beside the photo and starts existing OCR. Retain protections against stale recognition and overwriting manual names.

Default to known Elder zone when the draft has no zone; require explicit confirmation when unknown or mismatched. Remove the online host's hardcoded Asia/Shanghai default. The current host supplies no Elder zone: automatic device-zone transfer is NOT implemented. No shared API or schedule semantics changed. Existing plans retain their explicit zone; future device-zone changes do not automatically rewrite plans.

## Verification

- Debug build and lint passed.
- 129 Debug JVM tests passed (0 failures/errors/skips).
- 14 emulator API 37 instrumentation tests passed: PlanEditorViewTest and PhotoTextSuggestionsTest. Includes count/time correspondence, 23:59, duplicate rejection, cancellation, context clearing, calendar leap day/end clearing, known/unknown zone behavior, save lifecycle and OCR overwrite/cancel protections.
- Used adb install -r and direct instrumentation rather than Gradle connected tests to preserve the user's online login/pairing app data. Test fixtures do not submit cloud plans.
- User's low-resolution medicine image reproduced OCR failure. Original and 3x scaled image both produced corrupted text and no candidate. This is NOT fixed; do not insert a guessed medicine name. Clearer single-box image or manual entry remains required. Private photo and diagnostic test were moved outside source and are not committed.
- Full `gate1Check` did NOT pass: server ran 627 tests with 310 failures. The local file-photo tests report `UnsupportedOperationException: posix:permissions not supported as initial attribute` on Windows, with dependent PhotoObjectException failures. Server files were not changed. Android-focused checks above passed; do not report all-repository checks green.
- Xiaomi physical-device and TalkBack acceptance not performed.

## Manual next check

On the caregiver emulator reopen the uploaded photo/editor. Check photo confirmation, daily count and each time; choose dates and confirm. When Elder zone is unknown, explicitly enter/confirm the intended IANA zone before saving. Verify the submitted plan from the paired Elder. Do not count this automated local verification as cloud end-to-end acceptance.

## Time-zone input follow-up

The execution zone is now a read-only selector with localized zone names and IANA IDs. Unknown zones and unavailable date/time examples disable and clear acknowledgement. Dialog selection is scope-gated and dismissed on access/context loss. Twelve PlanEditorViewTest emulator tests passed, including rejecting an injected `8:00` zone and selecting Asia/Shanghai. Locale lookup uses ConfigurationCompat for API 23 compatibility.

## Search and emulator input follow-up

Zone picker now filters localized display labels, Chinese/English time-zone names and IANA IDs, case-insensitively, with an empty-result message. Twelve PlanEditorViewTest emulator tests and Debug lint/build passed after this change; coverage includes Chinese query, lowercase ID query, empty result and correct selection after filtering. Both emulator Gboard configurations now list English QWERTY and Simplified Chinese Pinyin; show_ime_with_hard_keyboard is enabled. No app text or cloud plan was submitted to verify keyboard configuration.
