# S2 reverse photo inventory evidence

Owner: XinyanXue / S2. Refs #13 / #16. Current parent: [#105](https://github.com/BRMRs/group33/pull/105), exact `7d0ba6fc2303bc28215d51757d2559ca5f62812f`, retaining #104 and previous implementation attribution. The historical November2 execution below used its then-Draft parent `c0770555e3c7256b6cfb15e7dfe642fcce490a8c`. This is a local read-only operator slice; neither whole issue is complete. Main integration still requires reviewed prerequisites and a non-author security/persistence review. Proposed review perspective is the existing Server/persistence backup; this record does not invent their approval or a new assignment.

## Observable change

The existing forward reconciliation reads published metadata and validates known objects. The new separate `photo-inventory inventory` command enumerates immediate private directory entries and matches object identities against published metadata, durable upload reservations/receipts and Medication references. It reports protected states, unmatched candidates, temporary files and unsafe entries without deleting, reading photo content or changing normal application code/Shared/wire/schema.

Reference protection and every filesystem/database failure remain observable through a single sanitized aggregate report. COMPLETE is selected-scope exhaustion, never deletion authorization or cross-storage atomicity. Details and environment are in [operator instructions](../PHOTO_INVENTORY.md).

## Test coverage

- Ordinary inventory service tests cover bounded ID batches, exact/truncated limits, source failure after a prefix, missing mappings, unavailable storage, observed mutations, monotonic deadline, interruption, empty-directory source validation and input bounds.
- Real local-file tests cover canonical objects, parts and unknown names, flat traversal, symlinks/FIFOs/permissions, roots that cannot be created or repaired, rewrite/new entry during matching, directory replacement with anchored access, private settings and rejection of deletion/cloud commands.
- Tagged real PostgreSQL tests use a fresh fictional schema and SELECT-only operator: real product uploads, unchanged rows/private files, older-than24h unknown-upload recovery with the original key/receipt, rejected identity, Medication-only and accepted-receipt diagnostic views, concurrent STAGING publication, actual concurrent upload, a database-rejected hidden write, lock/statement timeouts, owner/table/column grants, missing SELECT, exact limits and RLS-hidden reservation refusal.

The two diagnostic views deliberately simulate incomplete metadata; they are not evidence that normal validated foreign keys allow those states. Test-source fakes prove service orchestration only; actual file/PostgreSQL/packaged-process results are recorded separately. The macOS fd-backed fallback is exercised on this machine. Linux's JDK backend must be attributed to its actual CI/run result, not claimed from the macOS run.

Final execution counts, cache modes and packaged launcher results are recorded after verification; unexecuted steps are not passes. Preserve the original forward-reconciliation regression sources and same-key unknown-result assertions. No cloud/real backup/photo data, deployment, normal App/Android/phone provider/SMS or two-device test is performed for this slice.

Expected commands for this slice:

```sh
./gradlew :server:test --tests 'com.group33.server.photo.inventory.*' --offline
./gradlew :server:postgresIntegrationTest --tests 'com.group33.server.photo.inventory.PostgresPhotoInventoryTest' --offline
./gradlew gate1Check test :server:photoReconciliationDist :server:schemaMigrationDist :android-app:assembleRelease --offline
```

The tagged PostgreSQL task requires explicit local test settings and fails when absent; it does not silently skip. Full PostgreSQL, original forward tests, Server-only packaging and independent launcher checks are to be run before declaring this candidate verified. Ordinary CI does not run real PostgreSQL and does not prove cloud acceptance.


## Verified local candidate — 2026-11-02

All 19 new ordinary and 14 new real PostgreSQL cases passed. Full ordinary reports: Server 777, Shared 18, Android Debug 51/Release 51, total 897; full real PostgreSQL suite 323. Zero failures/errors/skips. The original 20 forward-reconciliation ordinary and 11 PostgreSQL tests remain unchanged and pass in those suites. Final task modes: Server executed; Shared FROM-CACHE; Android Debug executed, Release executed. Server-only layout: Server executed, Shared executed. No device execution is implied by Android build/JVM results.

Gate1, policy, lint, Debug/Release APK, bootJar, migration/operator packages and the independent Server-only build passed. Lint: 0 errors, 2 inherited warnings. Thirteen actual packaged shell-launcher/independent JVM executions cover reference/protected/candidate classification, limits, invalid commands/input, missing schema, owner/column-write/RLS refusal, unsafe mode, complete empty scope, unavailable Mac helper and original-state recovery. Reports are aggregate-only with empty raw stderr. Fixture rows and file bytes/modes/mtimes are preserved after test-harness restoration; the command itself never changes them.

Application classes/resources match parent #105; inventory/reconciliation/migration administration, helper and tests are absent from bootJar. Existing main business/SQL/migration, Shared, Android, deployment and forward-operator sources are byte-preserved. Dedicated PostgreSQL is stopped and generated fixtures/credentials removed; reusable tools/dependencies/distributions and original failure evidence remain local.

The direct directory tests initially exposed the Mac JDK's missing secure-directory API. The fd-backed packaged fallback preserves handle-relative no-follow access; the strengthened replacement test proves that a renamed-root handle sees only its original directory and leaves the symlink's external target unchanged. Initial test-authoring/portability logs are retained separately. A remote CI result, if available, is recorded in the PR; local Mac results alone do not claim execution of Linux's native backend.


## 2026-11-25 current-parent synchronization

BRMRs integrated current #105 `7d0ba6fc2303bc28215d51757d2559ca5f62812f`, which inherits #104/#149/#148/#79 and main `9e5621393c3d130bc65ec6754d0531c363c9cf9d`. This replaces the obsolete mixed cloud/schema parent while preserving every original inventory source/test/launcher/helper byte from `9d2120b07242de057cdcdb4a5a14b5c2775f5f78`. The earlier evidence above remains historical and is not a rerun claim. Current-parent forward reconciliation includes the reviewed-source column-grant/RLS corrections and the PostgreSQL17 CI profile. Current inventory-specific failure-path review and fresh checks are recorded separately below. No real cloud, migration, deletion or operational acceptance is implied.


## 2026-11-25 reproduced visibility failure and correction

The synchronization commit `20f5360` initially preserved original inventory bytes. A fresh owned PostgreSQL16 fixture then ran the original14 inventory controls and a new security-invoker-view counterexample: the14 controls passed, but the counterexample returned COMPLETE rather than DATABASE_UNAVAILABLE while the underlying RLS hid an original STAGING recovery reservation. The view itself reported row_security_active=false. No tool write or deletion occurred. Before/after fixtures and regression verify unchanged owner-visible rows/file bytes, removal of only test-authored policy/view, restored staging protection and original-operation recovery to the same photo ID.

Set transaction-local row_security=off in the existing read-only Repeatable Read transaction so underlying policy-filtered reads fail instead of silently hiding references. Keep the explicit admission checks, no-deletion output, rollback and existing timeouts. Also reject PostgreSQL17+ MAINTAIN privileges for all three relations using version-aware inspection, preserving PostgreSQL16 compatibility. The original direct-RLS and column-write controls remain.

Fresh local PostgreSQL16 after correction: inventory18 report instances,15 passed/0 failures/errors/3 explicit17-only MAINTAIN skips; inherited forward27,25 passed/0 failures/errors/2 explicit17-only skips. Combined45 instances:40 passed,5 version-scoped skips, no failures/errors. This does not claim the five17-only cases ran locally; the inherited dedicated17 workflow must execute all45 without skips before its result is claimed. All job-owned local clusters/roles/random credentials/directories were stopped and removed. Full ordinary checks, standalone packaging and exact-head hosted results are recorded in the current PR after execution. No actual Supabase, cloud schema, cleanup, phone, device or production acceptance follows from these local fixtures.


The corrected candidate's full `gate1Check test :server:schemaMigrationDist :server:photoReconciliationDist --offline` succeeded:110 tasks,46 executed/49 cached/15 up-to-date. Server786 and Android Debug135/Release134 executed,0 failures/errors/skips; Shared18 was cached and is not a new execution. Policy/whitespace and all497 checkout artifact hashes pass. Both source-review axes independently confirmed the two corrections with0 remaining findings; that is not non-author human approval.

Fresh standalone ZIP checks executed both packaged launchers with no private inputs: each exits64 with only its sanitized settings JSON and empty stderr. Executable permissions and the packaged fd scanner are present; application bootJar excludes inventory/reconciliation/migration classes. Operator ZIP SHA256 `9cf77aeab34b34afbdc2afb7d339b0c69ee6ec12f54c57e7fd5aa04f58cd882b`. Temporary extraction removed. The historical thirteen configured launcher scenarios remain their original November2 result; this current run claims only the two missing-input packaged executions, ordinary/current PostgreSQL controls and packaging checks just described.
