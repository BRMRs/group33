# C1 phone entry and common-session integration candidate

Refs #10 / #49, existing PR #88; owner tianiridescent (C1). The tested client slice
`18e5a81` is based on main `dc1e2fb`. The update merges it into the original
`feature/10-c1-login-flow` history without a force push and targets main.

## Preserved sources and scope

The SDK bridge, phone client and login/role UI reuse #88 at
`0a2291d86f06a382f4f28db521204333acf45f0c`. The existing SDK verification callback,
SMS reentry/cancellation handling, server-verified account/role and client error
semantics retain their original attribution. The AAR is copied byte-for-byte from
that revision, not downloaded or upgraded. The update also retains its original
`umeng-common-9.5.6.aar` and `umeng-asms-v1.8.0.aar` runtime dependencies, all in
Debug only. The initial client-only candidate omitted those two AARs; the existing
PR integration audit corrected that omission before publication and added a
non-initializing runtime class-loading check.

The host now supplies the exact S1 PrivateSessionStore instance to the phone
adapter and DebugChainRepository. The phone adapter does not include the legacy
PhoneSessionStore, persist provider proof/phone/password, or decode JWTs in the UI.
An opaque authentication attempt follows verification through registration;
completion, logout, generation, expiry and old-401 fencing use the common store.
The added current-attempt query guards registration before HTTP and changes no
session disk format.

The IME/system-bar handling and keyboard regression harness reuse #117 at
`247b432ca154231bb716c899275688c7efb46184`, adapted to SDK-owned SMS entry and the
inline feedback cards. That PR remains independent; this record does not merge it.

A configured phone Debug profile disables anonymous login at both the controls
and repository method. Default anonymous Debug composition remains available for
the existing main integration. The two profiles use distinct session environments
and namespaces, with no automatic history migration. No raw credentials enter
screen state or a new UI-owned account store. Shared entry routing follows the
common AuthRepository, not a preview-home shortcut or a local-demo intent extra.

The client retains the existing trial token/verify/register request fields and
server-returned lifetime. Proposed installation/ticket/claim fields from #96 are
not invented or frozen here. The existing PR #88 experimental Server/provider,
phone account store, routes and tests are retained, disabled by default. Their
logic is unchanged from `0a2291d`; the build/configuration combines their Aliyun/H2
dependencies and Fusion settings with main's existing PostgreSQL/business settings.
No new Server/Shared wire, schema or production phone identity binding is introduced.
Required review includes non-author C1/UI,
S1 common-session/infrastructure and S2 authentication/authorization perspectives.

## Configuration and provider boundary

Authentication and business URLs must be the same origin. The optional team-test
build task validates explicit HTTPS/policy settings, registered package name and
the actual signing certificate MD5. An optional team keystore comes from private
environment variables; no key/password is committed. These checks are not network
reachability or provider acceptance tests.

SDK code and AAR are Debug-only. SDK activity declarations and inherited SDK
permissions remain in the Debug manifest/merge; Release does not initialize or
package any of the three AARs. Debug retains the original provider/Umeng permissions,
including AD_ID; no provider component initializes from these AAR manifests.
The existing SDK requests are started only after user
consent, not by application startup or automated fixture tests.

See [team instructions and missing service handoff](../PHONE_TEAM_TEST.md).

Repository refresh discovered the separate deployment branch
`chore/16-render-main-deployment` (`81da482`) documenting
`https://group33-pr110-preview.onrender.com`. Its health endpoint returned 200 on
2026-11-08. This is an in-memory anonymous business preview, not a secret-entry
webpage or evidence that phone routes/actor binding are deployed. The deployment
branch is not included in this authentication PR; main remains `dc1e2fb`.

## Verification record

2026-11-08, Windows/JDK 17, offline Gradle with one worker and a 1 GiB heap:

- Debug JVM: 143 tests, zero failures/errors. Release JVM: 133, zero failures/errors.
  Shared: 18, zero failures/errors (unchanged reports, task up-to-date).
- Debug/Release and AndroidTest APK builds pass; Debug Lint has zero errors and
  41 warnings. These counts describe the client-only preparation; Server checks
  for the original-trial-preserving PR update are recorded separately below.
- Five actual Gradle configuration-task executions: absent service, mismatched
  origin, wrong certificate and wrong package fail with the expected diagnostic;
  the matching public certificate/HTTPS fixture configuration passes. The fixture
  host is `.invalid`; this is deliberately not a reachability test.
- Release APK DEX has no Alibaba Fusion/gatewayauth classes or native libraries;
  merged Release permissions contain POST_NOTIFICATIONS and the existing app-local
  dynamic-receiver permission only. Debug includes the provider's permissions.

Physical Mi Pad 4 Plus, Android 8.1/API27, isolated `phone88` audit package:
the configured local-trial profile reports OK (36 tests), 47.429 seconds: 35
passing cases and one intentionally skipped default-profile-only case. This
includes six bilingual keyboard/reachable-submit cases, two entry/navigation
cases, six actual common-store integration cases, five loopback transport cases,
two configured-entry cases and 14 inherited PrivateSessionStore cases. No remote
CI, supplier call, SMS or real phone account success is claimed. API35/200% was
not rerun for this integrated phone UI; prior #117 results are separate evidence.

The first API27 run exposed a test synchronization gap: navigation clicked while
session restore still disabled the button, and attempted to scroll a formerly
GONE control before layout. The harness now waits for enabled, shown, laid-out
controls with a bounded main-loop condition. The failing logs are retained outside
Git; the assertions are not removed or relaxed to accept an absent form.
Keyboard tests also wait for the existing smooth-scroll transition before tapping
Back. The default anonymous profile auto-focuses its nickname field and can show
the IME after startup; its navigation harness explicitly closes that keyboard and
settles the scroll before tapping the alternate-method control.

Final default-profile navigation/SMS reentry rerun: 3/3 pass, 8.938 seconds
(`verified-default-final.log`). This uses only profile-independent methods from
the updated audit harness against the saved default APK. The original default
run's common-store/HTTP/store and forged-demo-extra cases already passed; the
first two navigation failures are retained as failures and linked to this corrected
rerun rather than presented as an uninterrupted clean run. Final harness build and
Debug Lint pass. The two temporary audit packages were uninstalled; the original
`review113` application/data remain and its launcher was restored.

Physical API27 entry and cancelled-consent screenshots (no supplier invocation):

| State | English | Chinese |
| --- | --- | --- |
| Configured Debug entry | [image](phone-main/profile-entry-en.png) | [image](phone-main/profile-entry-zh.png) |
| Local-trial consent, cancelled | [image](phone-main/profile-consent-en.png) | [image](phone-main/profile-consent-zh.png) |
| Actual IME and reachable submit | [password](phone-main/keyboard-ENGLISH-password.png) | [SMS](phone-main/keyboard-SIMPLIFIED_CHINESE-sms.png) |

Initial client-only audit APK SHA-256 (external suffix, missing the two original
Umeng runtime dependencies; retained as a regression baseline, not a team SDK build):
`71c73d47d353040acf6aca85b106422f3eb38d2efe6d3cd21ce3106b127aabd5`.
Raw build/configuration/device reports and six original IME captures are retained
outside Git under `D:/w/review-20261108/phone-main/`.

The `phone88` application ID suffix is an external audit init script setting only;
it is not the registered supplier package and must not be used to claim real SDK
acceptance. The normal team build checks the actual registered package/certificate.
Original `review113` pairing application and data are preserved.

### Existing PR #88 update verification — 2026-11-08

The full update preserves the original PR as an ancestor and main `dc1e2fb` as an
ancestor; no force push or replacement PR is required. The two original Umeng
AARs are restored in Debug, and the server trial implementation remains disabled
by default. The tablet install script now uses the certificate-checked build task
and the standard ADB port 5037; its PowerShell syntax parses successfully.

- Final build: Debug 143, Release 133, Shared 18 (up-to-date), targeted Server auth
  7 JVM cases, all with zero failures/errors/skips. Debug/Release/AndroidTest APKs
  and Server bootJar build successfully. Debug Lint: zero errors, 42 warnings.
  The original Umeng bytecode emits D8 stack-map warnings; it is not upgraded or
  suppressed here. This targeted Windows run does not claim a full Server/Gate 1
  pass; the unmodified POSIX photo tests still require Linux CI.
- The existing phone-registration MockMvc case now also verifies that a signed
  phone token receives 401 from actual care-relationship and pairing-code routes.
  Success on the old `/schedules` scaffold does not grant business authorization.
- API27 negative control: the new non-initializing dependency-loading case fails
  on the saved initial client-only APK with missing `UMConfigure`, reproducing the
  omitted dependency. No provider initialization or SMS is used.
- Complete APK: OK (37 reported cases), 48.046 seconds: 36 pass and one
  intentionally skipped default-profile-only case. Includes the three provider
  runtime classes loaded without initialization, two configured entry cases,
  bilingual navigation, all six real-IME cases, common session/HTTP/store cases.
  Six final IME captures are retained outside Git; the two linked IME images were
  refreshed. Entry/consent UI source is unchanged from the earlier captures.
- Final Release DEX excludes Fusion, gatewayauth and Umeng classes and native
  libraries; merged permissions remain POST_NOTIFICATIONS and the app-local
  dynamic-receiver permission only. Debug retains the original SDK permissions.
- The two `phone88` packages were uninstalled after testing; `review113` data and
  the original Caregiver foreground screen remain intact.

Final isolated audit APK SHA-256:
`7d1fe01877f9e579b716e5bdec6329a7ae33ae9c03bffa6242fa76ad55914867`.
Logs: `pr88-final-checks.log`, `pr88-final-unit-results.json`,
`pr88-missing-dependencies-counterexample.log`, `pr88-complete-device-tests.log`
under the external audit directory above. None demonstrates real supplier sign-in,
public phone service deployment or phone-authorized business access.

The session tests use fictional verified responses with the actual Keystore and
S1 store; business HTTP tests use explicit response fixtures. Transport tests use
dedicated local ServerSocket fixtures. These test client composition and fencing,
not S2's unimplemented phone-to-business actor binding.

Known external acceptance remains: D1-D4 joint decision, deployed phone service,
bounded supplier verification, production persistence/account-installation binding,
team signing/HTTPS configuration, actual SDK one-tap/SMS, restart and business
authorization on that same environment. A reachable main business service alone
does not prove any of these.

## Rollback

Revert this client/composition slice. No migration or account-history merge is
performed. Switching back to the anonymous build reuses its original separate
profile; it does not transfer pending commands to another account/environment.
