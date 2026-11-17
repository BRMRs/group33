# C1 feedback date input handoff

Status: **partial implementation candidate; production date source and integration remain pending**. Refs [#15](https://github.com/BRMRs/group33/issues/15) / [#19](https://github.com/BRMRs/group33/issues/19). This document records C1's implementation, not an accepted date-source contract or human review.

## Baseline and implementation

The branch `feature/15-c1-feedback-date-input` starts from main `61140712193b816980045c52cc7276223731c268`. Pairing HTTP corrections are a separate delivery and are excluded.

Publication-time check: main has advanced to `bbe4997034ecb837d2093f4e73267cc530976f29` via #116. This branch has not merged or rebased that change. The checks below describe the original branch and exact #124 inputs, not a current-main integration result; current-main compatibility and artifact-manifest reconciliation remain review work.

`CaregiverHistoryViewModel.defaultRange(today)` already returns today minus 29 days through today, inclusively. The existing context and `ChainServices` do not provide an authoritative history date. ADR-0003's per-plan execution zones do not define one history-wide date for multiple plan zones or no plans. Existing query rules and the 90-date cap are unchanged.

- `CaregiverFeedbackRangeInput.kt` consumes an explicitly supplied date captured with its context. It rejects missing inputs and mismatched environment, installation, user, session, relationship or Elder, and rechecks context after the callback. It retains no previous range and must run on the host's serialized context thread.
- `CaregiverFeedbackDate` is an internal Android holder, not a Shared/wire DTO or an identity-verification claim. The adapter selects no clock, device zone, plan or endpoint. The normal entry does not register or call it yet.
- Eleven deterministic tests cover inclusive date boundaries, absent inputs, stale contexts, switching during resolution, restored sessions and replacement dates.

## Separate #124 combination candidate

The [four-file patch](../evidence/c1-feedback-date/124-date-consumer.patch) applies to pristine #124 `a377a7df0731d48fc052514b61d37955e014f06d`. SHA-256: `5a706f23cfd842ccd44c46d7d9b630f3eff4e38a8a7964f3ca9343b8d37713a4`. An empty context line was normalized before committing; applying the tested and normalized patches produces the identical Git tree `4d62324626fabea997179e1cfbb20fc32d3f3c8b`. It includes the two C1 Kotlin files and these adaptations:

1. `OnlineFeatureContent.kt`: propose a nullable date-range callback. If unavailable, clear C3 Hosts and retain the legacy dose panel; a subsequent explicit host rebind may consume a newly available date. Existing non-null callbacks remain usable.
2. `CaregiverFeedbackEntryTest.kt`: use fictional scoped dates with the real resolver. Add missing-date/rebind, restored-session/stale-date and reentry-without-date checks. Preserve the legacy query and require an additional exact 30-date query only for a valid supplied date.

The nullable callback is a C1/C3/S1 review candidate, not an accepted shared interface. No date producer, Activity registration, automatic notification or production refresh API is invented. Retaining the legacy panel does not accept its UTC-based range as the complete-history default. On adoption, update the recipient tree's own contribution/artifact records rather than copying main's entire manifest.

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
