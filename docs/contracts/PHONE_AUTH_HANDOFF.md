# Phone sign-in handoff

**Status: experimental implementation for Android/Server review.** The product flow below follows the user's clarification on 2026-10-29: Elder and Caregiver use the same phone sign-in; a verified first-time user chooses a role. The routes are implemented on the feature branch but are not an accepted API contract or a verified production deployment.

## App flow

1. The Android app asks the App Server for a short-lived Alibaba Fusion Auth token.
2. The app starts Fusion Auth scene `100001`. Alibaba performs one-tap number verification and follows the configured SMS authentication path when one-tap times out or fails.
3. On SDK success, Android sends the opaque verification token to the App Server. The app never treats a client-supplied phone number as proof of identity.
4. The App Server verifies the token with Alibaba. An existing phone account receives its stored role and an app session. A new phone account receives a short-lived registration ticket; the user selects Elder or Caregiver, then the server creates the account and returns an app session.
5. Android stores only the app session using this branch's experimental `PhoneSessionStore`. Integration with S1's common session foundation in PR #84 is still pending; the two stores must not become parallel production session sources. Alibaba credentials and verification tokens are not persisted or logged.

The proposed identity rule is one verified phone number per account with a role fixed at first registration. Existing accounts never choose a role during sign-in. Changing that role or using one phone for both roles needs an explicit product decision.

## Proposed HTTP seam

All routes are under `/api/v1/auth` and require TLS.

### Get a Fusion Auth token

`POST /fusion/token` with an empty body. The App Server calls Alibaba `GetFusionAuthToken` using the configured scheme, Android package name, package signature, and Android platform, then returns:

```json
{"fusionAuthToken":"<short-lived-token>","schemeCode":"<scheme-code>","expiresInSeconds":900}
```

The Alibaba AccessKey and secret stay in the App Server's secret store. The app must request a fresh token after expiry.

### Verify the SDK result

`POST /fusion/verify`:

```json
{"verifyToken":"<opaque-sdk-result>","expectedPhone":"<phone-entered-for-sms-only>"}
```

The server calls Alibaba `VerifyWithFusionAuthToken`. For SMS, `expectedPhone` is only a match check; the server must use Alibaba's verified number as the identity and reject a mismatch. For one-tap, omit `expectedPhone`. Suggested outcomes:

```json
{"status":"SIGNED_IN","accessToken":"<app-session>","expiresInSeconds":86400,"user":{"id":"...","role":"ELDER","displayName":"..."}}
```

```json
{"status":"REGISTRATION_REQUIRED","registrationTicket":"<short-lived-single-use-ticket>"}
```

### Complete first registration

`POST /phone/register`:

```json
{"registrationTicket":"<ticket>","role":"ELDER","displayName":null}
```

For `CAREGIVER`, `displayName` is required. The ticket is single-use, short-lived, and bound server-side to the phone already verified with Alibaba. The response returns the same app-session shape as a successful sign-in. Existing account tickets cannot change role.

The exact routes, DTOs, errors, rate limits, database migration, and session lifecycle need S1/S2 review before this implementation is frozen. The current IP limit is process-local and is not a substitute for shared rate limiting at a public deployment. The home pages currently remain previews.

## Configuration for real integration

The server is disabled by default. Configure these as server environment variables, not in the Android app or repository:

- `PHONE_AUTH_ENABLED=true`, `PHONE_AUTH_SCHEME_CODE`, `PHONE_AUTH_PACKAGE_SIGN` (the MD5 signing fingerprint without colons), and optionally `PHONE_AUTH_PACKAGE_NAME`.
- `ALIBABA_CLOUD_ACCESS_KEY_ID` and `ALIBABA_CLOUD_ACCESS_KEY_SECRET`: credentials for a restricted RAM principal with Fusion Auth API permissions.
- `PHONE_AUTH_DATABASE_URL` (a PostgreSQL JDBC URL), `PHONE_AUTH_DATABASE_USER`, and `PHONE_AUTH_DATABASE_PASSWORD`.
- `PHONE_AUTH_HASH_KEY` (at least 32 bytes; keep it stable so existing accounts remain discoverable), and a strong non-default `JWT_SECRET`.

The experimental server creates its two phone-auth tables on first use, so the database principal needs schema creation rights. Plan and review a migration before deployment. No real credential belongs in a commit or screenshot.

For a single-machine development run, the bundled H2 driver also accepts a persistent file URL such as `jdbc:h2:file:./local-phone-auth;MODE=PostgreSQL` with a local database user and password. This is not a substitute for the project's PostgreSQL deployment or its integration checks. Protect the local database file and use a separate test environment.

Build Android with `-PphoneAuthBaseUrl=https://<server>` and `-PphoneAuthPrivacyPolicyUrl=https://<app-policy>`. The app requests consent before initializing the SDK. A published app privacy policy must describe the actual Alibaba SDK use and data handling. Release builds require the console scheme to match the release signing certificate; the current console signature matches this machine's debug certificate only.

The app exposes separate one-tap and SMS entries. Every tap on SMS opens a blank app phone field; after the user enters a number, the app passes it to a new Alibaba Fusion scene and prefills the provider field. The SDK owns code sending, its resend timer and frequency checks. Alibaba may require an upstream SMS challenge after its risk checks; that challenge remains in the flow. Both the code page and the upstream SMS page pass a successful Fusion token to the app server; a server-accepted proof returns to sign-in or first-time role selection. Leaving the provider screen ends the scene and returns to the login choices. A failed one-tap attempt returns to the app's alternatives and error message. The legacy password and hand-entered local-demo SMS form are not production authentication.

On the 2026-11-06 Xiaomi tablet trial, Alibaba opened the upstream SMS challenge and returned proof to Android twice, but the local trial server did not answer the verify request within the former 20-second client timeout. The app now waits up to 45 seconds for this verification request. This is not evidence of a completed sign-in: S2 still needs to trace the server-to-Alibaba verification latency, configure bounded upstream timeouts and deploy the server behind HTTPS before team testing.

### Password UI handoff (2026-11-05)

- Password sign-in has no accepted server endpoint. The Android screen keeps the entry visible but reports that the server connection is pending; it does not issue a password request or sign in.
- Password recovery uses the Alibaba Fusion SMS verification scene. A successful SMS proof currently ends with a clear pending-service message; it does not change a password.
- First-time Caregiver registration shows an optional password field and an explicit skip action. Until password storage is agreed and deployed, entering a password is blocked with a message, while skipping continues registration. No password is silently discarded or stored on the device.
- The Android session file contains the server account ID, role, display name, app session token and expiry, encrypted with Android Keystore. It contains neither the phone number nor a password. The experimental server identifies accounts with a keyed phone hash.

### Local real-service smoke check (2026-10-29)

- The local server's Fusion token endpoint returned HTTP 200 after the RAM user was granted `AliyunDypnsFullAccess`.
- The user reports that a real SMS arrived on their iPhone, the code was entered and submitted from the Android emulator, and the new user selected a role.
- This confirms the reported first-time SMS registration/role-selection path against the local trial server. No phone number, SMS code, AccessKey, or account identifier is recorded here.
- It does not verify carrier one-tap on an emulator, an existing-account sign-in after restart/reinstall, incorrect/expired codes, registration-ticket expiry/replay, multi-device database persistence, PostgreSQL, public HTTPS deployment, or authorization on protected business endpoints. Those require separate checks before production.

Sign-out clears the local encrypted session; the server JWT remains valid until its expiration. Other feature repositories are not yet wired to this session, so entering a home screen remains a preview and does not demonstrate authenticated business operations.

### S1 lifecycle correction and integration boundary — 2026-10-30

The lead/S1 has added generation fencing at the actual repository completion/private-file boundary. One attempt follows the verified response through first registration; a successful save consumes it. Logout invalidates older attempts before filesystem I/O, and same-profile store instances serialize save/clear. A late/duplicate response cannot re-save an obsolete account even if its ViewModel callback is ignored.

Clear now verifies that AtomicFile's base/backup/new files were removed. Failed deletion retains an in-process clear intent, blocks old restores/completions and retries durable clear before further restoration/authentication. The UI clears account presentation but explicitly reports that logout could not be saved. If the process dies before a failed deletion can be retried, durable logout cannot be promised; surface/retry that failure. This is local token removal, not server revocation.

S1 confirms these existing integration constraints for C1/S2:

- Server-returned stable account `userId` and first registered role remain authoritative. `UserView` is already a type alias of existing `User`; no second user-response shape is needed merely to map this UI account into the common model.
- Installation identity is a separate stable, environment-scoped device identity supplied by S1. Never substitute `userId` for it, trust a client-selected actor, or automatically merge old anonymous data. C1's SDK/role UI remains feature-owned; public business repositories consume the verified common session.
- Use the common session's original attempt/lease for auth completion, HTTP credentials, and stale-401 invalidation. An old 401 must not clear a newer login. Temporary 503/429 preserves the local session and original recovery evidence; it is not proof that an account expired.
- The production phone verification/registration ticket and JWT must carry the agreed account/installation binding into the existing P0 actor resolver. S1 accepts the separate installation model; S2 still needs to record exact request/claim/storage/error details for joint Android/Server review before those wire fields are implemented/frozen. This patch does not invent that contract or claim that `/schedules` proves phone access to pairing/photo/plan.

Verification added here uses fictional verified-response inputs through the real repository completion and encrypted Keystore/file path. It checks late response after logout, old registration/duplicate completion, real filesystem deletion failure/recovery, environment isolation and absence of plaintext token bytes. It does not contact Alibaba or send SMS. Final counts and checks are recorded in the PR; original provider-trial evidence remains attributed to its author.

## Local flow preview

Build the debug APK, then launch the explicitly marked local preview:

```powershell
adb shell am start -n com.group33.medicationreminder/.SharedLoginActivity --ez com.group33.medicationreminder.LOCAL_AUTH_DEMO true
```

The preview accepts any syntactically valid phone number and the fixed local code `123456`; no message is sent. First sign-in leads to role selection. Later sign-ins to that number reuse the selected role while the app process remains alive.

## Real-service emulator trial

This private debug-only setup uses the emulator's `10.0.2.2` route to a server bound to host loopback. The app asks the tester to accept a local-test data disclosure before initializing Alibaba's SDK. It is not a published app privacy policy or a release configuration.

```powershell
.\gradlew.bat :android-app:assembleDebug :server:bootJar '-PphoneAuthBaseUrl=http://10.0.2.2:8080' '-PphoneAuthLocalTrial=true'
.\scripts\start_phone_auth_trial.ps1
```

The trial script uses the shared Fusion scheme code configured in the script and prompts each tester for their RAM AccessKey ID and secret. AccessKey values are not printed or saved. Keep them out of commits and chat. Local database, account-hash and JWT keys are saved using Windows user-bound protection under `%LOCALAPPDATA%\Group33PhoneAuthTrial`; preserve them to retain access to test accounts. Install the debug APK on the emulator and launch normally, without the local-demo intent. This scheme is bound to the debug signing fingerprint configured in the script.

An iPhone or other phone can receive the actual SMS; the code can be entered in the emulator. Alibaba does not support carrier one-tap number authentication on an emulator, and whether the Fusion SDK reaches its SMS fallback in this emulator must be established with a live trial. Sending real SMS may incur provider charges.

For a USB-connected Android tablet, keep the local server running and use `scripts/install_phone_auth_tablet.ps1 -Serial <ADB serial>`. The script builds with the tablet loopback URL, installs the APK, restores `adb reverse`, and compares the installed APK hash with the build. A plain `assembleDebug` build omits the local trial URL and must not be used for this tablet trial. Re-run the install script after every source change or tablet reboot (the reverse tunnel may be lost).
