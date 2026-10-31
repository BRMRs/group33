# Elder-scoped Medication and plan collection indexes

2026-10-31 — XinyanXue / S2, Refs #16 / #14. Parent is Draft #99 at `42f44ebe7889d97d96fd679cd42c309a5b24fbd9`, not a claim that its prerequisites have landed on main. Main was observed as `b2aeb492`. Earlier business, snapshot, timeout and migration implementations retain their original attribution.

## Change and measured counterexample

The existing JDBC collection queries filter Medication, ReminderPlan and retained PlanRevision by `elder_id`, then preserve their existing ID/revision ordering. Their older integrity indexes lead with resource IDs. V10 adds three non-unique B-tree access paths: Medication `(elder_id, id)`, ReminderPlan `(elder_id, id)`, PlanRevision `(elder_id, plan_id, revision)`. It changes no query, endpoint, response, authorization, transaction isolation, field, receipt or business record. All V1–V9 migration and prototype SQL bytes are preserved.

Three new actual PostgreSQL planner tests fail before implementation and pass afterward with unchanged assertions. They use the installed PostgreSQL17 runtime, normal scan settings and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, TIMING OFF)`, not `enable_seqscan=off` or a forced planner hint. A genuine service-created photo/relationship/confirmed-and-edited plan is the selected foreground. Another 6,000 complete fictional FK/CHECK-valid plan graphs are generated with SQL solely as planner workload. Their metadata does not represent real uploads or user/device acceptance; no real medical data or external service is used.

| Existing collection | Before | After | Buffer accesses before → after |
|---|---|---|---|
| Medication | sequential scan,6,000 unrelated rows filtered | elder-conditioned index scan |107 →3|
| ReminderPlan | sequential scan,6,000 unrelated rows filtered | elder-conditioned index scan |150 →3|
| retained PlanRevision | sequential scan,6,000 unrelated rows filtered | elder-conditioned bitmap index/heap scan plus sort |164 →9|

The selected row counts remain1/1/2. Buffer counters are accesses in this selective local sample, not distinct disk reads, an elapsed-time speedup, or a universal production guarantee. The revision planner legitimately retains a sort. Different cardinalities or statistics may choose a different plan. PostgreSQL's [multicolumn-index guidance](https://www.postgresql.org/docs/17/indexes-multicolumn.html) explains the leading-key choice; its [EXPLAIN documentation](https://www.postgresql.org/docs/17/using-explain.html) describes the measured plan/counters.

## Versioning, rollout and rollback

The separate administrative tool now targets version 10; the application's read-only release manifest appends the pinned Flyway checksum for V10. Existing checksums and SQL are immutable. A managed V9 schema must be explicitly upgraded before starting the matching required-gate release. The gate rejects V9 without modifying it. A V9 administration binary also refuses V10 history. This requires a coordinated maintenance release, not independently changing the database under an assumed compatible older binary.

V10 is transactional and intentionally uses ordinary `CREATE INDEX`, with transaction-local 1-second lock and 30-second statement limits. An ordinary build can block writes, as documented by [PostgreSQL CREATE INDEX](https://www.postgresql.org/docs/17/sql-createindex.html). Plan an operator-reviewed write pause, verified backup/recovery and matching application/tool versions. This is not a zero-downtime or large-production online-index procedure. No CONCURRENTLY exception is added to the existing grouped migration policy.

The three names must be unused: there is no `IF NOT EXISTS` that could accept an unrelated index silently. A busy middle table or conflicting last index name fails closed; a real PostgreSQL failure rolls back the earlier index DDL and new migration-history insertion. Existing objects/data remain untouched and the same migration succeeds after resolving the precondition. A network disconnect or unknown commit response is not proof of rollback: reconnect and inspect/validate the managed history before retrying, without clean/repair/baseline or deleting history. After a confirmed V10 commit, use a matching release or an independently reviewed restore/forward plan; do not downgrade by removing history rows.

Manual prototype schemas remain opt-out compatible but do not acquire V10 automatically. Unmanaged nonempty schemas still require a separately reviewed adoption/conversion. The application has no Flyway dependency, migration SQL or startup DDL. No Supabase/Render configuration or schema was touched.

## Verification and limits

Six new real PostgreSQL cases cover the three actual planner access paths, V9→V10 preservation of business rows and their `xmin`, both immutable confirmation/edit receipt replays, retained revisions, current caregiver revocation, whole-index rollback on name collision, and actual table-lock failure/retry. Required read-only startup behavior and existing migration/rollback/future-history cases retain their assertions with current-version expectations updated to10. The future-history fixture is 11; original late V9 failure injection remains at V9.

The initial test-only fixture had an uninitialized JUnit `@TempDir` beforeAll field; it was fixed with lifecycle parameter injection and retained separately, not counted as a product counterexample. The subsequent three planner failures are the actual before-implementation evidence. No assertion was relaxed to turn them green.

Reproduce using existing JDK17/SDK35 and offline dependencies: `./gradlew gate1Check test :server:schemaMigrationDist --offline`; with explicit dedicated local PostgreSQL settings, `./gradlew :server:postgresIntegrationTest --offline`. Final results: 747 ordinary report instances (Server627, Shared18, Android Debug51/Release51), zero failures/errors/skips; 286 uncached real PostgreSQL cases (280 inherited plus six new), also all passed. Server and both Android variants executed; Shared was FROM-CACHE. Six separate commands run from the actual old/new migration distributions prove V9 installation, unchanged read-only refusal before upgrade, V9→V10 migration, repeat validation/migration, and old-release refusal of the newer history, including their two expected failure exits. Actual bootJar/tool separation and matching V10 resources passed. Source hashes, full task/cache modes and exact CI are recorded in the external `S2-plan-query-indexes-20261031` packet and PR. Disposable test schemas and private fixture password files are removed, and the dedicated instance is restored to its prior stopped state.

Named persistence/Server review and actual prerequisite integration remain required. This does not implement #96 phone/installation/session binding, #24 group/actions/follow-up/probes, real Android/HTTP/Room consumption, full history/snapshot API, formal cloud adoption, photo cleanup or two-device acceptance. No downloaded tool, installer, dependency cache or prior deliverable was deleted.
