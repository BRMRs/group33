# C1 login current-attempt adoption candidate

2026-11-27 (Asia/Shanghai). Refs #10 / #118; local preparation for PR #88.
Agent-assisted inspection and verification; no human approval or signature.

## Exact inputs and provenance

- Parent #88: `ec06f8d788bb3d4a4348f9493c054142f91c1199`.
- S1 correction #153: `c863be09087cf07e678be73bf23b2e02c13344ec`.
- Local branch: `test/10-c1-phone-current-attempt`, separate from the member's
  dirty language checkout and existing login worktree.
- GitHub API recursive-tree comparison checked all **495** remote tracked blobs
  against the reconstructed checkout using Git blob hashes: **zero mismatches**,
  before this C1 evidence document was added. The complete S1 patch, attribution,
  tests, hosted runner and evidence were retained; no replacement implementation.
- Remote main observed at `99837cd55bb32a39990c725699ecdc7551ce862c`.
  This candidate does **not** contain that main revision. Its parent includes
  `9e5621393c3d130bc65ec6754d0531c363c9cf9d`.

## Client and bounded session-guard inspection

1. `BoundPhoneExchange.start` rechecks the original authoritative attempt inside
   its IO block, then checks the published snapshot before calling `grant`.
   A stale preflight result cannot by itself authorize the request.
2. Every `sdkToken` callback checks the injected snapshot; invalidation closes
   the exchange and returns null. No `runBlocking`, Keystore or disk read is
   introduced into this synchronous SDK callback.
3. `CommonPhoneSession` delegates to the same `PrivateSessionStore` used by the
   business host. It introduces no second credential store or installation ID.
4. The snapshot compares signed-out state, environment, generation, epoch and
   start time. Existing clear/replacement invalidates the shared observation;
   HTTP and authentication completion retain authoritative suspending checks.
5. Existing original-attempt deadlines, proof serialization, registration
   single-use and failure isolation remain. No Shared/Server/wire changes.

No confirmed blocking finding in this bounded inspection. This statement is
not a formal C1 approval, independent S1 signature or whole-PR acceptance.

## Local verification

Command (JDK17, SDK35, default unconfigured phone profile):

```powershell
.\gradlew.bat :android-app:testDebugUnitTest :android-app:testReleaseUnitTest :android-app:lintDebug :android-app:assembleDebug :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
```

Command exited **0**, `BUILD SUCCESSFUL in 9m 11s`: 152 actionable tasks,
110 executed / 42 from cache. Local JVM reports: Debug **192**, Release **150**,
all actually executed, zero failures/errors/skips. Debug lint: **0 errors,
58 warnings**. Debug, Release and AndroidTest APKs all built successfully.
The instrumentation APK was compiled, not executed on a device in this run.
Repository policy and whitespace checks passed; the complete checkout manifest
is regenerated to include this evidence. Machine-readable counts and APK hashes
are retained in the original repository's private `.git/c1-progress-20261127/`.
No local device, supplier, SMS, live Server, PostgreSQL or cloud test is claimed.
The #153 hosted API35 results remain S1's evidence at their original exact head.

## Current personal progress and remaining gates

- #112 is now merged; caregiver pairing delivery has advanced beyond the older
  startup summary. #132 has C3 approval on `b18878f`, but retains Draft because
  the trusted production date source and final normal-entry composition are absent.
- #127 and #140 remain open and require current-head independent review.
- #88 remains Draft with the original changes request. This local candidate
  neither changes the remote parent nor dismisses that request.
- #118 now has actual runtime candidates #155/#156 and S2's Draft #157; these
  must not be described as deployed or as passing real phone acceptance.

Before adopting into the remote parent: the member personally checks this bounded
review and the two counterexamples; preserve S1 authorship and S2's recorded
security review. Synchronize actual main, resolve the login/pairing integration
and contribution/manifest conflicts, and verify the final combined head.
Git SSH/HTTPS fetch failed locally; GitHub API reading succeeded. The API main
comparison is available locally, but contains omitted binary bodies and cannot
replace a full Git merge. No partial main patch was applied.

After #118 supplies the reviewed service/configuration/test package, verify real
one-tap/SDK SMS, first-role/existing-role routing, logout/relogin and authorized
pairing. Keep missing service/policy/credentials and human review explicit.

Rollback: discard this isolated candidate's adoption delta; the original worktrees,
remote branches, deployed environments and user data have not been changed.
