# Explicit local recovery copy

Refs #16. The separate administration ZIP now includes `bin/manual-schema-copy`.
It replaces an ad hoc copy step in the earlier fictional restore rehearsals with
one repeatable command. The application still performs no migration/copy, and
the operator class, launcher and catalog queries stay outside the application JAR.

## Supported outcome and limits

Copy the 18 legacy V1–V8 prototype tables from one **already restored loopback**
schema to a different, freshly migrated, empty schema in the same local database.
Updated on main `bbe4997` and its actual V1–V10 migration sequence. Dose V10 target tables are explicitly part of the reviewed target set and must remain empty; this tool copies only the original18 legacy tables.
Existing migrations are immutable. The supported target relation set must be
reviewed with any later schema change; this tool does not reserve V11 or
silently accept a future release. The separate #116 then #102 order is unchanged.

This is a **local recovery rehearsal operator**, not a remote/cloud adoption or
cutover command. Explicit loopback IPs are required; `localhost`, remote hosts,
URL credentials/options/encoded paths and source=target are rejected. Inspecting
a reference captures selected metadata; it does not certify its safety. Review
both references against the intended, freshly migrated release before copy.
The tool does not download a cloud backup, read/copy/delete image files, retain
keys, stop deployed writers, change a role, execute DDL, create migration history,
baseline/repair Flyway, rename/drop a schema or switch a service.

## Procedure

1. Restore the approved backup into an isolated local database under appropriate
   local credentials. Keep the backup, original receipt/signing keys and matching
   private object copy under their existing protection. Do not use cloud
   credentials as local login credentials.
2. Separately provision a new target and use the matching `schema-migration`
   distribution to migrate/validate that empty target. Use this same ZIP/release
   for the following operation. Runtime users remain separate from administration.
3. Privately configure the existing `GROUP33_MIGRATION_URL`, `_SCHEMA` (target),
   `_USER` and `_PASSWORD_FILE`. Add `GROUP33_COPY_SOURCE_SCHEMA` and absolute
   `GROUP33_COPY_SOURCE_CATALOG_FILE` / `GROUP33_COPY_TARGET_CATALOG_FILE` paths
   within a 0700 directory. Password/reference files must be private, non-symlink
   regular files; do not put actual values in Git, console output or issue comments.
4. Run `bin/manual-schema-copy inspect-source` and `inspect-target`. These use
   read-only Repeatable Read and fixed SELECT-only catalog queries, then roll
   back. Reference files are created with0600 and CREATE_NEW; existing references
   are never overwritten. Independently review the exact source/target provenance,
   catalog and expected release. A hash captured after an unreviewed change is
   not approval of that change.
5. Quiesce **both schemas' writers and privileged DDL/role activity**. Explicitly
   acknowledge review and maintenance with
   `GROUP33_COPY_ACK=LOCAL_RESTORE_CATALOGS_REVIEWED_WRITERS_AND_DDL_PAUSED`, then run
   `bin/manual-schema-copy copy`. The process does not provide distributed
   maintenance, protect against a concurrent superuser, or control a deployed app.
6. Verify the matching runtime, original operation receipts, identities/relations,
   plans/revisions, keys and private image bytes in the isolated copy. Only a
   separately reviewed cloud conversion/cutover plan can adopt such a result.

## Transaction and failure boundaries

The operator revalidates the target's actual Flyway history, then uses a single
Read Committed transaction. Source SHARE locks block ordinary DML; all supported
target tables/history receive ACCESS EXCLUSIVE locks. After locks it rechecks
exact relation sets, selected catalog fingerprints and the private history-row
reference. The target must be empty except the one built-in issuance guard.
Legacy column names/types/nullability/order must agree; generated/identity columns
are unsupported. Unknown source/target relations fail closed.

After deferring the existing constraints, explicit columns copy through
`INSERT ... SELECT`, leaving source rows and target history untouched. Deferred
constraints are forced before COMMIT. Length-prefixed SHA256 row comparisons
check both source and target; catalog/history references are checked again.
New occurrence tables remain empty: a data conversion does not manufacture events.
Only a confirmed COMMIT prints success.

Lock timeout is750ms, server/driver statement timeouts are bounded, and copy work
has a cooperative30-second budget. Each legacy table is limited to10,000 rows,
50,000 in total, with bounded catalog rows/cell sizes. These limits intentionally
reject larger rehearsals. Initial connection and separate Flyway validation have
their existing independent bounds. A lost final COMMIT acknowledgement is an
**unknown outcome**: preserve both schemas and inspect, never assume rollback or
automatically submit another copy. A confirmed target is not overwritten on retry.

Table locks complement the operator's maintenance window; they do not freeze all
functions, roles or privileged DDL. The selected catalog reference is a drift
guard, not generic semantic equivalence or a complete PostgreSQL catalog audit.
See PostgreSQL's [locking](https://www.postgresql.org/docs/17/explicit-locking.html)
and [isolation](https://www.postgresql.org/docs/17/transaction-iso.html) rules.

## Verification and review

`ManualSchemaCopySettingsTest` covers private references, formats, redaction and
connection rejection. `PostgresManualCopyToolTest` uses fictional real PostgreSQL
and the actual packaged launcher: data/receipt replay, rollback after a statement
failure, schema/index/trigger/CHECK/history drift, source/target lock conflicts,
row budget, overwrite refusal, receipt-key failure and separate object recovery.
The dedicated CI job runs these and the existing schema-migration regressions.
Two additional real-PostgreSQL cases inject the **same JDBC08006** at the commit
boundary: close the transaction before COMMIT, or actually commit in PostgreSQL
then close the connection before returning to the caller. Independent connections
prove an empty rolled-back target in the first case and fully persisted original
rows in the second. The tool does not automatically resubmit either; a committed
target rejects another copy, and code/plan original receipts replay unchanged.
This is explicit JDBC-boundary fault injection around real PG work, not a claim
of cutting real packets or testing a live cloud network. The original mid-copy
statement-failure case remains separate. A connection error alone never proves
rollback. Test counts/results belong to the exact published head, not to real cloud data.

S2 owns this administration slice. Non-author persistence/security and lead
integration review remain required. This does not close #16 or accept phone/V2
contracts, an actual cloud migration, production cleanup, or two-device/LAN work.

中文：本命令只用于已有备份在本机恢复后的受控复制，先独立审查目录参考、停止写入及
特权DDL，再复制到匹配版本的空目标。失败保留两侧并检查最终状态；不会连接或修改云库，
不会迁移、清理照片或自动切换服务。真实云升级与完整双端验收仍按原流程另审。

## 2026-11-17 main integration

Merged main without rewriting branch history. The first32-case run found20 setup failures because the fixture expected9 migrations; updated that release expectation and explicitly reviewed the two empty dose V10 target tables in the allowlist. V1–V10 SQL and application code stay unchanged. Source scope, loopback-only connection, catalog/history pinning, all-target-empty check, locks, transaction rollback and unknown-commit single-attempt boundaries are preserved. Original failure logs retained; final results are reported on the PR.

## 2026-11-18 public-entry and reference-path boundary correction

All reachable CLI/library entry points reject URL query/options before requesting a connection, including the former non-private guard mode and empty `?`. Public data sources whose getURL encodes even benign options are rejected; callers must supply a query-free loopback URL. For authenticated library use, `loopbackSource(url,user,password)` validates the raw URL and creates the bounded connection source; it does not copy arbitrary driver properties. The driver itself renders default properties into a standard PGSimpleDataSource.getURL, so such rendered query URLs are not accepted as recovery inputs. The CLI retains fixed5s connect/30s socket budgets through an internal allowlisted properties set, not caller-controlled URL parameters. Application/schema migration configuration is unchanged.

Reference paths must be absolute/normalized, with a non-symlink immediate parent of exactly0700 and a non-symlink regular file of exactly0600. Read/write fix the canonical parent path after rejecting a symlinked immediate parent and checking exact mode/file identity. Before/after checks verify parent identity/mode and regular-file identity/mode; file opens use NOFOLLOW and writes use CREATE_NEW. Read at most4097bytes to enforce the4096-byte limit. This works on the existing POSIX Mac/Linux fixture environments without requiring SecureDirectoryStream support. A moved0600 file in a public parent or a symlinked parent is rejected. This boundary assumes the operator controls the private directory; it is not a general sandbox against malicious same-process code.

Direct sentinel data-source tests exercise inspect-source/inspect-target/copy override URLs without any real network connection; old implementation fails them. Original PG fixtures now separate scoped application sources from query-free tool sources without removing rollback/unknown-COMMIT assertions. Final full and realPG/launcher results are recorded on the PR.
