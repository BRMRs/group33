# C1 caregiver navigation preview evidence

These screenshots show the C1 navigation shell only. The sample Elder is explicitly local preview data; it is not a login, pairing, or authorization result. Plans and records remain integration placeholders.

## Emulator checks

- Emulator: Medium Phone, Android API 35, English (US), 420 dpi.
- Default viewport: 1080 × 2400 px (about 411 × 914 dp), font scale 1.0.
- Small viewport check: 840 × 1872 px (320 × 713 dp), font scale 2.0. Device settings were restored after capture.
- `:android-app:connectedDebugAndroidTest`: 4 tests passed, 0 failed. Tests cover all three destinations, no-Elder guidance, preview labeling, and preserving Settings after switching language.
- `test`, `:android-app:lintDebug`, and `:android-app:assembleDebug` passed.

The bottom navigation stays outside the independently scrolling content area. At 320 dp / 200% text, scroll the content to read the full guide; the Plans / Records / Settings bar remains fixed and reachable. TalkBack, grayscale, and manual contrast measurement are not claimed as completed by these checks.

## Screenshots

- `navigation-demo-en.png`: sample Elder preview at the default viewport.
- `no-elder-guide-en.png`: no trusted Elder context at the default viewport.
- `no-elder-guide-320dp-200pct-en.png`: no-Elder guide at 320 dp / 200% text; this is the scrolled view to show the fixed bottom navigation while reading the long guidance.

### 2026-11-22 main b5acc05 compatibility verification

Candidate source tree 5008c4ced0a76712ed44293549606581b7900cb9, based on PR head ad047a74ccda51b0ab663f98f1c15e12c9695be9 and
main b5acc05224612bf25797ee71a5cdf1a879b67d91. This integration takes the existing main event-row component
and its fixtures without changing C1 auth/pairing/session behavior, DTOs, Server,
Shared or production registration. The only merge conflict was the artifact
manifest; hashes were regenerated from raw Git blobs (CRLF checkout for .bat).

Actual local command (isolated Debug application ID, offline cached dependencies):

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest :shared:test -PdebugServerUrl=http://127.0.0.1:18087 -I ../group33/.git/c1-local-recovery-20261117/login-isolated.init.gradle --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

Debug 135 / Release 134, zero failures/errors/skips;
lint zero errors / 32 existing warnings; all three APKs built.
Shared was UP-TO-DATE, not a newly executed suite. Server was not rerun: unchanged.
Repository policy and diff whitespace checks passed. Logs, XML reports and frozen
APK hashes are retained in the c1-main-127 local evidence, not regenerated
build artifacts committed to Git.

Mi Pad 4 Plus API27, isolated c1dev88 package: CaregiverNavigationUiTest plus SharedLoginNavigationTest passed 6/6 (including both languages). The initial local wrapper expected five instead of the six selected source methods; the retained raw runner says OK (6 tests), with no failures. Only the wrapper result classification was corrected; no device assertion was changed.
This is current-main compatibility evidence, not real phone login, two-client
pairing or final C2/C3 composition acceptance. Existing human feature acceptance
remains historical; no additional human acceptance is claimed here.

Extended main event-row device checks on the #112 candidate were 5/7 (combined
with C1 navigation: 8/10); separate baseline reruns reproduced both failures.
One Debug host did not receive the expected system-bar padding on API27; another
fixture captured screenshots on the UI thread and stalled UiAutomation teardown.
These files were exact main versions. A separate Refs #19 Debug/test-only repair
candidate is being validated, not silently mixed into this main-sync delivery.
Do not describe the extended component suite on this PR as fully passing.
Final integration review and applicable non-author approvals remain required.
