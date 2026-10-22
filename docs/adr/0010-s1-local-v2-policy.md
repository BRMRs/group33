# ADR-0010: Prepare the initial S1 V2 local policies

## Status

Accepted for local S1 implementation by project lead Rui Zheng on 2026-10-22. Later in the same working session the lead authorized committing, pushing and opening a Draft PR containing only this S1 change. Merge remains unauthorized. This records the lead's latest product/initial-parameter instruction, not six human reviews or S2 protocol approval. References: [#24](https://github.com/BRMRs/group33/issues/24), [#12](https://github.com/BRMRs/group33/issues/12), [current product document PR #25](https://github.com/BRMRs/group33/pull/25).

## Context

Main is a buildable r1 scaffold; current V2 product authority is recorded in #23/PR #25. S2's wire/group identity, durable transactions and notification transport still need joint design. S1 can independently implement deterministic Android-local timing, conclusion and recovery decisions without inventing routes or replacing feature owners' code.

## Decision

- No deployed old App users exist; first release needs no old-client compatibility or existing-user data migration.
- Group commands are accepted or rejected as a whole in each local/Server transaction. No distributed transaction or implemented persistence is claimed by this policy-only slice.
- For an effective follow-up conclusion, an authorized current Caregiver entry takes priority over Elder reports. All original facts/audit history remain required; Caregiver Unable to confirm stays unresolved. No Caregiver entry means timely Elder reports continue to count. No report does not mean Not taken.
- Preserve T+30m notification, [T,T+2h) Elder response, T−5/T+5 probes and ten-minute snooze without threshold extension. Help/Taken/acknowledged takeover end prompting.
- Implement the initial 60s probe waits and 15s visibility target/60s check limit. An unexecuted probe is incomplete, not a device failure. A matched response before the final decision prevents an alert; after an existing alert it is connection recovery, not medication resolution. These are recorded occurrence facts: wall-clock rollback does not undo an already received response or already created alert, and recorded response causality comes from verified attempt matching rather than timestamp ordering.
- Unacknowledged transmission retries nominally occur at 15s/60s after the first attempt, with no more than three attempts. Anchor the successive 15s/45s gaps to actual sends so recovery cannot burst overdue retries. After confirmed device receipt, unresolved follow-up reminders occur at +2m/+5m; exhausting reminders does not resolve the case. If the first reminder runs late, keep at least the nominal three-minute gap before the second instead of bursting overdue reminders after recovery. These are initial parameters authorized for implementation and still subject to S2/device feasibility review.
- Existing command recovery preserves original operation identity: unknown completion resumes original work, confirmed/cache-pending only refreshes, NOT_SUBMITTED/REJECTED require explicit action, and changed environment/installation/user rejects old recovery.

## Consequences and boundaries

Pure policies use an injected Clock and public inputs. Actual scope/authentication, matching a probe to the right round, current accepted Caregiver revision, durable journal/outbox transactions, atomic work claims and network/notification/AlarmManager execution belong to later real adapters. Calling a policy does not send, persist or deliver anything. No arbitrary Shared DTO, API or Room schema is frozen.

This is a separate S1 code branch from main, not code inserted into the docs-only #25 workspace. E/C1/C2/C3 keep their feature work; S2 keeps Server behavior and transport. Agent-assisted tests are not member sign-off, real process recovery, group transaction acceptance or dual-device proof.
