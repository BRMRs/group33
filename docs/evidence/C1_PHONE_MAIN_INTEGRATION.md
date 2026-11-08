# C1 phone entry and common-session integration candidate

Refs #10 / #49; owner tianiridescent (C1). Local branch
`feature/10-c1-phone-main-integration`, from main `dc1e2fb`.

## Preserved sources and scope

The SDK bridge, phone client and login/role UI reuse #88 at
`0a2291d86f06a382f4f28db521204333acf45f0c`. The existing SDK verification callback,
SMS reentry/cancellation handling, server-verified account/role and client error
semantics retain their original attribution. The AAR is copied byte-for-byte from
that revision, not downloaded or upgraded.

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
not invented or frozen here. No Server/Shared source, schema or production phone
identity binding changes are included. Required review includes non-author C1/UI,
S1 common-session/infrastructure and S2 authentication/authorization perspectives.

## Configuration and provider boundary

Authentication and business URLs must be the same origin. The optional team-test
build task validates explicit HTTPS/policy settings, registered package name and
the actual signing certificate MD5. An optional team keystore comes from private
environment variables; no key/password is committed. These checks are not network
reachability or provider acceptance tests.

SDK code and AAR are Debug-only. SDK activity declarations and inherited SDK
permissions remain in the Debug manifest/merge; Release does not initialize or
package the provider. The existing SDK requests are started only after user
consent, not by application startup or automated fixture tests.

See [team instructions and missing service handoff](../PHONE_TEAM_TEST.md).

## Verification record

2026-11-08, Windows/JDK 17, offline Gradle with one worker and a 1 GiB heap:

- Debug JVM: 143 tests, zero failures/errors. Release JVM: 133, zero failures/errors.
  Shared: 18, zero failures/errors (unchanged reports, task up-to-date).
- Debug/Release and AndroidTest APK builds pass; Debug Lint has zero errors and
  41 warnings. No Server source changes or full Server/Gate 1 pass is claimed.
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

Audit APK SHA-256 (external suffix, not a supplier-registered package):
`71c73d47d353040acf6aca85b106422f3eb38d2efe6d3cd21ce3106b127aabd5`.
Raw build/configuration/device reports and six original IME captures are retained
outside Git under `D:/w/review-20261108/phone-main/`.

The `phone88` application ID suffix is an external audit init script setting only;
it is not the registered supplier package and must not be used to claim real SDK
acceptance. The normal team build checks the actual registered package/certificate.
Original `review113` pairing application and data are preserved.

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
