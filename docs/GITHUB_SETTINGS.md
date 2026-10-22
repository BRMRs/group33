# Required GitHub repository settings

The project lead applies and periodically verifies these settings for `BRMRs/group33`. They are not fully expressible in committed files, so this document is the auditable configuration checklist.

## General pull-request settings

- Enable squash merge.
- Disable merge commits.
- Disable rebase merge.
- Use the pull-request title as the default squash commit title.
- Automatically delete head branches after merge.
- Keep Issues enabled.

## `main` branch protection or ruleset

Apply a ruleset targeting `main`:

- Require a pull request before merging.
- Require at least one approving review.
- After the six GitHub handles are recorded and `.github/CODEOWNERS` includes at least one non-lead stable owner, require review from CODEOWNERS.
- Dismiss stale approvals when new commits materially change the reviewed revision.
- Require all review conversations to be resolved.
- Require the `build-and-test` CI job to pass.
- Require the branch to be up to date before merge, or enable a merge queue with the same check.
- Block force pushes.
- Block branch deletion.
- Do not allow bypass for ordinary member work.

The project lead may administer the repository but follows the same PR and CI path. A GitHub service outage or repository-recovery event is documented after the fact if an administrative repair is unavoidable.

### One-time first-baseline publication

The lead-approved [PR #22 exception in ADR-0009](adr/0009-two-stage-baseline-and-gate1-acceptance.md#one-time-pr-22-publication-exception--2026-10-17) is the only approval exception. During its final merge window only, `required_approving_review_count` changes 1 → 0 → 1, `require_last_push_approval` true → false → true, and `require_extra_approval_for_unattributed_changes` true → false → true. Restore the complete prior ruleset, not just those fields; verify effective main rules after success or failure. This paragraph records authorized procedure, not a claim that settings have already changed or been restored. Exact execution/readback evidence belongs in PR #22 / #12. CI, conversations, strict current-main checks, active protection, empty bypass list and squash-only remain enforced throughout; all later PRs require ordinary approvals.

## Labels

Create and use these labels consistently:

| Label | Purpose |
|---|---|
| `needs-triage` | Maintainer evaluation required |
| `needs-info` | Waiting for required information |
| `ready-for-agent` | Fully specified and safe for an agent |
| `ready-for-human` | Fully specified and requires human/device/account work |
| `in-progress` | Owner and active branch exist |
| `in-review` | Non-draft PR is ready for review |
| `blocked` | A named dependency or decision prevents progress |
| `wontfix` | Declined with a recorded reason |
| `feature` | Feature work |
| `bug` | Defect work |
| `decision` | A recorded technical decision is required |
| `security` | Authentication, authorization, secret, privacy, or vulnerability impact |
| `contract` | Shared Android/Server behavior or wire contract |
| `migration` | Persistent schema or data migration |

Labels describe state and risk; they do not replace the accountable owner or acceptance criteria.

## Collaborator and environment access

- Grant the minimum access needed for each member.
- Do not share personal access tokens or cloud credentials.
- Render and Supabase access is granted by named account, not a shared password.
- At least the project lead and one recorded deployment backup must be able to recover the deployed environment before the formal demonstration.
- Repository secrets are environment-scoped where possible and are never copied into Issues, PRs, Actions logs, screenshots, or local example files.

## Verification record

After the first push and whenever settings change, the project lead records the date and evidence here:

| Date | Verified by | Ruleset/settings evidence | Notes |
|---|---|---|---|
| 2026-10-16, 23:38 Asia/Shanghai | `BRMRs`, lead-authorized agent execution | [Active main ruleset 25250513](https://github.com/BRMRs/group33/rules/25250513); repository settings and effective branch-rule API readback | Configuration applied and independently read back; not a teammate review or Gate 1 signature |

### Applied configuration and effective-rule verification

The lead explicitly authorized applying these settings on 2026-10-16. This entry supersedes the initial publication's historical “settings pending” notes; the product decisions, role assignments and Gate 1 acceptance criteria are unchanged.

| Control | Observed configuration / evidence |
|---|---|
| Merge methods | Squash enabled; merge commits and rebase disabled, both at repository level and in the main PR rule |
| Squash title / branch cleanup | `squash_merge_commit_title=PR_TITLE`; `delete_branch_on_merge=true` (no branch was deleted during this verification) |
| Protected target | Exact `refs/heads/main`; ruleset `main-reviewed-squash-delivery`, ID `25250513`, `enforcement=active`; branch API reports `protected=true` |
| Bypass | `bypass_actors=[]`; current administrator reports `current_user_can_bypass=never` |
| Required CI | `build-and-test`, restricted to GitHub Actions integration `15368`; `strict_required_status_checks_policy=true`, so the branch must be tested against current main |
| Required approval | `required_approving_review_count=1`; stale approvals dismissed; `require_last_push_approval=true` requires someone other than the latest pusher to approve the final reviewable push. This does not allow a PR author to approve their own PR |
| Discussion resolution | `required_review_thread_resolution=true`, present in the effective main rules |
| Force push / deletion | Effective `non_fast_forward` and `deletion` rules enabled with no bypass actors |
| CODEOWNERS | `require_code_owner_review=false` until the six handles and a non-lead stable owner are recorded, as required above; the temporary lead-only map is not a substitute for independent approval |

Verification used read-only `GET /repos/BRMRs/group33`, `/rulesets/25250513`, `/rules/branches/main`, `/branches/main`, and the PR GraphQL state after applying the configuration. The effective-branch endpoint confirms enforcement on main, not merely that a ruleset was saved. No push to main, merge attempt, force push, deletion, synthetic approval or synthetic review thread was used as a probe.

PR #22 readback immediately after activation: `isDraft=true`, `merged=false`, `state=OPEN`, `autoMergeRequest=null`, `reviewDecision=REVIEW_REQUIRED`, `mergeStateStatus=BLOCKED`, no submitted reviews and no review threads. Its then-current head `0fd7848a14fa3d88eee1e99215ce832ee73c669b` had a successful `build-and-test` check from integration 15368 ([CI evidence](https://github.com/BRMRs/group33/actions/runs/38924492725)). A later documentation commit must pass its own required CI; the earlier success must not be attributed to a new SHA. With no existing threads, discussion enforcement was verified through active-rule readback, not by claiming an exercised unresolved-thread rejection.

Six real role reviews, each member's attributable code/test PRs, and named Android/Server same-revision sign-off remain mandatory under Issue #12. GitHub's one-approval minimum is not six-role acceptance. The activation-time Draft state above is historical; query PR #22 for current publication status. Gate 1 remains OPEN after publication. Final commit/manifest identity, CI, merge and approval-restoration evidence are recorded in [Issue #12](https://github.com/BRMRs/group33/issues/12) and [PR #22](https://github.com/BRMRs/group33/pull/22).
