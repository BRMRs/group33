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
