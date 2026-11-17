# Elder-scoped Medication and plan collection indexes

2026-11-17 — XinyanXue / S2, Refs #16 / #14, existing PR #102.

## Current-main integration

Based on actual main `bbe4997034ecb837d2093f4e73267cc530976f29` after #116 merged, following the [lead decision](https://github.com/BRMRs/group33/pull/102#issuecomment-6786998096). Keep dose-action V10 and all V1–V10 SQL bytes unchanged; append the original #102 index SQL byte-for-byte as V11. Admin tool VERSION and the read-only release manifest both advance to 11, with Flyway checksum -96611416. The original PR branch ancestry is retained; its former #99 parent is no longer an unmerged prerequisite.

Three non-unique B-tree indexes lead with elder_id: medication (elder_id, id), reminder_plan (elder_id, id), reminder_plan_revision (elder_id, plan_id, revision). No business query, HTTP, authorization, receipt, Shared, Android, dependency or workflow changes.

## Verification scope

The original planner assertions are unchanged. A service-created confirmed/edited plan is measured against 6,000 fictional FK/CHECK-valid background graphs, using default planner settings and EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON, TIMING OFF). The fixture starts at dose V10 and measures the isolated V11 addition. Selected row counts must be equal; each elder-leading access path must be used and touch fewer than one tenth of the original buffer accesses. These are selective sample counters, not elapsed-time or universal production promises.

The retained confirmation/edit receipt and row/xmin preservation case covers both V9→V11 and V10→V11. Original conflict and table-lock rollback/retry checks remain. Five coordination cases reuse the earlier [compatibility candidate](https://github.com/BRMRs/group33/pull/102#issuecomment-6719713397): original dose receipt replay and current revocation; read-only V10 refusal/V11 validation; rollback of preceding index DDL after a middle-name conflict; fresh V1–V11 install; refusal of an already-applied index-V10 history without repair or data rewrite. Migration release expectations follow VERSION; main's original late V10 failure injection is retained.

The original 2026-10-31 red/green planner evidence and 747 ordinary/286 PG counts belong to the original head, not this revision. Likewise the earlier [184-assertion/54-HTTP packaged recovery rehearsal](https://github.com/BRMRs/group33/pull/102#issuecomment-6721840873) belongs to its stated historical inputs and is not claimed as rerun here. Current results are recorded after execution below.

## Rollout and rollback

V11 uses ordinary transactional CREATE INDEX with local 1-second lock and 30-second statement limits. Index names must be unused; no IF NOT EXISTS silently accepts an unrelated object. The grouped migration rolls back pending DDL/history on a known failure, and succeeds after the operator resolves the precondition. Plan an operator-reviewed write pause, verified backup including matching photo objects/keys, and a coordinated application/admin release. This is not an online or zero-downtime migration.

The matching required-gate application refuses V9/V10; an older release refuses V11. On an unknown commit response reconnect and inspect/validate history before retrying. After confirmed commit use the matching version or an independently reviewed restore/forward plan; never delete history to downgrade. Already-applied original index-V10 databases are explicitly refused and require separate conversion review. Never rename an applied migration, repair checksums or assume they contain dose V10.

The application only validates history and contains no migration SQL/CLI/Flyway. Unmanaged nonempty schemas require separately reviewed adoption. No cloud DDL, real business data, phone/V2 contract decision, new deployment, Android/Room consumption or two-device acceptance is included. Non-author persistence/Server review remains required.

## 2026-11-17 validation result

- Full gate1Check/test plus Release APK/admin ZIP: 905 ordinary report instances, zero failures/errors/skips. Server628 and Android Debug130/Release129 executed; Shared18 FROM-CACHE. Lint: 0 errors/41 warnings. 134 tasks:109 executed/25 cached.
- Full uncached PostgreSQL task:335 report instances, zero failures/errors/skips, using fictional local schemas; database restored STOPPED. This includes 3 planner cases,4 preservation/rollback cases and5 named coordination cases. The coordination subclass also inherits42 existing contract cases; these repeated executions are not47 new independent cases or new feature contributions.
- V10→V11 default planner sample: medication107→3, plan150→3, revision164→9 buffer accesses; selected rows1/1/2 remain identical. V9/V10 original confirmation/edit receipts and V10 dose receipt replay preserved.
- All11 packaged SQL resources match source; application JAR contains the matching read-only manifest and no Flyway/admin CLI/migration SQL.391 review hashes, policy and diff checks pass.
- Initial sandbox attempt could not open Gradle's cache lock; it ran no tests. The authorized execution above passed. No production counterexample or relaxed assertion is claimed.

The runtime/SQL/test inputs above were validated before adding this results paragraph; final metadata/checksum verification follows the same inputs. Remote CI is recorded on the PR at its actual commit, not inferred from local success. No additional devices or packaged HTTP/restore rehearsal were run in this revision.
