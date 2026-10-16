# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues in `BRMRs/group33`. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --repo BRMRs/group33 --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --repo BRMRs/group33 --json number,title,body,labels,comments`.
- **List issues**: `gh issue list --repo BRMRs/group33 --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`, with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --repo BRMRs/group33 --body "..."`.
- **Apply / remove labels**: `gh issue edit <number> --repo BRMRs/group33 --add-label "..."` / `--remove-label "..."`.
- **Close**: `gh issue close <number> --repo BRMRs/group33 --comment "..."`.

## Pull requests as a triage surface

**PRs as a request surface: no.**

## When a skill says “publish to the issue tracker”

Create a GitHub issue in `BRMRs/group33`.

## When a skill says “fetch the relevant ticket”

Read it with `gh issue view <number> --repo BRMRs/group33`.
