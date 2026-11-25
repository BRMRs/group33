# Read-only local photo reconciliation

> **Current integration, 2026-11-25 — BRMRs / S1.** This candidate inherits #104 `4590b27` and #149/#148/#79 with actual main V1–V10/dose. The initial synchronization preserved `fecb575`; current corrections strengthen the existing metadata source admission/RLS checks and add regression coverage while retaining the original service, file inspector, packaging and eleven PostgreSQL controls; old index code is retained independently as #102 V11, not duplicated here. The tool stays a separate explicitly invoked local read-only distribution; observations never authorize deletion. Historical results retain their original source attribution; current actual checks are recorded in the PR. Prerequisite main landing and independent final review remain required; no actual cloud/operator deployment or migration is performed.


Refs #13 / #16. This separate administration tool inspects existing local PostgreSQL photo metadata and private local-file objects. It makes no database, object, permission, migration, receipt, upload reservation or deployment changes. It is absent from the application bootJar, has no Spring registration, HTTP route, scheduled job or delete command, and is not run on Server startup.

Build with the existing JDK17/dependency cache:

```sh
./gradlew :server:photoReconciliationDist --offline
```

Extract `server/build/distributions/group33-photo-reconciliation.zip` into a private operator directory. Run `bin/photo-reconciliation inspect` with these environment variables, supplied privately rather than committed:

| Variable | Meaning |
|---|---|
| `GROUP33_PHOTO_INSPECT_DATABASE` | Explicit local database name |
| `GROUP33_PHOTO_INSPECT_PORT` | Explicit loopback PostgreSQL port; host is fixed to 127.0.0.1 |
| `GROUP33_PHOTO_INSPECT_SCHEMA` | Existing, specifically selected private application schema; no public/pg_* schema |
| `GROUP33_PHOTO_INSPECT_USER` | Dedicated non-owner SELECT-only operator, not the migration/application credential |
| `GROUP33_PHOTO_INSPECT_PASSWORD_FILE` | Existing private regular password file, no group/other permissions or final symlink |
| `GROUP33_PHOTO_INSPECT_OBJECT_ROOT` | Existing trusted service-account POSIX object directory, mode 0700, no final symlink |
| `GROUP33_PHOTO_INSPECT_BATCH_SIZE` | Optional 1–100, default 25 |
| `GROUP33_PHOTO_INSPECT_MAX_PHOTOS` | Optional 1–1000, default 100 |
| `GROUP33_PHOTO_INSPECT_MAX_BYTES` | Optional declared-byte budget1–67108864, default 33554432 |
| `GROUP33_PHOTO_INSPECT_MAX_MILLIS` | Optional elapsed budget1–30000ms, default 30000; uses monotonic time |

The operator needs CONNECT, schema USAGE and SELECT on `medication_photo` and `photo_upload_operation`. The tool rejects superuser/BYPASSRLS/CREATEDB/CREATEROLE, schema owner/CREATE permission and write grants on either inspected relation, including inherited grants. Provision permissions separately; the tool never grants them. It has no configurable remote host/JDBC URL, Supabase key or cloud Storage scan. It does not create a missing object root or repair file permissions.

## Interpretation

One primary read-only Repeatable Read transaction covers metadata counts and keyset pages. Qualified identifiers avoid implicit schema routing. SQL is limited to 3s with a 1s lock budget; connect/socket budgets are 3s/5s. Each page has at most 101 rows, including one lookahead. Declared payload and elapsed budgets bound object work; an individual object is limited by the existing 5242880-byte contract. A current operation may still publish between metadata and file observations: this is not a cross-storage atomic snapshot.

Only known READY metadata objects are read. The tool streams and verifies private regular-file length/SHA256 without returning content, paths, photo/actor IDs or fingerprints. Missing and corrupt observations are findings, not confirmed orphan objects. Unavailable database, denied storage, incomplete limits and interruption are explicit incomplete results; none is successful empty inventory. STAGING and REJECTED operations are counted as protected reservations and never probed, expired, replaced or deleted. Existing files not named by READY metadata, including `.part` and unreferenced files, are not enumerated or modified.

The single JSON report contains only completion and aggregate counts plus `deletionAuthorized:false`. No per-object identifiers or raw exceptions appear. Counts on an incomplete report describe only work completed so far. Exit codes:

| Exit | Meaning |
|---|---|
| 0 |Complete selected metadata scope, no missing/corrupt findings|
| 2 |Complete selected metadata scope, missing/corrupt observations exist|
| 3 |Incomplete due to limits, database/storage unavailable or interruption|
| 64 |Invalid command/private settings, sanitized error only|

Exit 0 does not prove the absence of unreferenced files, concurrent changes or platform problems. A limit has no public resume cursor in this slice: retry with a suitably reviewed larger finite budget or use a later reviewed operator pagination interface; never treat a truncated prefix as complete coverage. File access time may change as a normal effect of reading; content, mtime, permissions and business rows are not modified.

## Recovery and future deletion boundary

If an object was written but READY/receipt commit is unknown, the original STAGING reservation and private object remain available to the existing same-request/key retry. Rejected records retain their original identity/rejection evidence. Accepted receipts and plans are not rewritten in response to a missing file observation. No file is eligible for deletion merely because it is older than 24h: current READY/Medication references, live upload/recovery lease and reconciled concurrent state must first be handled by a separate reviewed protocol/transaction. This slice implements none of those new lease/delete semantics.

Ordinary service/file/settings cases and real local PostgreSQL cases are described in [S2 evidence](evidence/S2_PHOTO_RECONCILIATION.md). No real cloud data, phone/V2, Android/normal-App/two-device or fully disconnected LAN acceptance is implied. Prerequisite main landing and non-author persistence/security review remain required before normal main integration or operational deployment.

## Complete visibility and SELECT-only credentials

Admission rejects column-level INSERT/UPDATE/REFERENCES grants as well as table-level privileges, including inherited roles. On PostgreSQL17 and later, MAINTAIN is also refused; the PostgreSQL16 path never asks the server about an unsupported privilege name.

The snapshot sets transaction-local `row_security=off` to make a query fail if RLS would filter its scope. This setting does not bypass a policy or grant extra visibility; filtered data becomes DATABASE_UNAVAILABLE, never a complete empty inventory. It does not disable table RLS, change policies or repair/grant permissions. Original read-only/Repeatable Read/primary/SQL budgets and no-deletion result remain.

The dedicated job-owned PostgreSQL17 CI runs the complete operator regression profile, including the two maintenance-grant cases. Local PostgreSQL16 explicitly skips only those unsupported-version cases; its other25 executions include all eleven original controls and fourteen direct/inherited-column or deny-all/partial-RLS counterexamples. Actual final-head results are recorded in the PR, not inferred from this document.

Primary semantics: [column/table privilege functions](https://www.postgresql.org/docs/16/functions-info.html#FUNCTIONS-INFO-ACCESS-TABLE), [row-security error instead of filtering](https://www.postgresql.org/docs/16/runtime-config-client.html#GUC-ROW-SECURITY), [PostgreSQL17 MAINTAIN privilege](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-INFO-ACCESS-TABLE).
