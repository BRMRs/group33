# C1 bound-phone client preparation

2026-11-25. Refs #10 / #118; update candidate for #88. This is client preparation,
not deployment, teammate approval, policy activation or real phone acceptance.

## Inputs and behavior

- Existing #88 `c1e1b9034faa37c6b565bd39bbcad6bd5c96d706` and accepted main
  `9e5621393c3d130bc65ec6754d0531c363c9cf9d` (#96). Main adds documentation only.
- Controller input: #144 `214b8814f524ff8188cc3e4d58e944b5858cef27`.
  #147 `049b9c29b785d2b9f191c51c78e4a6c7c62dda5f` is the supplied Server
  deadline/scheme correction, pending actual parent integration and final review.
- Start sends the original S1 installation. Refresh retains the private Server
  capability and scheme; verification and registration retain the same installation.
  One-tap omits `expectedPhone`; SDK SMS retains the entered expected number.
  Successful verification/registration must echo the original installation.
- The capability stays inside the adapter. Only the SDK grant is given to Fusion.
  A conservative monotonic deadline starts before the first request, clamps to
  reported lifetime and never increases. Private-store checks and queued IO recheck
  time/current generation before a request, and late results cannot publish state.
- Grant and proof work are serialized. Uncertain work closes the scene; no automatic
  new Start or proof replay. A registration ticket is claimed once, uses a conservative
  original expiry, and cannot be resent or reused with a changed role after an unknown
  response. A fresh explicit authentication is required, as specified by #96.
  The former trial retry test is updated to this accepted behavior, not disabled.
- Sign-out/replacement closes the active local scene and fences old completions.
  CommonPhoneSession exposes the existing S1 installation; no second installation,
  session file, anonymous-account migration or client identity assertion is added.
- No password-reset wire exists in #118. Its repository method fails as unconfigured
  before SDK/HTTP work; it no longer consumes a login proof as a supposed reset.
  Existing UI, bilingual strings and release unconfigured-provider behavior remain.

## Verification

On the isolated #88 branch, not the larger local experience composition:

```powershell
$env:JAVA_HOME='D:\program_self\jdk-17.0.17+10'
.\gradlew.bat -I <local-application-id-init.gradle> `
  :android-app:testDebugUnitTest :android-app:testReleaseUnitTest `
  :android-app:lintDebug :android-app:assembleDebug `
  :android-app:assembleRelease :android-app:assembleDebugAndroidTest `
  --offline --console=plain
```

The external init script changes only applicationId to
`com.group33.medicationreminder.c1experience`; it supplies no phone URL or credential.
Debug **190** and Release **150** tests pass, zero failures/errors/skips. Debug includes
15 original-attempt tests, six real loopback HTTP tests and 18 common-session tests.
These are overlapping suite counts, not additional distinct totals. Lint reports
zero errors / 58 warnings; all three APKs build.

The original-attempt cases include shorter/fixed deadlines, suspended private-store
validation, late grants/verification, wrong capability/scheme, original-installation
requests, serialized work, uncertain results and single-use registration. HTTP tests
check exact JSON shapes, optional-field omission, installation echoes, strict lifetime
types and error envelopes. They use fictional credentials on dedicated loopback ports.

Platform validation runs `PhoneTransportIntegrationTest` and
`CommonPhoneSessionIntegrationTest` on an isolated API35 emulator package: **15/15**,
zero failures/skips, using actual Android HTTP/JSON and S1 Keystore/session storage. A
dropped-response case counts received proof bodies on both JVM and Android: streaming
requests prevent the underlying HTTP implementation from replaying the POST. Results and artifact
hashes are retained in the existing local recovery evidence directory. No SDK,
supplier, SMS, live account, cloud service or physical-tablet acceptance is claimed.
Server/Shared source is unchanged; their tests and real PostgreSQL were not rerun
for this client-only slice. Full same-version CI and required review remain gates.

Local logs retain the first test-fixture compile failure (`com.sun.net.httpserver`
is absent from the Android compile surface), its portable ServerSocket replacement,
and the successful subsequent runs. No assertion was relaxed to resolve it.
An emulator reboot temporarily removed package/activity services during one final
install attempt; no tests executed in that attempt. The final 15-case run passed
after normal boot completed, without a device reset or settings change by this run.

## Rollout and rollback

The old unbound trial is not a fallback for this wire. Default phone configuration
remains blank, no provider is enabled and no AccessKey is packaged. Public business
authorization, final Server composition/migrations, supplier proof-retention evidence,
effective HTTPS policy and the registered team package/signature remain under #118.
Only then run real one-tap/SMS, initial-role and authorized-pairing acceptance.

Rollback the C1 adapter changes before activation; no database migration, permission,
supplier dependency or backend mutation is introduced. The separate local experience
composition and its frozen tablet APK are preserved; they are not this PR's payload.
