# Contributing

The complete operating agreement is [Engineering playbook](docs/ENGINEERING_PLAYBOOK.md). It is mandatory for every issue, branch, commit, pull request, review, and release. Accepted ADRs and contracts take precedence if a lower-level instruction conflicts with them.

## Before starting a task

1. Create or assign a GitHub issue with an acceptance criterion.
2. Confirm which files or interface the task owns.
3. Check whether another open pull request changes the same shared file.
4. Branch from the latest `main`.

Branch names include the primary issue number and use one of these forms:

```text
feature/<issue-number>-<short-name>
fix/<issue-number>-<short-name>
chore/<issue-number>-<short-name>
docs/<issue-number>-<short-name>
test/<issue-number>-<short-name>
refactor/<issue-number>-<short-name>
```

Do not create permanent branches for a person, the elder app, the caregiver app, or the server.

## Commit and pull request rules

- Keep a branch focused on one issue.
- Use Conventional Commits such as `feat(plan): add caregiver reminder-plan form`.
- Do not commit secrets, real medical data, `local.properties`, or generated build output.
- Pull requests require at least one teammate review.
- Shared Android/Server contracts require both perspectives to review.
- The author resolves conflicts and confirms the final build.
- Prefer small pull requests that can be reviewed in one sitting.
- Use squash merge; the PR title becomes the final commit subject.

## Definition of done

Initial publication uses the [two-stage acceptance workflow](docs/adr/0009-two-stage-baseline-and-gate1-acceptance.md): Stage A delivers a buildable, agent-navigable foundation; Stage B retains all six actual contributions, code/test PRs and named Android/Server same-version Gate 1 sign-off. Future member feature contributions do not block obtaining the foundation. The sole [PR #22 publication-approval exception](docs/adr/0009-two-stage-baseline-and-gate1-acceptance.md#one-time-pr-22-publication-exception--2026-10-17) permits its explicitly authorized squash release after checks and requires immediate restoration of regular approvals. PR #22 references #12 without closing it; an onboarding prompt grants no merge permission.

A task is done only when:

- the acceptance criterion works on a device or emulator;
- relevant tests pass;
- loading, empty, and failure states are considered;
- user-facing strings exist in English and Chinese resources;
- no real personal or medical data is present;
- the pull request explains how to verify the change;
- the contribution record or issue links to the pull request.

## Shared files

Changes to these areas should be discussed before implementation:

- `shared/src/main/.../model/`
- `docs/API_CONTRACT.md`
- root Gradle configuration
- Android manifest permissions
- server authentication and persistence configuration

## Local checks

Run these before requesting review:

```bash
python3 scripts/check_repository_policy.py
./gradlew test
./gradlew :android-app:lintDebug
./gradlew :android-app:assembleDebug
```

