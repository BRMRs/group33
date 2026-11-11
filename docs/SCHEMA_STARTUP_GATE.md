# Read-only versioned-schema startup gate

Refs #16; follows the explicit migration tool in Draft #90. This is a proposed S2 runtime guard, requiring persistence/security/operator review and actual prerequisite main integration. It does not authorize a cloud schema conversion or claim a complete production rollout.

For a **managed `postgres-local` runtime**, set `PERSISTENCE_REQUIRE_VERSIONED_SCHEMA=true` (Spring property `app.persistence.require-versioned-schema`). The same URL, schema and runtime account already used by business stores are checked before those stores are returned. No separate migration credentials enter the application. Selecting memory with this flag fails rather than silently falling back. Other runtime modes, including the independently maintained Supabase/Render adapter branch, are not supported by this candidate and must be integrated separately without replacing newer configuration.

The application opens one bounded read-only Repeatable Read transaction and compares qualified `group33_schema_history` rows against this release's small manifest. Exact ordered versions, SQL script names, non-null Flyway checksums, SQL type and successful application are required. Missing history, manual schemas, partial releases, changed/missing/future/failed entries or reordered history fail startup. Query timeout is five seconds, and the existing connection/socket limits remain in effect. Neither SQL, credentials nor private exception causes are exposed. Existing business table/column and encrypted-receipt checks still run; migration history alone cannot prove that the live database structure was never altered.

The manifest is release metadata, **not a new migration algorithm**. Its checksums are verified in ordinary CI against the pinned Flyway implementation and every unchanged versioned SQL resource; real PostgreSQL tests compare it with Flyway's actual applied history. Flyway and administrative classes/resources remain absent from the application bootJar. Future forward migration releases must update this metadata together with their reviewed SQL and upgrade tests. Never edit applied history or checksums to satisfy the gate.

## Operator sequence

1. Use a separately reviewed migration account/tool to migrate and validate an explicitly provisioned managed schema. Keep backup and rollback compatibility evidence.
2. Grant the runtime account `USAGE` on that schema, `SELECT` on its migration history, and the existing minimum business privileges. This guard needs no migration/DDL privileges.
3. Configure the existing private runtime settings and opt in to the startup gate. The check runs on every process start; the application never executes migrate, baseline, repair, grants or schema creation.
4. On failure, stop the rollout, identify the correct reviewed application/tool/schema pair and retry after explicit correction. Do not disable the gate or restore an old application against a newer schema as a routine recovery step.

The flag defaults to false to preserve existing manually initialized development/test schemas. This PR does not enable it on Render, connect to Supabase, automatically adopt an unmanaged schema, or add phone/V2/group/action/follow-up tables. It adds startup validation, not a migration executor, a continuous drift detector, complete cloud acceptance or device/fallback acceptance. The application uses no migration secret even when the guard is enabled.

Tests: `RuntimeSchemaHistoryTest` (manifest/pinned-tool provenance and malformed inputs) and `PostgresRuntimeSchemaHistoryTest` (actual history, database-enforced read-only validation, manual/partial/corrupt/future histories, schema isolation, existing business checks and Spring bean startup). The separately configured tagged suite does not silently skip a missing local database. Packaged application behavior is recorded in the linked evidence after execution.
