# C1 feedback date input handoff

Status: **partial implementation candidate; production date source and integration remain pending**. Refs [#15](https://github.com/BRMRs/group33/issues/15) / [#19](https://github.com/BRMRs/group33/issues/19). This document records C1's implementation, not an accepted date-source contract or human review.

## Baseline and implementation

The branch `feature/15-c1-feedback-date-input` starts from main `61140712193b816980045c52cc7276223731c268`. Pairing HTTP corrections are a separate delivery and are excluded.

Publication-time check: main advanced to `bbe4997034ecb837d2093f4e73267cc530976f29` via #116. On 2026-11-20 that main was merged locally without conflicts using `git merge --no-commit --no-ff origin/main`; no commit/push is authorized yet. Original checks below retain their original versions and are not evidence of a current-main device rerun.

`CaregiverHistoryViewModel.defaultRange(today)` already returns today minus 29 days through today, inclusively. The existing context and `ChainServices` do not provide an authoritative history date. ADR-0003's per-plan execution zones do not define one history-wide date for multiple plan zones or no plans. Existing query rules and the 90-date cap are unchanged.

- `CaregiverFeedbackRangeInput.kt` consumes an explicitly supplied date captured with its context. It rejects missing inputs and mismatched environment, installation, user, session, relationship or Elder, and rechecks context after the callback. It retains no previous range and must run on the host's serialized context thread.
- `CaregiverFeedbackDate` is an internal Android holder, not a Shared/wire DTO or an identity-verification claim. The adapter selects no clock, device zone, plan or endpoint. The normal entry does not register or call it yet.
- Eleven deterministic tests cover inclusive date boundaries, absent inputs, stale contexts, switching during resolution, restored sessions and replacement dates.

## Separate #124 combination candidate

2026-11-20 local main-sync check: `:android-app:testDebugUnitTest --tests
'*CaregiverFeedbackRangeInputTest' --offline --no-daemon --max-workers=1
--no-parallel --console=plain` passed, 11 tests / zero failures/errors/skips
(2m39s). Repository policy, both staged/unstaged diff checks and all 393
checkout-normalized artifact entries pass. Log/XML are retained in the primary
checkout's ignored `.git/c1-local-recovery-20261117/pr132-main-targeted.*`.
This only verifies the resolver on the locally merged main. It does not rerun
the historical #124 patch, fix the gaps below, or claim complete build/device
acceptance. The local merge is still uncommitted and has not been pushed.

**Historical findings:** [C3's review of acaea5d in PR #132](https://github.com/BRMRs/group33/pull/132) identified that the original patch permitted a legacy UTC 31-date query when the supplied date was unavailable. It also only reread dates on authorization-context changes. The local 2026-11-20 revision below addresses these default-path defects; earlier comparison/acceptance records describe the original patch, not the revised one. This candidate is still not ready for normal-entry adoption until the production producer and final integration are delivered; the latest #136 seam result is recorded below.

The next C1/C3 combination must preserve the explicit-date entry while showing
an unavailable default without launching a guessed-range query, and must update
or clear the default view when the supplied date changes or is withdrawn under
the same authorization context. This follows the recorded #15 rule; it does
not define a production time-zone stream or modify C3's host API unilaterally.

The **original** four-file patch published at `acaea5d` applied to pristine #124 `a377a7df0731d48fc052514b61d37955e014f06d`. Its SHA-256 was `5a706f23cfd842ccd44c46d7d9b630f3eff4e38a8a7964f3ca9343b8d37713a4`; it produced Git tree `4d62324626fabea997179e1cfbb20fc32d3f3c8b`. The historical behavior was:

1. `OnlineFeatureContent.kt`: propose a nullable date-range callback. If unavailable, clear C3 Hosts and retain the legacy dose panel; a subsequent explicit host rebind may consume a newly available date. Existing non-null callbacks remain usable.
2. `CaregiverFeedbackEntryTest.kt`: use fictional scoped dates with the real resolver. Add missing-date/rebind, restored-session/stale-date and reentry-without-date checks. Preserve the legacy query and require an additional exact 30-date query only for a valid supplied date.

The nullable callback is a C1/C3/S1 review candidate, not an accepted shared interface. No date producer, Activity registration, automatic notification or production refresh API is invented. Retaining the legacy panel does not accept its UTC-based range as the complete-history default. On adoption, update the recipient tree's own contribution/artifact records rather than copying main's entire manifest.

## 2026-11-20 current #136 combination

C3 delivered the nullable history entry in [#136](https://github.com/BRMRs/group33/pull/136)
at `a3d462e79c3a4ff2eaf0e7572754f09d5cc6395f`, based on #120 `14093a3`.
The [recorded handoff](https://github.com/BRMRs/group33/issues/15#issuecomment-7039063044)
allows `CaregiverHistoryHost.bind(..., range = null, ...)` and reusing the same
Host on same-context date updates/withdrawal. This resolves the earlier
missing-default entry and manual-draft seam; it does not deliver a production date source.

The existing [nine-file patch](../evidence/c1-feedback-date/124-date-consumer.patch)
now targets the reconstructed baseline below, **not pristine #124**. The filename
is retained so there is one maintained candidate artifact. C3 implementation files
remain dependency inputs, not C1-authored changes.

- Records directly reuse the existing #136 HistoryHost. An unavailable default
  mounts its blank explicit-date form and performs no guessed-range query.
- Calendar confirmation fills a draft; only the existing explicit Read action queries.
- Rebind the same Host with an updated/null source under the same trusted context.
  C3 retains manually applied dates and unfinished fields; context/session changes
  and leaving Records clear/detach old content and actions.
- Keep the old #124 FeedbackContent for overview only, so clearing it cannot destroy
  the separately owned history Host. The legacy UTC dose panel never supplies a fallback.
- No C3 implementation, HTTP/Shared DTO, producer, persistence or production registration
  is changed. The internal nullable callback remains a review candidate.

### Reproducible dependency baseline

Start an **alternate Git index** at #136 `021b39f5ea18950cf0d182dbff87d3103d51f6fd`, retaining its #120 dependencies.
For exactly the nine paths below, replace the index blob with the corresponding
blob from #124 `a377a7df0731d48fc052514b61d37955e014f06d` (`git ls-tree`, then
`git update-index --add --cacheinfo <mode> <blob> <path>`). Do not overlay other #124 files.

- `android-app/src/main/kotlin/com/group33/medicationreminder/integration/OnlineFeatureContent.kt`
- `android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverFeedbackContent.kt`
- `android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverOverviewHost.kt`
- `android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverOverviewView.kt`
- `android-app/src/main/res/layout/activity_caregiver_home.xml`
- `android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration/CaregiverFeedbackEntryTest.kt`
- `android-app/src/androidTest/kotlin/com/group33/medicationreminder/caregiver/CaregiverFeedbackContentTest.kt`
- `android-app/src/androidTest/kotlin/com/group33/medicationreminder/caregiver/CaregiverOverviewHostTest.kt`
- `android-app/src/androidTest/kotlin/com/group33/medicationreminder/caregiver/CaregiverNavigationUiTest.kt`

The resulting `git write-tree` must be `34574a35badfab6fdc9cbd4924dcd3dae5055102`. Apply the exported patch using
`git apply --cached`, then `git write-tree` must be `0aeea0422f7d98c3ed178487a65e94bbbf9e49f9`. This verification
does not modify the real index or working files. Build only in a checked clean
review area or with individually saved/restorable preimages; do not overwrite
another person's work. The local temporary overlay was restored after verification,
preserving the existing uncommitted main merge and C1 files.

Patch SHA-256: `b9ea67cd27b2a4a798627692efc8babba8faca53e0fce51d28de451e72730499`.

The nine paths comprise the previous six plus C1's `CaregiverHistorySyncRepository`,
its JVM test and the opt-in `CaregiverHistoryOnlineEntryTest`. No C3 source is changed.

### 2026-11-21 actual-entry sync correction

The user's manual attempt applied 2026-11-12 through 2026-11-26, but showed
ACCESS_UNKNOWN. The earlier prediction of an authorized empty result was incorrect.
The C3 Host correctly observes local events only. In the actual Debug Repository,
an unpopulated range has UNKNOWN access; changing dates does not itself sync it.
This escaped the earlier renderer tests because their fixtures already returned
authorized event caches. That manual attempt is not a passing read acceptance.

C1 now passes a context-owned composition adapter to the unchanged Host. Starting
a valid history subscription observes the existing event Repository and coordinates
one `CaregiverDashboardRepository.refresh(elderId, range)`. There is no read or sync
for a blank range or calendar draft. Repository observations remain the only UI
data/authorization source; a refresh receipt never creates authorized empty data.
Known REVOKED observations do not initiate a refresh. The Host cancels the flow
on range/context disposal; the adapter also checks the current trusted context
before starting work and before forwarding cache emissions. No retry loop, new
HTTP/Shared API, C3 listener replacement or production registration is introduced.
The existing session-bound transport/cache checks remain necessary for late HTTP
results. This is a local C1 adaptation of existing interfaces, not an accepted
replacement for the outstanding S1/S2 production date/session contracts.

New JVM tests: 6/6 passed; full Debug197/Release196 tests, lint (0 errors/33 warnings)
and Debug/Release/test builds passed in `pr132-sync-full-build-02.log` (8m10s).
The Android test alone was subsequently corrected/rebuilt in build03/build04;
no product source changed after the full checks. Final-source lint rerun05 passed
(2m44s; 0 errors/33 warnings). The pre-sync 54/54 and 60/60 results below are historical, not proof
of the actual sync path.

Actual API27 HTTP/Room/Activity regression (`pr132-sync-online-04`): with the exact
previous manual APK SHA `c03954285e98434ece75a36b58d3d0a57ac92442582f33d913b58e44d583bbf0`,
the new test reproduces a 20-second timeout waiting for authorized range cache.
It first refreshes the real care relationship, verifies no local document for
the selected range, edits dates without synchronizing, then clicks Read. This
expected counterexample failure is retained, not included in a green total.
The corrected app passes the same actual path in English and Chinese (1/1 each),
including authorized Room state, applied range and the visible empty-state text.
Only fictional anonymous identities and an empty local test Server are used;
this does not prove production/phone identity or a populated-history sync.

Actual new app SHA `85a0af8182335ee862925e77ad86fdba7b9def92fbfb7ed369ae192052483b92`;
final test SHA `452624a8f06b8a885dc0e60d51721658b63fba5494957f8e4f2514e3b9820f58`.
Earlier actual-entry captures remain in `pr132-sync-online-04`. The published
capture links below now show the latest Clock composition, with its own APK hashes.

Three earlier setup failures are retained: online01 had a JUnit non-void method
(fixed with explicit Unit); online02 waited for relationship authority before
performing the normal post-restoration relationship refresh; online03 failed
that refresh while this test's USB reverse mapping was absent. Restore only
tcp18087, verify the exact old installed APK and require the intended 20-second
range-cache counterexample in online04. None of these setup failures is relabeled
as a product reproduction or hidden as a passing result.

Final current-source API27 regression `pr132-136-date-device-sync-07` passed
27/27 in each language group, 54/54 total, zero failures/errors/skips. It includes
the new calendar-no-sync and explicit-read-sync assertions plus the existing
scroll, draft, session and Host boundary cases. This supplements the two real
HTTP/Room/Activity checks above; fixture results do not replace those checks.
Final repository policy, staged/unstaged diff checks and all 401 normalized
artifact hashes pass after restoring the temporary dependency sources. Original
staged merge tree `a3c3280a` and primary `gradle.properties` remain unchanged.
The user has not yet accepted this correction; no upload is authorized.

Earlier 2026-11-21 remote check: #136 head `b73603a` adds evidence only; its code
revision remains `a3d462e`. Main advanced from `bbe4997` to `b5acc05` by #48.
The candidate remains the exact reconstructed baseline above, not a claim of
having integrated the new main. Current-main integration remains a publication gate.

### Latest dependency and human acceptance — 2026-11-21

The user confirmed the corrected normal-entry date/read behavior with
“这次对了，继续推进”. This accepts that manual scope on the prior APK, not production
phone login, populated history or every final-combination criterion. It does not
authorize a commit, push, PR update or comment.

#136 subsequently advanced to `021b39f` with S1's display-month Clock injection
and one new Host device test. It freezes only the calendar's display month in
Fragment arguments, never supplies a selected/default read range. C1's nine
source files and exported patch are byte-identical to the manually accepted
revision; the dependency baseline and applied trees above now include this increment.
Latest-combination automated results are recorded separately from the earlier
54/54 and actual-entry acceptance below.

Latest combination on `021b39f`: full Debug197/Release196 JVM tests passed;
lint 0 errors/33 warnings; Debug, Release and instrumentation APKs passed
(`pr132-clock-full-build-01.log`, 7m26s). Actual local HTTP→Room→Activity checks
passed English and Chinese, one each, on previously unpopulated explicit ranges
(`pr132-clock-online-02`). The first clock runner stopped after locale setup due
to a missing Python class-name variable; no business check ran in that attempt.
The driver-only correction is retained, followed by the passing complete run.
Full device regression passed 28/28 in each language, **56/56 total**
(`pr132-136-date-device-clock-08`), including the new injected-February calendar
test. Zero failures/errors/skips in those final device groups. C1 source is
byte-identical to the manually accepted correction, so no additional human
interaction check is claimed or required solely for this display-month change.

Current APK SHA `aebac79d25f973e29598e22a69c2e5c6f750ba3d2b14e654d5737971ed43fd50`;
test SHA `dea22970d53a8c65a470f4952f793e1ddaedfd0dca76ed679306dad93e519991`.
Matching APKs, raw JVM XMLs and lint were frozen in `pr132-clock-binaries` before
the separate main-baseline build. Published actual-entry screenshots now show
this latest composition: [English](../evidence/c1-feedback-date/normal-read-en.png),
[Chinese](../evidence/c1-feedback-date/normal-read-zh-CN.png). Older originals
remain locally under `pr132-sync-online-04`; they are not overwritten or relabeled.

Main is `b5acc05` (#48). A separate alternate-index integration preview imports
its seven Android source paths exactly, merges CONTRIBUTIONS without losing C1's
entry, and resolves only the artifact-manifest conflict by recomputing all 407
index-blob hashes. It does not modify the real staged merge tree `a3c3280a` or
finish the existing uncommitted merge. The resulting source baseline was
checked separately from the C3 composition. Actual branch commits/main ancestry
remain a separately approved publication step; an alternate tree is not a merge.

The separate latest-main source baseline check has now passed: Debug146/Release145
JVM tests; lint 0 errors/32 warnings; Debug, Release and instrumentation APK builds
(`pr132-main-baseline-full-build-01.log`, 8m09s). The exact seven source blobs
were temporarily applied on the preserved branch source baseline and restored
byte-for-byte afterward. Raw XMLs and lint are in `pr132-main-baseline-test-results.zip`;
source/input checks are in `pr132-main-baseline-validation.json`. This is a branch
baseline build, not a device run of that baseline and not the 197/196 composition.
Both used the same six Gradle targets shown below, offline, max-workers=1;
the composition supplied the loopback debugServerUrl and manual applicationId,
while the branch build used only the applicationId isolation init script.
Actual local commands, run from `c1-review-pr119`:

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest -PdebugServerUrl=http://127.0.0.1:18087 -I ../group33/.git/c1-local-recovery-20261117/c1-date-manual.init.gradle --offline --no-daemon --max-workers=1 --no-parallel --console=plain
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest -I ../group33/.git/c1-local-recovery-20261117/login-isolated.init.gradle --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

The local init scripts change only applicationId (`c1datemanual` for composition,
`c1dev88` for branch validation); no production config is registered. Full device
commands use the earlier `am instrument` form with the same two classes and
LANG argument; latest Host count is eight, C1 entry count twenty. Actual online
checks use `CaregiverHistoryOnlineEntryTest`, `-e c1HistoryOnline true`, explicit
`c1HistoryFrom`/`c1HistoryThrough` on a fresh fictional range and the isolated
`com.group33.medicationreminder.c1datemanual.test` runner. Locale setup used
`ChainOnlineFlowTest#configureLanguage -e chainLanguage LANG`. Exact arguments,
matching binaries and per-run raw logs remain in the named local evidence folders.
Server/Shared are unchanged from latest main; the recorded same-source Linux
Server628/Shared18 results are reused, not claimed as newly executed. Historical
unsupported Windows full-repository failures remain recorded.

All current working artifact hashes (401), repository policy and diff checks pass
after both temporary source overlays were restored. The final publication preview
recomputes 407 index-blob hashes, including incoming main artifacts. Neither check
commits or changes the real merge index. Approval is required before finishing
the preserved old-main merge, committing the 14-file evidence update and normally
merging the verified latest main; the final real tree must match that preview.

### Checks actually run

On Windows/JDK17, using an applicationId-only init script setting
`com.group33.medicationreminder.c1dev88`:

```text
gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest -I <isolated-application-id.init.gradle> --offline --no-daemon --max-workers=1 --no-parallel --console=plain
```

Passed: Debug 191 / Release 190 JVM tests, zero failures/errors/skips;
lint 0 errors/33 warnings; Debug, Release and instrumentation APK builds (9m22s).
Targeted resolver + C3 History ViewModel checks also passed (11 + 18).
Subsequent test-driver-only changes were rebuilt with `:android-app:assembleDebugAndroidTest`.
Server/Shared sources equal main `bbe4997`; the separately recorded same-source
Linux Server628/Shared18 actual execution is reused, not claimed as a new run.
Historical unsupported Windows Server failures remain recorded below.

On physical Mi Pad 4 Plus API27, install matching app/test APKs after checking
their isolated package names with aapt. Run each `LANG` (`en`, `zh-CN`):

```text
adb -s <tablet> shell am instrument -w -r -e class com.group33.medicationreminder.integration.CaregiverFeedbackEntryTest,com.group33.medicationreminder.caregiver.CaregiverHistoryHostTest -e c1DateLanguage LANG com.group33.medicationreminder.c1dev88.test/androidx.test.runner.AndroidJUnitRunner
```

Pre-sync full run06: **27/27 each group, 54 passed, zero failures/errors/skips**.
The single-tap driver change and repeated original-order checks are described below;
the earlier failures remain retained evidence.
C1 entry tests set the isolated application's AppCompat locale before Activity
creation and restore it afterward; C3's own Host tests retain their own locale
setup. API35 has not been rerun; historical API35 failures are not declared fixed.
Strict fixture repositories prove renderer/context behavior, not actual Server,
Room, production trust, phone authorization or final normal-entry behavior.

Final app SHA-256 `3bb0d96a3da53d498df0c80f1f98d67c7710bcdaa899449098236381f8123851`; test SHA-256 `d0ffbd16cb8d85dedc563e1de8209277b149b82a29f93b99fa876d1d6c29a1cd`.
Inspected run05 renderer screenshots (same product APK, before the test-only tap change): [unavailable EN](../evidence/c1-feedback-date/unavailable-en.png),
[unavailable ZH](../evidence/c1-feedback-date/unavailable-zh-CN.png),
[manual read EN](../evidence/c1-feedback-date/manual-history-en.png),
[manual read ZH](../evidence/c1-feedback-date/manual-history-zh-CN.png),
[preserved draft EN](../evidence/c1-feedback-date/preserved-draft-en.png),
[preserved draft ZH](../evidence/c1-feedback-date/preserved-draft-zh-CN.png).
Raw build logs, every failing/rerun instrumentation log, source metadata and
inspected screenshots remain under the primary checkout's ignored
`.git/c1-local-recovery-20261117/` (`pr132-136-*`). This local evidence is not
automatically available to remote reviewers. Screenshots are controlled renderer
fixtures; outer navigation/header binding is not final Activity acceptance.

### Retained failures and limits

- The first build accidentally mixed an unrelated old #124 OverviewRoomRefreshTest
  with #136's newer evidence resources. Removing only that extra dependency test
  restored the intended nine-file baseline; no #136 tests were removed.
- First full API27 run: 25/27; two raw edge drags threw INJECT_EVENTS before the
  scrolling assertion. Automation-channel run02: 22/27 in each group because its
  events lacked a touchscreen source. Set SOURCE_TOUCHSCREEN, retaining exact
  coordinates, hold timing and assertions: run03 passed 27/27 each group.
- Run03 screenshot inspection found mixed static/dynamic locales in the harness.
  Replaced the post-creation resource mutation with pre-creation AppCompat locale
  selection, added a locale assertion, rebuilt and reran the entire group in run04.
  That run passed 26/27 English and 27/27 Chinese: the existing fast-reverse/final-row
  details case timed out. Added step-specific timeout state/screenshot diagnostics,
  preserving the five-second timeout and original assertions; run05 passed both groups.
  The intermittent failure is not closed merely because this rerun passed.
  No application permission, global device language/navigation setting or original
  application data was changed; only the isolated test app was stopped afterward.
- Earlier pristine-#124 six-file correction (SHA `1135773b6182b57816822ad30e6c3522197d86b86f62687a02fe0ac15ea87e01`,
  tree `7cbcf2161a2500b6c4df8d045f1b966411e8e4ae`) passed 18 selected API27 checks.
  That historical result did not cover this #136 composition or the then-missing seam.

Focused reproduction before the final tap-driver change: ten single fast-reverse checks per
language (20/20), plus three keyboard-then-fast-reverse groups per language
(12/12 test executions), all passed. Commands/logs/results
are in `pr132-136-reverse-diagnostic` and `pr132-136-reverse-pair-diagnostic`.
Subsequently the original three-test order reproduced the failure in English
iteration 5: the last row was visible but its inline details did not open
(`count=25, first=19, last=24, top=-224`). The reverse-scroll assertion had passed.
The raw tap driver completed 44/45 tests; the failure log and screenshot remain in
`pr132-136-reverse-triple-diagnostic`. A temporary scroll-trace variant passed
30/30, so fling state is not established as the cause. That tracing was removed.

The final C1 test helper uses Espresso `Tap.SINGLE.sendTap` with the same point,
checks `Tapper.Status.SUCCESS`, and does not retry or relax the five-second result
assertion. This is test-input robustness work, not a proven C3 product fix.
After rebuilding `:android-app:assembleDebugAndroidTest`, full run06 passed 54/54;
ten original-order groups per language passed 60/60 in
`pr132-136-reverse-triple-verified`. Original failures remain visible.
Matching final app/test APKs are archived in `pr132-136-date-device-06`; older
iterations retain source trees and hashes, not all historical APK binaries.

Pre-sync repository policy, staged/unstaged `git diff --check`, and all 399
checkout-normalized artifact hashes pass. The six evidence PNGs are included in
the manifest. The real staged main-merge tree was unchanged by the dependency
restoration; original `gradle.properties` bytes and the separate keyboard delivery
were retained. This candidate contains no committed or staged dependency overlay.

**Remaining:** S1/S2 production date contract/implementation; final C2/C3/current-main
normal-entry integration, applicable human/bilingual/accessibility acceptance and
non-author review. The prior manual trial does not approve this revision. Keep
#132 Draft; no commit, push, issue comment, formal approval or issue closure is implied.

## Recorded direction and remaining production dependency

[S1's recorded response](https://github.com/BRMRs/group33/issues/15#issuecomment-6939380941), posted after the paired tests, answers [C3's source request](https://github.com/BRMRs/group33/issues/15#issuecomment-6909543539):

- Use the currently selected Elder execution device's last trusted reported IANA zone for the initial local date. Multiple plan zones or no plans do not change that anchor. Missing trusted input remains unavailable; do not substitute the caregiver device zone, UTC or an arbitrary plan zone.
- S2 owns trusted reporting and authorized HTTP contracts. S1 owns persisted scoped observations, date publication, midnight/zone updates and invalidation. C1 consumes matching Active-context input and rebinds the host; C3 consumes the explicit date and maintains page cleanup.
- Scope the future input by RepositoryScope, session generation, relationship and Elder. Invalidate before switching contexts; discard old-context late results. Zone changes and local midnight require new dates.
- S1 explicitly confirms that main has no consumable production provider yet. The proposed stream's concrete interface, wire contract and persistence implementation require separate S1/S2 delivery and applicable review.

The date rule and ownership direction are now recorded; a reviewed, implemented producer interface remains absent. This internal holder does not implement the future observation stream or attest trust. The nullable callback and legacy-panel compatibility patch predate S1's response and still need C1/C3/S1 review, including unavailable-state presentation. Any Shared/API/persistence change requires applicable Android/Server review. No production registration, issue closure or real-server acceptance is claimed.

## Verification and outstanding evidence

Windows/JDK 17. Commands used `--offline --max-workers=1 --no-parallel --no-daemon`:

```powershell
.\gradlew.bat :android-app:testDebugUnitTest --tests '*CaregiverFeedbackRangeInputTest'
.\gradlew.bat test :android-app:lintDebug :android-app:assembleDebug --continue
.\gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug
```

Targeted tests passed 11/11. Android Debug and Release each passed 135 tests; lint reported 0 errors/28 warnings and Debug APK assembly passed. Shared's 18-test result was FROM-CACHE. The full-repository command exited 1: unchanged Server tests reported 310 failures out of 627, including unsupported Windows `posix:permissions` and dependent Spring initialization errors. A pristine-main targeted persistence test reproduced the POSIX limitation; this does not establish the cause of every Server failure. Whole-repository checks are not green.

The separate #124 combination passed 191 Debug JVM tests and assembled app/test APKs using isolated applicationId `com.group33.medicationreminder.c1review124`. Earlier combined device results (before the paired original comparison):

| Environment | Result | Unresolved observations |
|---|---|---|
| Mi Pad 4 Plus, API27, original MIUI gesture navigation | 18/21 | Three page-edge `INJECT_EVENTS` SecurityExceptions |
| Same tablet, temporary button navigation | 20/21 | `fastReverseAndLastRowDetailsRemainReachable` timed out after 5 seconds; original navigation setting restored |
| API35 emulator | 19/21 | Large-text timeout; `overviewContentUsesOnlyTheSharedPageScroll` expected scrollY 0, actual 109 |
| API35, those two failures rerun without source changes | 1/2 | Large-text passed; overview failed again, actual scrollY 640 |

All three new date integration checks and four C1 navigation checks passed in each full combined run. No assertion was weakened. The [manual trial record](../evidence/c1-feedback-date/LOCAL_ACCEPTANCE.md) records the user's provisional feedback with its limits.

[C3 requested exact combination inputs and pristine comparison](https://github.com/BRMRs/group33/issues/15#issuecomment-6922291973). The [2026-11-17 paired comparison](../evidence/c1-feedback-date/124_BASELINE_COMPARISON.md) now records exact source/APK hashes, matching device conditions, singles and related groups for pristine #124 and the candidate. Both target failures reproduce on the original. The new combined API35 group additionally timed out on date-side dragging; two isolated reruns per variant passed, leaving that group difference unresolved. No original-code defect cause, green full regression or human approval is inferred. Raw APKs/logs remain local for a separately authorized handoff.

[C3's separate proposed denied-event-read tests](https://github.com/BRMRs/group33/issues/15#issuecomment-6923493529) request C1 context/authorization-recovery review and S1 Repository/Room review. They do not authorize a new date or identity protocol. Their implementation and human review are outside this partial delivery.
