# S2 pairing-code PostgreSQL Store: local prototype

2026-10-26. Refs [#16](https://github.com/BRMRs/group33/issues/16); service behavior comes from [#47](https://github.com/BRMRs/group33/pull/47), not from a new wire contract.

## Input and scope

Private snapshot of main `64ddd4e26440d60a60f209dd7eb12d102e2a55dd` plus three unchanged files from #47 `3dcb4a8a8824826b64cd6f97d0fa71eed4e851f9`: PairingCodeStore.kt, PairingCodeIssuanceService.kt and its original test. This is uncommitted local preparation, with no official feature branch, PR, remote CI or approved migration/backup assignment. It does not change the actual capability-service checkout.

New production code is an explicitly constructed JDBC Store and a receipt cipher. The existing Store/transaction interfaces and service stay unchanged. The SQL resource is an explicitly applied prototype schema, not an automatic startup migration or a Flyway history. No Bean, Controller, JWT, user Store, Shared field, Android route or global security configuration is registered or changed.

## Behavior and tradeoffs

- One PostgreSQL guard row is locked for the whole callback and commit. READ COMMITTED reads following acquisition see the preceding writer's commit. Multiple connections/processes share the same lock; no JVM mutex supplies correctness. This intentionally retains the original global serialization boundary and limits throughput.
- Code replacement, quota timestamps, binding/rejection evidence and encrypted accepted receipt share one transaction. SQL, callback and deferred commit failures roll back; 55P03 contention maps to the existing CodeStoreBusy / OPERATION_IN_PROGRESS result. Zero wait uses NOWAIT; positive wait uses transaction-local lock_timeout.
- Primary keys enforce one current code per Elder and one operation per actor/UUID. Binding cannot be overwritten. Effective hash exclusivity is enforced by the shared guard and the existing service's hash check; the hash index is not a time-dependent UNIQUE constraint. Expired hash reuse remains legal. This assumes every issuance writer uses the adapter; arbitrary direct SQL writes are not an alternate business API.
- Rolling quota timestamps preserve duplicate milliseconds using ordinals. Exact old success replays without consuming quota or restoring an obsolete active code; recorded rejections retain binding and can be reevaluated after the condition recovers.
- Replayable eight-digit code bytes use AES-256-GCM with a fresh 12-byte nonce, a 128-bit tag, and a versioned envelope. AAD binds actor/operation, installation, target, fingerprint, result ID and expiry. The external 32-byte key has no generated fallback; wrong/missing keys or modified material fail rather than generating a replacement. The active-code table keeps the service's existing hash, not raw code. Key custody/backup/rotation and production privileges remain deployment review work; do not discard the key while receipts must remain replayable.

## Actual PostgreSQL verification

Dedicated local PostgreSQL 17.11 / loopback 127.0.0.1:55432. `PostgresPairingCodeStoreTest` has **22 actual passing tests**, with 0 failures/errors/skips, using real JDBC connections and temporary per-test schemas. All names/actors/images are synthetic; no real medical or identity data is used.

Covered: leading zero and exact expiry; old success after replacement; persistent quota and rejected binding; caregiver rejection; eight simultaneous identical operations and eight distinct operations; cross-Elder collision and expired hash reuse; actual SQL insert and deferred COMMIT failures; callback rollback; NOWAIT and bounded waiting; time sampled after an observed database lock wait; repeated schema application; actor scoping and unique-key failure; immutable binding; encrypted bytes, tampering, changed expiry, wrong/short/mutable keys; two separately started JVMs; actual stop/start of the dedicated PostgreSQL process followed by service replay.

The separate `postgresIntegrationTest` task disables result reuse and only includes the postgres tag. With no explicit configuration it fails; ordinary JVM checks exclude that tag and must not be advertised as database tests. Initial runtime dependency cache misses were resolved online before these tests ran; they were setup failures, not passing business tests.

Reproduction requires explicit `S2_PG_INTEGRATION_URL`, `S2_PG_INTEGRATION_USER`, `S2_PG_INTEGRATION_PASSWORD`, `S2_PG_RECEIPT_KEY_FILE`, `S2_PG_MANAGER_SCRIPT`, `S2_PG_PYTHON`. Tests deliberately allow only the isolated local practice database and create/drop only their own random schema. The restart test actually stops that instance, so run it when no one else is using the practice instance. Credentials and the stable binary key stay in ignored private files outside this repository; never paste their values into commands or review comments.

```bash
./gradlew :server:postgresIntegrationTest --offline --console=plain
./gradlew :shared:test :server:test gate1Check test --offline --console=plain
```

Original XML, build logs, the guarded local runner, source/manifest hashes and applicable patches are retained outside the repo in `S2-pairing-postgres-local/`. That directory's README records the complete standard-build result; the first command supplies database evidence and the second is regression/build evidence.

## What remains

This proves the Store and plain service against PostgreSQL using supplied synthetic actors. Anonymous identity remains process-local; a real HTTP/JWT/login path across a Boot restart is not implemented or proven. Pairing consumption, requests/consent/revocation, real relationships/authorization, durable identities, other business tables, cloud connection/TLS/pooling, production migration history, Storage, Render and complete local fallback/device acceptance remain outside the slice.

Before an official PR, confirm the bounded #16 scope, named non-author Server/persistence/security review and C1/E pairing perspectives, and the dependency/baseline after #47. Do not automatically reuse #47's backup arrangement or treat this as approval of a stacked PR. Rebase/adapt and verify the final commit/CI; #10/#16/#20 remain open.

Primary references: [pgJDBC](https://jdbc.postgresql.org/documentation/use/), [PostgreSQL locking](https://www.postgresql.org/docs/17/explicit-locking.html), [PostgreSQL client defaults](https://www.postgresql.org/docs/17/runtime-config-client.html), [JDK 17 Cipher/GCM](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html).
