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

## Privacy-policy configuration validation — 2026-11-19

Local revision: base `66c67de8b11c57e8c7b759d6065aa1ca5929d210` plus the
uncommitted C1 policy-validation slice. This is not a published revision or a
verified team APK. The existing clean checkout was switched to the existing
login branch; no merge, rebase, dependency upgrade or service deployment occurred.

Previously, a prefix check accepted `https:///privacy`. Build configuration and
`verifyPhoneTestConfiguration` now require a parseable HTTPS URI with a host,
without embedded credentials, invalid ports, control characters or BuildConfig
escape characters. Login composition and the consent entry use the same runtime
validator. Empty configuration remains allowed for the ordinary unconfigured
build, but not for a non-local team phone-test configuration. The existing
explicit loopback trial exception is retained. This checks syntax only, not
ownership, availability or suitability of an actual privacy policy.

Commands run on Windows/JDK 17, with `--offline --no-daemon --max-workers=1
--no-parallel --console=plain`:

```text
./gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease
./gradlew.bat help -PphoneAuthPrivacyPolicyUrl=https://example.test/privacy?lang=zh#terms
./gradlew.bat help -PphoneAuthPrivacyPolicyUrl=https:///privacy
./gradlew.bat help -PphoneAuthPrivacyPolicyUrl=https://name:password@example.test/privacy
./gradlew.bat :android-app:verifyPhoneTestConfiguration -PphoneAuthBaseUrl=https://example.test
python scripts/check_repository_policy.py
git diff --check
```

- Main verification: BUILD SUCCESSFUL in 12m 5s; Debug 147 and Release 137
  tests, zero failures/errors/skips. Four new tests per variant cover valid
  policy paths/queries/fragments, missing/malformed host and schemes, credentials
  and ports, and control/escape characters. Lint: zero errors, 45 warnings.
  Debug and Release APK builds passed; existing provider D8 warnings remain.
- Four actual Gradle configuration checks passed their expected outcomes: valid
  policy accepted; missing host and embedded credentials rejected at project
  configuration; missing policy rejected by the team verification task before
  any registered-certificate claim. These negative commands intentionally return
  nonzero, with the expected validation messages.
- Raw local evidence is retained in the primary checkout's ignored
  `.git/c1-local-recovery-20261117/`: `login-policy-build-01.log`,
  `login-policy-build-cases.json` and the four `login-policy-*.log` case logs.
- No UI layout, user-facing wording or keyboard behavior changed. No additional
  human UI acceptance is requested for this syntax-only slice. No APK was
  installed, provider initialized, SMS sent, secret read or real phone login
  attempted. No Server/shared code changed; full Server tests and fresh Linux
  CI were not run for this unpublished slice.

The S1 D1–D4 decisions recorded in #118 are acknowledged; their recording is not
proof that the corresponding Server implementation, independent security review,
phone-enabled HTTPS environment, actual policy URL or registered signing profile
has been delivered. Those gates and final main integration remain separate.
Rollback is to revert this validation slice; no stored-data migration is needed.

## Main synchronization and integration regression — 2026-11-19

Local candidate: published #88 head `69798f3c4a8510fd6146a2538c9b705026bdcac7`
plus main `bbe4997034ecb837d2093f4e73267cc530976f29`, resolved locally with
`git merge --no-commit --no-ff origin/main`. No integration commit or push has
been made. Inherited main changes retain their original ownership.

Seven conflicts were resolved without discarding either feature: contribution
history, shared-login keyboard tests, Debug manifest, Debug Application,
Debug Repository composition, artifact checksums and Server application config.
The phone profile retains its shared private session and disabled anonymous
fallback; main's dose-event/dashboard interfaces and Elder reminder startup are
retained. The Server fusion and dose-group configuration blocks coexist with
their original disabled defaults; no route, DTO, identity binding or schema is
invented. The privacy URL validation from `69798f3` remains intact.

Two new real-store/device regressions cover the merged dashboard path: a current
401 clears the same phone/common session without anonymous fallback; an old
dashboard 401 cannot clear a replacement phone session. These use fictional
verified responses and local transport fixtures, not actual phone authentication.

### Windows and Linux verification

Actual Windows command, followed by final lint after the test synchronization fix:

```text
./gradlew.bat test :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --no-daemon --max-workers=1 --no-parallel --continue --console=plain
./gradlew.bat :android-app:lintDebug --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

Android Debug 156 / Release 145 tests pass, with zero failures/errors/skips;
Shared 18 pass (Gradle cache). Debug, Release and AndroidTest APK tasks succeed.
Final lint passes with zero errors / 58 warnings. The full Windows command
**fails**: Server reports 635 tests / 311 failures. Failure samples include
unsupported POSIX permissions, private-photo-store initialization and dependent
Spring contexts; the two child-JVM assertions also report photo-store UNAVAILABLE.
No private-storage security check was weakened to obtain a Windows pass.

The same source checkout was tested directly from WSL Ubuntu/JDK 17, without a
source copy. Actual Linux Gradle invocation (from that checkout):

```text
env GRADLE_RO_DEP_CACHE=/root/.cache/group33-pr88-linux/ro-cache java -Xmx64m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain -PserverOnly :server:test :shared:test :server:bootJar :server:schemaMigrationDist -I /mnt/d/learning/xjtlu/year4/CSI301/CSI301_Group_Project/code/group33/.git/c1-local-recovery-20261117/login-linux.init.gradle --project-cache-dir /root/.cache/group33-pr88-linux/project-cache --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

Linux **BUILD SUCCESSFUL in 3m 36s**: Server 635 tests, zero
failures/errors/skips; Shared 18 pass (Gradle cache); bootJar and
schemaMigrationDist succeed. The init script only relocates build outputs:

```groovy
allprojects {
    layout.buildDirectory.set(new File(System.getProperty('user.home'), '.cache/group33-pr88-linux/' + project.name))
}
```

The read-only dependency snapshot was seeded from the successful Windows
Gradle 8.9 module cache, excluding lock/gc files, following
[Gradle's cache-sharing guidance](https://docs.gradle.org/current/userguide/dependency_caching.html).
The final Linux run is offline and uses the original project repositories and
dependency versions. Earlier Linux attempts are retained: run 01 failed for
missing offline dependencies; online runs 02/03 were explicitly stopped while
dependency resolution made no task progress. Run 03 only changed official
repository query order/timeouts through a temporary init script; it is not
test-passing evidence. Only run 04 supplies the Linux pass above.

### Xiaomi API 27 regression

Both configurations use the isolated application ID
`com.group33.medicationreminder.c1dev88`, verified from APK badging before install.
The original app and pairing-test app/data are preserved; global device settings
were not changed. The local-trial profile only adds
`-PphoneAuthBaseUrl=http://127.0.0.1:18084 -PphoneAuthLocalTrial=true`; there is no
provider acceptance or SMS. App source/package namespace remains unchanged.

Build tasks are `:android-app:assembleDebug :android-app:assembleDebugAndroidTest`
with the isolated applicationId init and the common offline/single-worker flags.
Instrumentation uses `adb -s 9c69cae2 shell am instrument -w -r -e class <classes>
com.group33.medicationreminder.c1dev88.test/androidx.test.runner.AndroidJUnitRunner`.
The class list is CommonPhoneSessionIntegrationTest, PhoneTransportIntegrationTest,
PhoneEntryIntegrationTest, PrivateSessionStoreTest, SessionProcessRecoveryTest,
SharedLoginNavigationTest and SharedLoginKeyboardTest (normal package prefixes).

- Unconfigured: 40 reported, 39 passed / one not-applicable local-trial case.
- Explicit loopback trial: 40 reported, 39 passed / one not-applicable
  unconfigured case. Total: 78 pass / two applicability skips / zero failures.
- The initial unconfigured run had one failure: the forged-extra entry test
  checked the inline feedback before it was drawn. The test now settles the
  scroll and waits for the actual feedback layout before the unchanged visible
  feedback/no-session assertions. Its original failure log is retained; the
  full two-profile suite was rerun, not just the failed method.
- Both profiles pass the added dashboard/common-session 401 regressions and
  all six real-IME bilingual SMS/password/reset submit/back cases.

Inspected evidence: [unconfigured English](phone-main/main-sync-unconfigured-en-api27.png),
[unconfigured Chinese](phone-main/main-sync-unconfigured-zh-api27.png),
[trial English](phone-main/main-sync-trial-en-api27.png) and
[trial Chinese](phone-main/main-sync-trial-zh-api27.png).
The phone-input/submit controls remain above the real keyboard. Anonymous Debug
controls exist only in the unconfigured profile; the phone trial omits them.

APK SHA-256 (application / instrumentation):

- Unconfigured: `8bf03e629daa8c997f590e1399de01c8b859b93932a12ad1c637302d650b827d`
  / `f0291465161fbbac65c6ae8f06f458bd5efe5e8c50859e5ba8c48495a3595e61`.
- Trial: `ede256a44d5c2941467dc1fece8f02378af95c58c3dfa91903da62c63da2965a`
  / `498ac3b8183216ad139a6fb03fd9c02f7a3464eaf80bbb55b62e3266109a4d40`.

Raw logs are retained under the primary checkout's ignored
`.git/c1-local-recovery-20261117/`: `login-main-build-01.log`,
`login-main-lint-final.log`, `login-linux-build-01` through `04.log`, the initial
`login-sync-unconfigured-instrumentation.log`, and `login-sync-device-02/`
(both build commands, APK hashes, final instrumentation and screenshots).

No new UI interaction or layout was introduced by this synchronization; no
additional manual UI acceptance is requested. API35/200%-font/TalkBack and
final team screen composition remain outside this tablet-only rerun. Actual
phone-enabled service, accepted wire implementation, provider proof boundaries,
team policy/signature config and real one-tap/SMS/business authorization remain
the #118 gates. This is not full #10/#49 completion, a verified team APK, or
teammate approval. #88 stays Draft until its implementation/review scope is
ready; CI for a future integration commit is still pending publication approval.

## Registration response races - 2026-11-19 local follow-up

Base: published #88 `498c21cbedf543f7632348b3736c5f3b25101f28`.
Only `PhoneRepositoryCommonSessionTest` and this evidence/its checksum entries
change. No production source, HTTP/DTO, session implementation or UI changes.

Two deterministic tests suspend a registration inside the fake blocking Server
client, then release its successful response after either sign-out or a new
authentication attempt has installed a replacement registration ticket:

- Sign-out rejects the old success as CODE_EXPIRED, saves no session, and a
  further registration cannot call HTTP with the old ticket.
- A new attempt also rejects the old success without consuming its replacement
  ticket; explicit registration uses that replacement ticket and saves only the
  replacement account. This covers the compare-and-set ticket cleanup as well
  as the session attempt fence.

The latch has a failure-only ten-second bound and is released in `finally`;
ordering uses an explicit completion signal, not sleeps or public-network timing.
Fixtures are fictional verified responses, not provider authentication evidence.

Executed with JDK 17, offline, one worker and no parallel projects:

```text
gradlew.bat :android-app:testDebugUnitTest --tests com.group33.medicationreminder.auth.PhoneRepositoryCommonSessionTest --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

Result: BUILD SUCCESSFUL; 12 tests, zero failures/errors/skips, including both
new cases. Existing production behavior already passes; no logic fix is claimed.
The complete Debug check command also passed (2m57s):

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:lintDebug :android-app:assembleDebug --offline --no-daemon --max-workers=1 --no-parallel --console=plain
python scripts/check_repository_policy.py
git diff --check
```

Debug JVM: 158 tests, zero failures/errors/skips. Lint and Debug APK succeeded;
the APK production sources are unchanged. Server/Shared and Release tests were
not rerun for this Debug-test-only addition. This follow-up
has not been committed/pushed and has no CI result. Prior device, Server and
Release results above belong to the prior integration, not this execution.
No additional manual/device acceptance is needed for these JVM-test-only changes;
real service/configuration and formal wire implementation still depend on #118.

## Registration ticket publication ordering - 2026-11-20 local follow-up

Base: #88 `51d9d718a19ee78616a79d830d7af5127e1cb513`. A deterministic regression
reproduced a separate check/publication race: the old attempt's `isCurrent`
result was true, then a replacement attempt published its ticket while that
old check was suspended. Resuming the old response overwrote the replacement
ticket. Explicit registration then failed instead of using the new ticket.
The targeted JVM test failed on the unmodified Repository at its SignedIn
assertion; this was a functional failure, not a timeout.

The Repository now serializes beginAuthentication, new-ticket current-check
and publication, and signOut with a coroutine Mutex. The lock covers only
local attempt/ticket/store work. Provider/HTTP calls and registration completion
remain outside it; PrivateSessionStore still fences persisted session writes.
No HTTP/DTO, auth binding, anonymous fallback, Shared/Server or UI change is made.

The JVM regression coordinates the exact interleaving with deferred signals and
UNDISPATCHED starts, without sleeps. The corresponding device regression uses
the real CommonPhoneSession/PrivateSessionStore with one gated current-check,
then verifies the replacement ticket and account, including reopening the store.
Its Server response is fictional; it never calls the SDK or provider.

The user subsequently reconnected the physical Xiaomi and authorized execution;
ADB reports `9c69cae2`, API27, unlocked. Tests use only the existing isolated
`com.group33.medicationreminder.c1dev88` package. Building its test APK alone
is not execution or real one-tap/SMS acceptance. No publication is authorized
for this follow-up.

The baseline failure log/XML are retained under the primary checkout's ignored
`.git/c1-local-recovery-20261117/registration-publication-before.log` and
`registration-publication-before.xml`.

Final no-device validation (same JDK17/offline/single-worker flags as above):

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

BUILD SUCCESSFUL in 9m59s. Debug 159 (including all 13 Repository/session
boundary cases), Release 145; zero failures/errors/skips. Debug/Release and
instrumentation APKs compiled. The new JVM case fails before the fix and passes
after it. Lint has zero errors and 58 warnings. Device results are recorded
separately after execution.

Linux same-worktree Server/Shared command uses the previously documented isolated
build output/project cache and read-only dependency cache, with no repository or
dependency changes:

```text
env GRADLE_RO_DEP_CACHE=/root/.cache/group33-pr88-linux/ro-cache java -Xmx64m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain -PserverOnly :server:test :shared:test :server:bootJar :server:schemaMigrationDist -I /mnt/d/learning/xjtlu/year4/CSI301/CSI301_Group_Project/code/group33/.git/c1-local-recovery-20261117/login-linux.init.gradle --project-cache-dir /root/.cache/group33-pr88-linux/project-cache --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

BUILD SUCCESSFUL in 2m19s; Server 635, zero failures/errors/skips, actually
executed. Shared 18 passing reports reused as UP-TO-DATE; bootJar and migration
distribution succeeded. This avoids the previously documented Windows POSIX
private-storage limitation rather than relaxing the storage tests.

Logs: `registration-publication-after.log`, `registration-publication-linux.log`
under the same ignored evidence directory.

Physical execution completed using `run_login_sync_device.py --run
login-sync-device-03` from that directory. It builds with the existing isolated
applicationId init, validates app/test package IDs with aapt before installation,
and executes the same seven instrumentation classes listed above. Both profiles
include `suspended_old_ticket_check_cannot_replace_new_ticket_in_real_store`:

- Unconfigured: 41 reported, 40 pass, one configuration-applicability skip.
- Explicit loopback trial: 41 reported, 40 pass, one configuration-applicability
  skip. Total 80 pass / two applicability skips / zero failures. The new
  real-store regression passed in both; neither skip is that regression.
- All six bilingual real-IME submit/back cases pass in each profile. Inspected
  all four final keyboard images; inputs/submit remain above the real keyboard.
  No new layout or interaction was introduced; no extra manual UI acceptance is
  requested for this concurrency fix. Real provider login remains a #118 gate.

APK SHA-256 (application / instrumentation):

- Unconfigured: `f5a9bfc2f2ec92b3ca7cd4ecfdb352025541e3e9d4a8f2b61cfa1e768e470c17`
  / `7caf3e5ea2b0f137ab46a634c0872605c98d61e4e1f4286b7468697fd4af9c27`.
- Trial: `9b3927e0abe7da91aedf24c83a110543a2d27fccb317696cff4e065809937338`
  / `8a0b61d7d1f863dc3f1dd9002f5626e31de9c6fce142e3f32e4369520161f441`.

Raw commands, profile results, images and instrumentation logs are in
`login-sync-device-03/`; original XML reports are archived in
`registration-publication-windows-reports.zip` and
`registration-publication-linux-reports.zip`. Tested source digests are in
`registration-publication-validation.json` and still match the final sources.
ADB initially lost the debug transport despite Windows listing the USB device;
after reconnect it reported device/unlocked and both suites completed. Only the
isolated package was installed/force-stopped; no original application data or
global device settings were changed. No commit, push, CI or human approval for
this follow-up is claimed. Reverting the local Repository mutex and added
regressions is source-only rollback; no schema or persisted format was changed.

## 2026-11-22 latest-main integration

The previously approved session correction was published as #88 `03846a9`.
Main subsequently advanced from `bbe4997` to `b5acc05`; #88 again reported a
merge conflict. This local normal merge retains both sides of the Debug manifest
and dimension resources, plus the main evidence-component implementation/tests.
All nine existing login/SDK activities and seven existing dimensions retain
their attributes/values. The only new activity is the non-exported main event-row
test host. Auth/session sources, Android build configuration, Server and Shared
are byte-identical to #88 HEAD; no API, production registration or persisted
format is changed. Contribution/artifact records are merged, not discarded.

Source merge tree before evidence edits: `b1254b9ce8778744d5774f2c48275874104db3bb`.
Actual Windows/JDK17 command, in the existing reused work area:

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest :shared:test -I ../group33/.git/c1-local-recovery-20261117/login-isolated.init.gradle --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

BUILD SUCCESSFUL in 7m26s. Debug164/Release150 actually executed with zero
failures/errors/skips. Lint: zero errors/46 warnings. Debug, unsigned Release
and instrumentation APKs assembled with the existing isolated applicationId
`com.group33.medicationreminder.c1dev88`; they were not installed. Shared was
UP-TO-DATE, not newly executed. Server was not rerun: the unchanged source keeps
the earlier Linux635 evidence above; no new Windows Server or cloud success is
claimed. Historical Windows POSIX and earlier provider/setup failures remain.

Exact merge checks, original XML/lint, log and matching APK hashes are retained
under `.git/c1-local-recovery-20261117/pr88-main-20261122-*`. No device/HTTP/provider
test was run this round; the preceding two-profile tablet results are historical.
Login UI values and auth/session implementation are unchanged, so this source
sync adds no manual C1 interaction check. Final real-service tests remain required.

C1 also consumed S2's #138 `18645bd` source/config handoff and independently
matched its seven source hashes. Configuration instructions now exist; PS1/PS2
production identity/authorization, actual controlled service, policy/signature
inputs and the verified team APK do not. This does not enable phone business
access or accept future request fields. Keep #88 Draft; publication of this local
main merge requires separate approval, then same-head CI and S1/S2 re-review.

Publication follow-up: the first CI run on `c5707612` stopped at checksums before
tests. The local manifest helper had incorrectly normalized CRLF bytes inside the
three binary AARs. Corrected their hashes using raw bytes, verified identical to
the tested SDK files and #88 `03846a9`; no dependency bytes/version changed.
The failed run `45012402460` remains visible. Subsequent validation checks binary
artifacts without text normalization; passing local builds above are unaffected.
