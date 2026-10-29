# Contribution log

Update this file weekly. Use links to GitHub issues and pull requests after the repository has its first commit.

| Week | Member | Issue or responsibility | Pull request or commit | Verification evidence | Status |
|---|---|---|---|---|---|
| 2026-10-24 | Iris-wly (C2), agent-assisted implementation | [#46](https://github.com/BRMRs/group33/issues/46): form validation and online-save state slice; parent [#14](https://github.com/BRMRs/group33/issues/14) remains open | [PR #43](https://github.com/BRMRs/group33/pull/43) | 9 PlanFormViewModelTest + 12 PlanSaveViewModelTest methods; final-head commands, manifest/policy checks and CI evidence recorded in PR #43. Repository doubles prove caller behavior only; no production/device acceptance claimed. | Review requested; S2 correction under verification, renewed human self-review and BRMRs/XinyanXue approval pending. Follow-ups #50–#54. |
| 1 | BRMRs (S1) | [#17](https://github.com/BRMRs/group33/issues/17), private Room command journal subset | [PR #45](https://github.com/BRMRs/group33/pull/45) | [Device tests and limits](docs/S1_COMMAND_JOURNAL.md#verification-2026-10-23), 12 API27 instrumentation cases; JVM/lint/build | Team review and full sync integration pending |
| 1 | XinyanXue — S2 | [#12](https://github.com/BRMRs/group33/issues/12): pairing-code string compatibility and numeric-to-string decoding | [PR #44](https://github.com/BRMRs/group33/pull/44) | `FeatureTransportContractReviewTest`: original code/field/list counterexamples fail before the fix; Shared/Server regression, HTTP errors and required repository checks recorded in PR | Implemented; awaiting BRMRs Server backup and tianiridescent Android review |
| 1 | BRMRs — agent-assisted documentation; not six members’ code credit | [#23](https://github.com/BRMRs/group33/issues/23): unify ELDER_REMINDER_V2 docs/Issues, preserve prototype evidence | docs/23-elder-ui-baseline; documentation PR linked from #23 | Policy/link checks, current manifest and scaffold regressions recorded in PR; V2/device acceptance still pending | Documentation review, not feature delivery |

Contribution percentages are agreed by the whole team near submission. Commit count alone is not a contribution measure; working behavior, tests, reviews, integration, and documented problem solving also matter.


2026-10-22：负责人已确认的产品规则、初版参数及六人分工随文档PR #25统一；独立S1代码在[PR #37](https://github.com/BRMRs/group33/pull/37)，不计入本PR的代码贡献。各人正式review/同版签署仍待完成。

2026-10-25 C2: addressed the [S2 whitespace-instructions finding](https://github.com/BRMRs/group33/pull/43#issuecomment-6382169123). Both new regressions failed before the fix and passed afterward (19 targeted methods). Only empty editor text maps to null; non-empty text is preserved exactly so corrected rejected requests receive a new intent. Full `gate1Check test` passed (19 executed / 73 up-to-date, Shared/Server tests up-to-date); no UI, real-adapter or device acceptance claimed. Local verification precedes commit/remote CI and renewed review.

2026-10-25 C2: addressed S1 review of PR #43 by sorting validated request times under P0_CANONICAL_V1 before intent comparison. Two permutation regressions failed before the fix; unchanged rejected intent stays blocked, unsent intent reuses its ID, and actual time/text corrections remain new intents. User text and form order are preserved. Final validation and CI are recorded on PR #43; renewed S1/S2 review remains required.

2026-10-29 C2 (Iris-wly, agent-assisted): [#51](https://github.com/BRMRs/group33/issues/51) scrollable editor and debug-only host on `feature/51-c2-plan-editor`. [Evidence](docs/evidence/C2_PLAN_EDITOR.md): build/lint/JVM checks and three instrumentation cases on emulator and API27 tablet passed. Owner reports no obvious issue in Debug trial. Draft depends on unmerged #43/#58; real host/photo/network and full accessibility acceptance remain pending.

2026-10-29 C2 follow-up: [Draft PR #86](https://github.com/BRMRs/group33/pull/86), six device tests on tablet/emulator and bilingual 320dp/200% screenshots added; [evidence](docs/evidence/C2_PLAN_EDITOR.md). Dependencies and remaining human/production acceptance stay explicit.
