# PR #35 deadline and installation identity local verification

Date: 2026-10-26 (Asia/Shanghai). Input: parent `d4c040a0c3cbe64ec79008d13ffd97649fb9d13f` plus this uncommitted patch. No commit, push, approval or human signature is claimed.

## Scope and behavior

- Preview recorder checks the original occurrence date/time against its injected clock before creating a receipt or changing status. The accepted interval remains `[plannedAt, plannedAt + 2h)`.
- A queued Taken or Remind later action that executes at the deadline fails without recording success. ViewModel shows Window closed, marks the preview Missed and disables actions.
- Preview window derivation uses the occurrence date, so yesterday's occurrence cannot reopen during today's window. This is only the existing preview implementation, not a production group action protocol.
- Installation identity production code is unchanged. Instrumentation uses the real Android SharedPreferences adapter, not FakeAuthRepository.

## Regression evidence

Before the production fix, both new ViewModel tests failed (10 tests, 2 failures): click at 09:59:59.999, keep the coroutine queued, advance the clock to 10:00:00, then execute it. Taken and Remind later incorrectly wrote a status. After the fix both pass, assert no recorded status, Window closed feedback, Missed and disabled actions.

Direct recorder tests cover both actions just before, exactly at and just after deadline, before planned time, and an old occurrence on the next day.

## Local commands and results

- `python3 scripts/check_repository_policy.py`: PASS.
- `./gradlew :android-app:testDebugUnitTest --tests 'com.group33.medicationreminder.elder.*' --console=plain`: PASS.
- `./gradlew test :android-app:lintDebug :android-app:assembleDebug :android-app:connectedDebugAndroidTest --console=plain`: PASS.
- XML results: Android debug 38, Android release 38, Shared 16, Server 30; zero failures/errors (some tasks reused Gradle cache).
- Connected tests: 3 passed on Medium_Phone emulator, Android 17 / API 37, JDK 17.0.18.

Connected assertions: new installation is signed out; adapter recreation after flushing writes restores the same ID/role/trimmed name; conflicting role is rejected without replacing the active or persisted session. Tests clear the fictional test installation preferences before/after each case. These prove real adapter recreation and storage interaction, not process death, whole-device reboot, real HTTP/JWT or API 35 coverage.

Reports: `android-app/build/reports/tests/testDebugUnitTest/index.html`, `android-app/build/reports/tests/testReleaseUnitTest/index.html`, `android-app/build/reports/androidTests/connected/debug/index.html`, `android-app/build/reports/lint-results-debug.html`.

The pre-existing local `gradle.properties` modification is preserved and excluded from this patch. Its manifest entry continues to identify committed repository contents, not local machine configuration. Human review and device/TalkBack acceptance remain separate.
