# ADR-0007: Use recorded decisions, protected main, and squash-based delivery

## Status

Accepted by the project lead on 2026-10-16.

Delivery clarification accepted on 2026-10-17: [ADR-0009](0009-two-stage-baseline-and-gate1-acceptance.md) separates initial development-baseline acceptance from full Gate 1 completion. Mandatory CI, non-author and specialized review remain; six future member contributions are Stage B obligations, not circular prerequisites for obtaining the Stage A foundation.

Publication note: this governance record was renumbered from the unpublished duplicate 0005 to 0007. ADR-0005 on occurrence generation and ADR-0006 on the demonstration fallback retain their existing identities and decisions.

## Context

Six members need to work in parallel without relying on private conversations, permanent role branches, or unwritten merge decisions. Product and technical contracts already exist, but daily delivery also needs one authoritative workflow and automated policy checks.

## Decision

The repository uses `docs/ENGINEERING_PLAYBOOK.md` as its operating agreement. Every task begins as a Ready GitHub issue, uses an issue-numbered short-lived branch, and ends in a reviewed pull request. Pull-request titles and pushed commits use Conventional Commits. `main` is protected and uses squash merge only. Required CI, resolved review conversations, and at least one non-author approval are mandatory. Shared Android/Server contracts require both perspectives.

The project lead owns integration, release decisions, and recorded backup assignment. Any teammate may act as backup, but backup work does not permit self-approval or bypass specialized review.

Chat or meeting decisions become binding only after they are copied into an issue, contract review, or ADR. Accepted ADRs and contracts take precedence over issue or PR discussion.

## Consequences

- GitHub repository settings must match the documented branch protection and squash policy.
- PR metadata and repository hygiene are checked in CI.
- The team must complete `docs/TEAM_ROSTER.md` before feature issues are declared Ready.
- Large or cross-boundary changes may require coordinated PRs, but each must remain buildable and link the shared decision.
- The workflow adds some documentation and review cost in exchange for traceability, safer integration, and reliable handover.
