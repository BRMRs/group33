# Contribution log

Update this file weekly. Use links to GitHub issues and pull requests after the repository has its first commit.

| Week | Member | Issue or responsibility | Pull request or commit | Verification evidence | Status |
|---|---|---|---|---|---|
| 2 | BRMRs (S1; agent-assisted correction) | [#10](https://github.com/BRMRs/group33/issues/10), stale auth and failed logout persistence; original phone feature by tianiridescent | [PR #88](https://github.com/BRMRs/group33/pull/88) | [Lifecycle fence and integration limits](docs/contracts/PHONE_AUTH_HANDOFF.md) | Non-author security review; phone-to-P0 contract and common session wiring pending |
| 1 | BRMRs (S1) | [#17](https://github.com/BRMRs/group33/issues/17), private Room command journal subset | [PR #45](https://github.com/BRMRs/group33/pull/45) | [Device tests and limits](docs/S1_COMMAND_JOURNAL.md#verification-2026-10-23), 12 API27 instrumentation cases; JVM/lint/build | Team review and full sync integration pending |
| 1 | XinyanXue — S2 | [#12](https://github.com/BRMRs/group33/issues/12): pairing-code string compatibility and numeric-to-string decoding | [PR #44](https://github.com/BRMRs/group33/pull/44) | `FeatureTransportContractReviewTest`: original code/field/list counterexamples fail before the fix; Shared/Server regression, HTTP errors and required repository checks recorded in PR | Implemented; awaiting BRMRs Server backup and tianiridescent Android review |
| 1 | BRMRs — agent-assisted documentation; not six members’ code credit | [#23](https://github.com/BRMRs/group33/issues/23): unify ELDER_REMINDER_V2 docs/Issues, preserve prototype evidence | docs/23-elder-ui-baseline; documentation PR linked from #23 | Policy/link checks, current manifest and scaffold regressions recorded in PR; V2/device acceptance still pending | Documentation review, not feature delivery |

Contribution percentages are agreed by the whole team near submission. Commit count alone is not a contribution measure; working behavior, tests, reviews, integration, and documented problem solving also matter.


2026-10-22：负责人已确认的产品规则、初版参数及六人分工随文档PR #25统一；独立S1代码在[PR #37](https://github.com/BRMRs/group33/pull/37)，不计入本PR的代码贡献。各人正式review/同版签署仍待完成。
