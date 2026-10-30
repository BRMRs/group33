# Explicit versioned schema administration

Refs #16; S2 implementation candidate, not approval to modify a deployed database or complete V2.

## Tool and scope decision

S2 selects Flyway Community **11.7.2**, matching the existing Spring Boot 3.5.16 dependency BOM, with its PostgreSQL module and the already selected PostgreSQL driver. Versions live in the catalog. This is the first proposed ordered migration sequence for the existing identity/pairing/photo/plan/occurrence persistence slice; specialist review and actual prerequisite main integration remain required. No new business/wire schema is invented while #24 is pending.

The [Java API](https://documentation.red-gate.com/flyway/reference/usage/api-java) owns ordering, migration checksums, history and PostgreSQL locking/transactions. The [pinned PostgreSQL implementation](https://github.com/flyway/flyway/blob/flyway-11.7.2/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLDatabase.java) supports transactional DDL and PostgreSQL 17. These upstream capabilities are separately verified against the dedicated local test database; they do not imply cloud acceptance.

The independent `schemaMigration` source set builds an administrative distribution. Flyway, its versioned migration resources and its entry point are absent from the application `bootJar` and never execute at ordinary Server startup. Existing prototype resources remain in the application unchanged. The application retains its existing explicit runtime/schema guards. There is no automatic production release gate or deployment/configuration change in this PR. Deployment operators must successfully validate the schema before starting the application; automatic deployment integration is a separate reviewed step.

## Initial sequence and immutability

`server/src/schemaMigration/resources/db/migration/V1__...sql` through `V9__...sql` are byte-for-byte copies of the inherited prototypes. Their old comments identify provenance; the administrative runner, history and review status define how these copies are used. Pairing consent/revocation, private photo receipts, retained plan revisions, deferred completeness/immutability triggers and original occurrence receipts keep their existing meaning. Prototype files remain available for existing independent adapter tests. This PR claims the migration mechanism, not authorship of inherited business SQL or a complete V2 migration.

Once a version enters main, never edit or rename it: correct it with a later forward migration. Capability/group/action/follow-up/notification tables are not silently folded into these versions. The released tool requires exactly its known applied sequence and rejects future, missing, changed or incomplete history; it cannot run an old release against an already newer schema.

All pending versions run in a single PostgreSQL transaction (`group=true`, no mixed nontransactional statements). Flyway records `group33_schema_history` in the explicitly selected schema. Concurrent migrators use Flyway's PostgreSQL lock; lock retries are bounded. The CLI exposes only `migrate` and `validate`, never `clean`, `repair`, `baseline` or `undo`. `validate` is read-only, verifies original checksums and rejects pending versions. An error exits nonzero with a redacted message; no private cause, connection string, SQL or credential is echoed.

## Explicit invocation

Build with JDK17: `./gradlew :server:schemaMigrationDist`. Extract `server/build/distributions/group33-schema-migration.zip` to a private tool directory. Export these settings from a protected local environment or secret manager, not from source control, chat or command-line arguments:

| Setting | Purpose |
|---|---|
| `GROUP33_MIGRATION_URL` | `jdbc:postgresql://host:port/database`; no embedded user, password, query or fragment |
| `GROUP33_MIGRATION_USER` | Explicitly selected migration role |
| `GROUP33_MIGRATION_PASSWORD_FILE` | Private regular file, no symlink or group/other permissions; password with at most one final newline |
| `GROUP33_MIGRATION_SCHEMA` | Existing isolated lower-case schema; public/system schemas forbidden |
| `GROUP33_MIGRATION_SSL_ROOT_CERT_FILE` | Required for non-loopback hosts; TLS always uses `verify-full` there |

Provision the schema and grants separately using reviewed administrator access. Use a separate migration role and runtime role for deployment; this tool does not create roles/databases/schemas, grant privileges or manage Storage. Use a direct or Session pooler connection compatible with a complete migration transaction; do not assume a transaction pooler works. No live Supabase/Render connection has been exercised by this change.

Run `bin/schema-migration migrate`, then `bin/schema-migration validate`. The equivalent local developer tasks are `:server:schemaMigrate` and `:server:schemaValidate`. Ordinary CI builds the distribution and runs configuration/provenance tests but requires no database secrets. The explicitly configured `:server:postgresIntegrationTest` runs real migration/upgrade/failure/concurrency/process tests against the guarded loopback database; those tests do not silently skip missing configuration.

For a new installation the schema must be empty. For an upgrade it must already have valid matching Flyway history. An existing manually initialized prototype/Supabase schema is deliberately refused, even if some table names match: no automatic baseline adoption. Its backup, exact structural/data comparison and explicit conversion/export into a separately provisioned managed schema require their own reviewed operator plan. Do not remove the existing schema or history to bypass this guard.

## Failure, rollout and rollback

The follow-up [read-only startup gate](SCHEMA_STARTUP_GATE.md) can explicitly require matching release history for `postgres-local`; its flag defaults off and it never executes migrations or changes existing cloud configuration. The administrative tool remains separate. Supabase/Render branch integration and manual-schema adoption remain independent reviewed work.

Before rollout record the tool revision, database/schema and current history using private operator records; take and test a database backup. Validate with the matching release before/after a reviewed maintenance migration. An unsuccessful pending transaction leaves previously applied versions and business receipts intact; resolve the cause and retry the same immutable tool. Never repair checksums merely to make validation green. A checksum failure or future history requires the matching reviewed release or investigation.

There are no destructive down migrations. Application rollback is permitted only after compatibility is demonstrated for that application/schema pair; otherwise use a reviewed forward correction or tested backup restoration. The initial sequence does not remove old prototype data, alter the deployed test project or complete phone authentication, V2 actions/snapshot/alerts, full Android integration or formal Gate acceptance.
