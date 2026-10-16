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
| Pending verification | Rui Zheng (`BRMRs`) | Record actual post-push readback in Issue #12 | Three-file bootstrap and Draft PR publication authorized; checking in this checklist does not apply GitHub settings |
