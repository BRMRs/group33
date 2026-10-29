# C2 photo caller-state preparation — 2026-10-24

Task: [#50](https://github.com/BRMRs/group33/issues/50), parent [#13](https://github.com/BRMRs/group33/issues/13).
Owner: Iris-wly (C2). Local branch: `feature/50-c2-photo-state`, based on main `f9f29d8`.

## Readiness and bounded scope

The issue remains needs-triage: BRMRs/XinyanXue review arrangements, C1 trusted host integration and real content-adapter boundaries still require confirmation. This is local implementation preparation against existing interfaces, not a Ready claim or completed photo handoff. No API/DTO/schema changes or production registration.

Two files provide the proposed first PR scope: `CaregiverPhotoViewModel.kt` and `CaregiverPhotoViewModelTest.kt` in the caregiver/photo source and test packages. They cover local metadata observation, content-handle loading/retry, and selection isolation. Manifest/evidence records accompany them. They do not depend on unmerged PR #43.

The trusted host must supply a repository bound to the supplied RepositoryScope. The scope value is an isolation key, not independently verified authentication. Clear context on sign-out; switch context when the selected Elder/account changes. A revoked scope/Elder pair stays denied for this ViewModel lifetime; a newly established relationship requires trusted host lifecycle handling, not stale cached AUTHORIZED data.

`PhotoContentStatus.AVAILABLE` means only that loadContent returned a handle. It does not mean bytes opened, passed size/type/pixel validation, decoded, or were displayed. The renderer must own/close streams, report decode errors, and verify actual viewing before plan confirmation. This state must not be used as a plan-readiness flag.

`retryObservation` retries the local observation only. Server refresh, HTTP authorization/error mapping, Room persistence, content decoding and UI navigation are not implemented here. Metadata cannot stand in for a viewed image. Repository errors remain typed; unexpected exceptions do not expose raw diagnostics.

## Verification

- Initial targeted debug test run: 8 methods passed; two additional cases were then added.
- Final `gradlew.bat gate1Check test --console=plain`: BUILD SUCCESSFUL (21 tasks executed, 71 up-to-date; includes lintDebug, assembleDebug and bootJar). Shared/Server test tasks were UP-TO-DATE; initial test XML reports contain 10 passing methods in each Android variant, with zero failures/errors/skips.
- `python scripts/check_repository_policy.py` and whitespace validation passed.
- Tests cover metadata/empty state, same-photo retry, duplicate clicks, account/Elder switch, revoked/unknown access, foreign metadata, local observation retry, logout/photo removal, newer photo selection and unexpected content errors.
- Deliberately non-cooperative suspended content calls test late completions after invalidation. Test-only adapters throw if upload or server refresh is called.
- Local tests use existing private gradle.properties configuration; a future PR needs CI on its committed checkout. No device, real image decode, real authorization or two-device acceptance was performed.

## Next integration gates

Confirm the bounded scope/reviewers in #50 before formal submission. Then connect the team's approved screen and C1 host, implement protected content reading and refresh with S1/S2, add bilingual/accessibility/device evidence, and verify real photo visibility before plan confirmation. Keep #50 open until its broader acceptance is demonstrated.

## S2 review correction — 2026-10-25

The revoked-then-observation-error regression failed before the fix. Exception handling now preserves the recorded scope/Elder revocation, and retryObservation checks that same record before resubscribing. The regression also checks that reselecting the revoked context does not subscribe. Ordinary observation failures remain retryable. Final results and new CI are recorded in PR #55; no formal approval or real-adapter acceptance is asserted.

Correction validation: `gradlew.bat gate1Check test --console=plain` passed (23 executed / 69 up-to-date); 11 photo methods passed in each Android variant. Shared/Server tests were UP-TO-DATE. Policy and whitespace checks passed.
