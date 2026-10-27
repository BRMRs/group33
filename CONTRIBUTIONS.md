# Contribution log

Update this file weekly. Use links to GitHub issues and pull requests after the repository has its first commit.

| Week | Member | Issue or responsibility | Pull request or commit | Verification evidence | Status |
|---|---|---|---|---|---|
| 1 | XinyanXue — S2 | [#12](https://github.com/BRMRs/group33/issues/12): pairing-code string compatibility and numeric-to-string decoding | [PR #44](https://github.com/BRMRs/group33/pull/44) | `FeatureTransportContractReviewTest`: original code/field/list counterexamples fail before the fix; Shared/Server regression, HTTP errors and required repository checks recorded in PR | Implemented; awaiting BRMRs Server backup and tianiridescent Android review |
| 1 | BRMRs — agent-assisted documentation; not six members’ code credit | [#23](https://github.com/BRMRs/group33/issues/23): unify ELDER_REMINDER_V2 docs/Issues, preserve prototype evidence | docs/23-elder-ui-baseline; documentation PR linked from #23 | Policy/link checks, current manifest and scaffold regressions recorded in PR; V2/device acceptance still pending | Documentation review, not feature delivery |

Contribution percentages are agreed by the whole team near submission. Commit count alone is not a contribution measure; working behavior, tests, reviews, integration, and documented problem solving also matter.


2026-10-22：负责人已确认的产品规则、初版参数及六人分工随文档PR #25统一；独立S1代码在[PR #37](https://github.com/BRMRs/group33/pull/37)，不计入本PR的代码贡献。各人正式review/同版签署仍待完成。

| 1 | XinyanXue — S2 | [#10](https://github.com/BRMRs/group33/issues/10): pairing-code issuance service and process-local atomic store | [PR #47](https://github.com/BRMRs/group33/pull/47) | 17 service tests: role/replay/binding/expiry/collision/rotation/quota/concurrency/rollback; gate1Check test, lint/build/policy/hash evidence in PR #47 | Implemented; non-author review and HTTP/persistence acceptance pending |
| 1 | XinyanXue — S2 | [#10](https://github.com/BRMRs/group33/issues/10): pairing issuance HTTP compatibility on main64 | Local main64 preparation; PR #47 dependency, no new formal branch/PR | Same 17 existing real Boot/security tests plus current main regressions; final results recorded locally | Prototype; identity/scope/reviewer confirmation pending |
| 1 | XinyanXue — S2 | [#16](https://github.com/BRMRs/group33/issues/16): pairing-code PostgreSQL Store prototype | Local snapshot only; depends on unchanged #47 service files; no new PR/commit | PostgresPairingCodeStoreTest: 22 real PostgreSQL tests, independent JVM replay, database restart, concurrency, SQL/commit rollback and encrypted receipt checks; docs/evidence/S2_PAIRING_POSTGRES_PROTOTYPE.md | Local preparation; scope, migration/security review and final-baseline PR pending |
2026-10-26 — XinyanXue (S2), Refs #10: local pairing-request consumption, participant detail, replacement cascade and attempt limits; 32 new test executions plus unchanged 17-test issuance regression. [Scope and limitations](docs/evidence/S2_PAIRING_REQUEST_CONSUMPTION.md). No PR/CI, HTTP, active relationship or persistence completion claimed.
2026-10-26 — XinyanXue (S2), Refs #16 / #10: local PostgreSQL pairing-request adapter/schema, 22 new real-DB tests plus 22 issuance DB regressions; [scope and evidence](docs/evidence/S2_PAIRING_REQUEST_POSTGRES.md). No production migration, HTTP or formal PR completion claimed.
2026-10-27 — XinyanXue (S2), Refs #10: local accept/reject/cancel/revoke service, atomic in-memory relationship/receipt transitions and current relationship lookup; 35 new test executions plus unchanged 49 pairing regression cases. [Scope and limitations](docs/evidence/S2_PAIRING_DECISIONS.md). No production authorization wiring, PostgreSQL decision persistence, PR or CI claimed.
2026-10-27 — XinyanXue (S2), Refs #16 / #10: local PostgreSQL pairing-decision/relationship persistence with forward prototype SQL and 25 new database cases; prior 44 database cases retained with schema-fixture updates. [Scope and evidence](docs/evidence/S2_PAIRING_DECISION_POSTGRES.md). No production migration, HTTP/Bean, PR or CI completion claimed.
