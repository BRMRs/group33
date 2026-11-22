# S2 review: edit conflict and deactivation consumers

2026-11-22. Refs #53, #108, #114. XinyanXue authorized agent-assisted technical
re-review and publication. No formal approval or integration signature is recorded.

Both latest branches contain main `b5acc05224612bf25797ee71a5cdf1a879b67d91`.
Their feature implementations and original tests are byte-for-byte unchanged from
the last S2 technical review; synchronization introduced no new Server/Shared change.
The old main-synchronization concern is addressed at the inspected commits. S1's
existing CHANGES_REQUESTED reviews still require S1's own review decision.

| Input | Current reviewed commit | Prior S2 reviewed commit | Current GitHub CI |
| --- | --- | --- | --- |
| [#108](https://github.com/BRMRs/group33/pull/108) | `a89e4a9c58ab9ce299844f6b55f6cf763d7ea1ed` | `860442e9d81d0697590f7d6bae13e39570c84e73` | [44991556221](https://github.com/BRMRs/group33/actions/runs/44991556221), success |
| [#114](https://github.com/BRMRs/group33/pull/114) | `0364724b8d4e48e5181cf2ff43e56890abca053c` | `c19a6131e143c2d379e9cfd23788f627c9ce0e0d` | [44991632888](https://github.com/BRMRs/group33/actions/runs/44991632888), success |

## Conclusions

No new S2 blocker was found in either declared local state-consumer scope.

- #108: input and original expected revision remain fixed through conflicts;
  adoption of a newer matching active plan is explicit. Unknown completion never
  rebases or automatically dispatches a new command. The original two synchronous
  close-before-I/O counterexamples pass unchanged: no write occurs after close has
  returned before Repository entry. Late results, cancellation, duplicate dispatch,
  recovery identity and confirmed/cache-pending separation remain guarded.
- #114: construction performs no write; explicit deactivation captures one plan,
  revision and command. Independent probes cover concurrent dispatch, close during
  dispatch, dispatch/recovery cancellation, restored applied confirmation, seven
  malformed receipt variants, foreign command responses and cache recovery failure.
  They preserve unknown identity and prevent a confirmed commit from being downgraded
  or replayed. There is no event/history writer or hard deletion in this component.

## Independent verification on these commits

| Check | #108 | #114 |
| --- | --- | --- |
| Full Android JVM Debug / Release, executed | 154 / 153 | 144 / 143 |
| Server / Shared full reports, FROM-CACHE | 628 / 18 | 628 / 18 |
| Original focused cases per variant | 19 | 9 |
| External review cases per variant | 11, unchanged from 2026-11-05 | 8 |
| Combined focused run Debug / Release, executed | 30 / 30 | 17 / 17 |
| Verified original Git paths / manifest entries | 447 / 398 | 445 / 396 |

All listed reports have zero failures, errors or skips. Full and focused runs overlap;
do not add their counts as distinct tests. `gate1Check`, `test`, `lintDebug`, Debug and
Release APK assembly, repository policy and diff checks passed on both original
snapshots. Each lint report has zero errors and 29 existing warnings. Server/Shared
cached reports are not new executions. External tests are absent from both APKs.
No author tracked source/test file was edited. Machine-readable input trees, task
modes, source verification and probe hashes are in [results.json](s2-plan-state-review-20261122/results.json).

## Reproduce the focused review

Use JDK 17 and Android SDK 35, with repository Gradle dependencies available. Obtain
this evidence directory from this PR and use a separate clean checkout of the
original PR. Set `review_evidence` to the absolute packet directory, then check out
the exact input commit from the table. For #108:

```sh
git checkout a89e4a9c58ab9ce299844f6b55f6cf763d7ea1ed
./gradlew --init-script "$review_evidence/probes.init.gradle" \
  -Ds2.review.probes="$review_evidence/pr108" \
  :android-app:testDebugUnitTest --tests '*PlanEditDraftTest' --tests '*PlanEditOperationTest' --tests '*S2PlanEditReviewTest' \
  :android-app:testReleaseUnitTest --tests '*PlanEditDraftTest' --tests '*PlanEditOperationTest' --tests '*S2PlanEditReviewTest' \
  --offline --console=plain --max-workers=1 --no-parallel --no-daemon
```

For #114, check out `0364724b8d4e48e5181cf2ff43e56890abca053c`, use `pr114`,
and select `*PlanDeactivationTest` and `*S2PlanDeactivationReviewTest` for each task.
The init script adds external tests only to unit-test sources. The evidence PR's
ordinary CI checks the evidence tree; it does not execute both unmerged consumers.

## Remaining scope

Trusted production host/journal.begin binding, HTTP/Room adapters, actual process
recovery, authorization changes, bilingual confirmation UI, device/accessibility and
cross-device #53 acceptance remain separate. Fake Repository/journal records here
do not prove durable storage, Server transactions, frozen-event retention in the
deployed system or delivery to an Elder. Both original PRs remain partial deliveries.

The PR descriptions still cite older heads and test counts; use the fixed commits
and current CI above for this review. Those historical values are not new results.
The implementation owner can refresh the descriptions separately. This evidence
does not clear another reviewer's requested changes or close #53.
