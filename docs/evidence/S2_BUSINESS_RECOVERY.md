# Independent local business backup/recovery evidence

## Current-main fixture repair — 2026-11-25 (BRMRs)

This review branch now includes main `9e5621393c3d130bc65ec6754d0531c363c9cf9d`,
including the already-applied dose V10 release. The original recovery fixture still
asserted exactly9 migrations. On a newly initialized,owned PostgreSQL16.15 cluster,
all three original cases failed in setup with `expected9/actual10`,before business
seeding,backup or recovery. This is a stale fixture precondition,not evidence of
production data loss or a business restore failure.

The setup now requires the current release's `SchemaMigrations.VERSION.toInt()`
applied count. Every case still migrates a new random schema and validates restored
migration history; no V1–V10 SQL or production code is changed. All original dump/
restore,new-database/object-root,17-table count,original receipt/replay,edited history,
revocation,wrong-key and missing-object assertions remain.

The corrected three cases actually passed on PostgreSQL16.15 using matching
`pg_dump`/`pg_restore`. A dedicated local application role and independent fixture
administrator were created only in a fresh owned cluster before the opt-in suite;
that cluster,temporary key and private fixture files were removed after the run.
No existing local service,cloud database or real data was touched. This is the same
quiescent single-schema/service-factory scope described below,not an independent
HTTP application JVM,full dose/V2 recovery,production backup or approved cloud rollout.
The dated nine-migration/PostgreSQL17 evidence below retains its original execution.

2026-10-31 — XinyanXue / S2, Refs #16 / #20. Base exact Draft99 `42f44ebe7889d97d96fd679cd42c309a5b24fbd9`. This adds three explicit PostgreSQL recovery integration cases and a separate opt-in test task; production business code, Shared/Android models, SQL/migrations and runtime configuration remain unchanged.

## What actually runs

Each case applies the existing nine versioned migrations to a random private source schema and uses real existing services to create two anonymous fixture identities with fixed roles, an accepted pairing, a private photo, an initial Medication/plan/revision/receipt and a later edit with its original receipt. It captures a custom-format schema archive with the installed PostgreSQL 17 `pg_dump`, plus verified immutable photo bytes in a private backup directory. Fixture writes are quiescent across the database/object capture.

The original schema is renamed and original object root is moved away before recovery; a lookup through the old identity source is required to fail. An explicitly created new local database, owned by the existing fixture application role, is restored through real `pg_restore --single-transaction --exit-on-error --no-owner --no-acl`. The test verifies the destination database name, counts across 17 tables, migration validation and unchanged encrypted pairing receipt bytes. Objects are written to a new private root via the existing immutable adapter. This uses [pg_dump custom archives](https://www.postgresql.org/docs/17/app-pgdump.html) and [pg_restore single-transaction restoration](https://www.postgresql.org/docs/17/app-pgrestore.html); no `--clean`, production replacement, role creation or automatic migration adoption is exposed.

Three real cases prove:

1. Fresh DB/object root preserve current identities, photo content, initial versus edited plan history, original pairing/photo/confirmation/edit receipts and side-effect-free exact replay. A subsequent real revocation survives runtime reconstruction and denies caregiver photo/plan access while the elder retains history.
2. The wrong external receipt key rejects the real persistence startup factory with a sanitized cause-free failure, preserving restored data. The correct external key permits reconstruction.
3. READY metadata alone cannot serve a missing private object: content returns the existing unavailable result. Restoring the original backup bytes repairs the same immutable object identity without altering business records.

This is real subprocess/database/filesystem/service evidence, not a fake Store. The final startup check uses the production configuration factory, not a full Spring HTTP host or a separate application JVM. The independent `recovery-refusal` check proves an invocation without explicit opt-in fails at Gradle preflight before any fixture can connect. The initial test compilation had a JUnit import ambiguity, fixed with explicit annotation imports; that compiler log is not described as a product defect or red business counterexample.

## Reproduction and isolation

Run `./gradlew :server:postgresRecoveryIntegrationTest --offline --console=plain` with privately supplied settings. Required existing settings are `S2_PG_INTEGRATION_URL` (exact dedicated loopback URL `jdbc:postgresql://127.0.0.1:55432/group33_s2_local`), `S2_PG_INTEGRATION_USER` (`group33_local_app`), `S2_PG_INTEGRATION_PASSWORD`, and `S2_PG_RECEIPT_KEY_FILE` (the private fixture receipt key). Additionally set `S2_PG_RECOVERY_ENABLED=local-fixtures-only`, `S2_PG_RECOVERY_ADMIN_PASSWORD` for this local fixture's `s2_local_admin`, and `S2_PG_RECOVERY_BIN` to the already installed PostgreSQL 17 bin directory. No real values belong in Git or public logs. The suite does not initialize this cluster or roles.

Only random `s2_restore_it_<32hex>` databases created by the current case are dropped; only that case's random source/retired schema is cleaned. Archive/object/tool-log files are private 0600 under a 0700 test backup directory and removed with the JUnit fixture. Existing installed binaries, private configuration, ordinary project data and dependency caches are preserved. Commands carry no passwords in argv; child output stays in private test files and failures report only safe status. The suite fails rather than skipping without explicit settings, is uncached and serial, and is excluded from ordinary `test` and the existing `postgresIntegrationTest`.

Normal `./gradlew gate1Check test` and `./gradlew :server:postgresIntegrationTest` continue to require no recovery administrator setting. Final local checks passed: 747 ordinary report instances (Server 627, Shared 18, Android Debug/Release 51 each), 280 inherited real PostgreSQL cases and three new real recovery cases, with zero failures/errors/skips. Server and both Android variants executed; Shared FROM-CACHE. Both database tasks were uncached. The no-opt-in preflight refusal was verified, owned fixture cleanup succeeded and the local instance stopped. Exact hashes and publication are in the external delivery packet and PR.

## Scope that remains open

This is a quiescent single-schema logical recovery rehearsal with an existing local role and separately retained receipt key, not a production backup tool, online cross-store consistent backup, full-cluster/PITR/role recovery or approved cloud conversion. Database role/ownership/ACL mapping is supplied by this fixture, not recovered by the archive flags. The archive excludes external secrets and private object storage. Source fixture data is fictional; no Supabase/Render, real account/medical data, supplier/SMS or device is used. It does not freeze #96 phone binding or #24 V2 actions/history/probes, change the public API, prove full HTTP/process/MIUI/two-device acceptance, or replace normal non-author review and prerequisite integration.
