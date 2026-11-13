# Current interaction (supersedes candidate-selection UI below)

User correction: checking “I have inspected the displayed medication photo” starts local OCR automatically. A recognized name fills only an empty name field; no extra OCR/candidate/copy buttons. One short loading/result message remains. Manual text, dose, dates and schedule are never replaced. Unchecking invalidates the running result. Recognition uses explicit name labels or one unambiguous dosage-form name; unknown/ambiguous text asks for manual input. This is a heuristic, not a medicine database or a guarantee of drug identity.

Earlier candidate-list implementation and test history follows for provenance; it is no longer the current UI.

# C2 OCR local implementation — 2026-11-13

Branch: feature/125-c2-ocr. Base: main 6114071. Refs #125.

Bundled ML Kit Chinese text recognition 16.0.1, using the already authorized decoded bitmap. No new cloud OCR endpoint. The source is not logged. Candidate lines are not trusted medication entities: the caregiver chooses a name or copies text into an empty instructions field; existing input is preserved. Dose and schedule remain manual. Existing photo/time-zone/save confirmation remains required.

Host clear/rebind invalidates pending results; detached components drop their context. Save state disables candidate application. The worker owns its copied bitmap until completion and closes the recognizer. No model-derived data is saved automatically.

This branch deliberately starts on main. #111 and #113 remain separate unmerged work; their formal browser and calendar changes are not included or overwritten. Integration requires preserving their PlanEditorView changes, then testing the combined head.

Verification: see current Gradle output/test reports. Initial development had no connected device. Follow-up API37 emulator verification is recorded below. Actual camera-photo accuracy, API27 Xiaomi, TalkBack, and complete cloud flow remain unverified. This is a bounded assisted-entry implementation, not automatic drug identification or dosage extraction.

Manual check: open authorized photo and plan editor; tap Recognize photo text; compare results with photo; choose name while blank; repeat with an existing name and confirm it remains unchanged; use blank instructions copy; verify dose/time unchanged. Retry blank/blurred image; leave/switch photo during recognition and verify no stale application. Save still requires normal user confirmation.

Final local verification: testDebugUnitTest and assembleDebug passed after UTF-8 resource normalization. Candidate parsing tests cover literal strength preservation, blank/oversized input and bounded results. No device recognition result claimed.

## Follow-up device verification
Medium_Phone emulator, Android API37: connectedDebugAndroidTest passed 10/10, zero failures/skips. Eight existing PlanEditorView tests plus two real OCR tests. Bundled Chinese recognizer processed an in-memory bitmap with fictional English and Chinese labels; both were found. Selected English text filled a blank name in the actual debug editor; an existing name was preserved. Cleared candidate callbacks could not apply. No HTTP OCR mock was used.

These fixtures are clean rendered text, not camera photos or an accuracy benchmark. Mid-recognition account/relationship changes, blank/blurred-image failure behavior, API27 and TalkBack still need further acceptance. Tests do not represent production cloud integration or #111/#113 combined-head verification.

Existing editor tests now distinguish the save action from the added OCR button, preserving their original save/recovery assertions. No app-data reset or cloud mutation was performed. Publication is recorded in the linked PR.

Publication preparation: additional blank-image retry and clear-during-recognition test; initialization exceptions now release owned pixels and restore manual/retry flow. Device total is 11 cases. Real camera photographs, blur/rotation, physical API27, TalkBack and #111/#113 combination remain deferred.

Final pre-publication device run: API37 11/11 passed, zero failures/errors/skips. Debug JVM 127/127 passed. Full Gradle test attempted: Server 627 tests, 310 failures on Windows, including Files.setPosixFilePermissions UnsupportedOperationException and LocalFilePhotoObjectStore POSIX initialization UNAVAILABLE. Server/Shared source unchanged; Linux CI remains required. Do not report full local test success.
