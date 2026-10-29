# E photo capture and submission handoff

2026-10-28. Refs #13. Base main `64ddd4e26440d60a60f209dd7eb12d102e2a55dd`.
Delivery branch `feature/13-elder-photo-submit`; member authorized commit and push on 2026-10-28. No merge or approval. This expands the earlier state-only proposal at the member's explicit request. Lead assignment/reviewer confirmation remains pending; no issue label changed.

The diff exceeds the usual 500-line guideline because camera/URI ownership, preview, submission isolation and their tests must form one reviewable flow; no backend, shared contract or global theme rewrite is included.

## Implemented boundary

Main role page → existing Elder home → Photograph a medicine box → system camera or document picker → private copy → preview → use/retake/select another → submit state. No medication name/dose inputs. Existing role/home roots scroll to keep entry reachable with enlarged text. This branch does not pull unmerged #35/#59 into main or replace their functionality.

The Activity uses an Elder feature ViewModel. An authenticated host may implement `ElderPhotoDependenciesOwner` to supply real `MedicationPhotoRepository`, `OnlineCommandRepository` and current verified Elder scope. No fake is registered in the application. Main has no production upload/session adapter; the default screen persistently states upload is not connected, and explicit submit reports not sent. This is not a real Elder-to-Caregiver handoff demonstration.

The host must provide dependencies only for a verified Elder session; scope is not authorization. Session/environment changes hide previous state and isolate private draft namespaces. begin(SUBMIT_PHOTO) supplies the command identity; the feature never fabricates a principal/key. Confirmed alone means accepted; REFRESH_REQUIRED remains accepted; errors/cancellation after adapter entry do not prove non-submission. NOT_SUBMITTED explicit retries preserve context. Rejected explicit retries use retryRejected(originalOperationId), never submit under a new key. Resume validates operation/scope/kind/target and PHOTO reference, without casting the result into full photo metadata. Metadata observation/refresh remains the adapter's responsibility.

## Photo ownership and privacy

System camera uses a non-exported FileProvider restricted to `cache/elder-camera/`; no CAMERA or broad media/storage permission added. The system picker grants the selected URI. Original bytes are copied into private `files/elder-photos/<scope digest>/`, with bounded 5 MiB input and 20 MP dimensions, JPEG/PNG signature/bounds/decode checks, repeated streams and SHA-256. Server validation is still required. Preview uses a sampled display copy and JPEG orientation; upload bytes are not re-encoded.

Private sources are excluded from legacy backup, cloud backup and device transfer. Attempt metadata (original command/scope/hash/type/size) is synced before adapter entry. Replacement removes only an unattempted old draft; attempted sources/context remain for adapter/journal reconciliation. Restored attempted drafts are locked against blind resubmission and may query the original command. This is local draft retention, not a replacement for S1's durable command journal, nor proof of end-to-end process recovery. Lifecycle/retention cleanup after authoritative terminal reconciliation must be integrated with that adapter; unresolved originals are deliberately not deleted. FileProvider/cache and source retention need S1/S2 privacy review.

## Verification

See the final local evidence report supplied alongside the diff for command logs, exact counts and emulator screenshots. Automated coverage includes state success/failure/unknown, double taps, receipt validation, scope changes, late A→B callbacks, original-key retries/resume, real private-source reopen/restore and bilingual 320dp/200% layout.

During initial camera testing a default zero inSampleSize caused a divide-by-zero preview crash. Fixed by explicitly starting at one; a real bitmap decode regression was added. Initial layout assertions incorrectly assumed linear font scaling on newer Android; corrected to Android's SP conversion rather than lowering the required 24sp. Failed development runs remain in local working logs, not claimed as final pass.

Required checks: `python3 scripts/check_repository_policy.py`, `./gradlew gate1Check test :android-app:lintDebug :android-app:assembleDebug`, `./gradlew :android-app:connectedDebugAndroidTest`, manifest and diff checks. No network-dependent test or real personal/medical photo is used.

## Still requires integration / human acceptance

- Real authenticated Android HTTP/Room/journal wiring and authorized two-device upload/read; Server photo PRs are independent and unmerged dependencies, not supplied by this branch.
- Real process/boot recovery across the command journal, metadata refresh, terminal source cleanup and expired credentials.
- C1 shared language/theme integration; this main-based branch uses existing Material theme components without changing global tokens or adding a language selector. Resources are bilingual.
- TalkBack actual listening, grayscale, physical-camera/vendor compatibility and API23/35 device matrix.
- Non-author feature review, S1 infrastructure/privacy review and S2 security/contract perspective. No agent approval/signature, no #13 closure.

Rollback the added Activity/provider/resources and feature entry; do not delete unresolved private source/journal data as a rollback shortcut.


## Main synchronization and validation — 2026-10-29

Merge input: feature `1ba7af22bf7b55527ed2415eafd19a22e425fb3c` plus main `999e1826ebca07998eaa9e150c0e47b5b05921fa` (#45/#55). Only CONTRIBUTIONS and the artifact manifest conflicted. Both contribution rows and both file inventories are retained; hashes were recomputed after resolution. Room journal/C2 photo source equals main; E photo source equals the pre-merge feature head. No Room/C2 implementation is counted as new E work. The whole issue remains incomplete and this PR remains Draft.

On the resolved merge tree, `gate1Check test :android-app:lintDebug :android-app:assembleDebug --offline` passed: 100 tasks, 75 executed/18 FROM-CACHE/7 UP-TO-DATE. Reports: Shared18, Server40, Android Debug67/Release67, total192 report executions, zero failures/errors/skips. Baseline tasks may reuse results. Lint0 errors/9 warnings; repository policy, hashes and diff checks passed.

All19 instrumentation tests actually passed on the API37 test emulator: inherited Room journal12 plus E private-source/recovery/isolation5 and bilingual layout2. A repository-external Gradle init changed only the device-test applicationId to `com.group33.medicationreminder.pr82merge` to protect the existing development installation; source/resources were unchanged. This is not API23/35 or physical-device evidence.

Actual normal launcher → Elder → photo entry → system camera → preview → explicit not-sent feedback passed; system document picker → generated PNG → private preview also passed after merging. No real personal/medical images or network upload were used. Device density420dpi/font1.0 remained/restored, the isolated package and public fixture were removed, and the original development package was retained. No TalkBack/grayscale claim.

These results supersede the old unintegrated-main warning for #45/#55 only. Real authenticated adapters, metadata refresh, terminal cleanup, full journal/upload restart, shared #35/#59 UI integration and two-device authorization remain pending. Local gradle.properties was backed up/stashed for checks and is restored byte-for-byte without entering the merge commit.
