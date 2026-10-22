# Team roster and review map

This file is the authoritative mapping from the six responsibility slots to people. A feature issue cannot become Ready until its primary role and required reviewers are assigned below.

The project lead is **Rui Zheng (`BRMRs`)**. The lead owns integration, build health, release decisions, and backup assignment.

The project lead confirmed the assignments below on **2026-10-22**. The same assignments are recorded in [Issue #24](https://github.com/BRMRs/group33/issues/24). This table is published for review in [Draft PR #25](https://github.com/BRMRs/group33/pull/25); it is not yet the merged main roster. An assignment records who owns the work; it does not record a completed review, approval, or Gate 1 signature.

Under [ADR-0009](adr/0009-two-stage-baseline-and-gate1-acceptance.md), the earlier Stage A onboarding rehearsals used role slots without named members. Those rehearsals are not reviews or signatures by the people now assigned. Ordinary feature Ready requirements and all Stage B member contributions remain unchanged.

Any teammate may be assigned as backup for any role. The lead records the active backup in the affected issue; there are no permanent backup silos. Backup assignment does not permit self-approval or remove mandatory Android/Server perspective review.

| Role | Member | GitHub handle | Primary review boundary | Status |
|---|---|---|---|---|
| E — Elder client | Tang | `Katherine-Tang` | Elder UI, actions, photo capture, accessibility | Assigned |
| C1 — Caregiver identity and pairing | iridescentian | `tianiridescent` | Auth/pairing, relationship UI, shared language resources | Assigned |
| C2 — Caregiver Medication and plans | Lingyu Wang | `Iris-wly` | Photo viewing, Medication/plan forms, validation and time zone | Assigned |
| C3 — Caregiver feedback and evidence | Cle-cre | `Cle-cre` | Dashboard/history, refresh, status/evidence mapping | Assigned |
| S1 — Android infrastructure | Rui Zheng | `BRMRs` | Room, networking/session, sync/outbox, alarms and recovery; project lead | Assigned |
| S2 — Server and deployment | XinyanXue | `XinyanXue` | Server business behavior, persistence, Storage and deployment | Assigned |

One person may also be the project lead, but each person has one primary responsibility slot. Temporary backup work does not change the primary mapping.

## Required review combinations

| Change | Required perspectives |
|---|---|
| Feature-local Android UI/ViewModel | Feature owner or recorded backup plus one non-author reviewer |
| Shared model/API/error/time/sync contract | S1 or Android infrastructure backup, and S2 or Server backup |
| Authentication/authorization | C1 perspective and S2/Server perspective |
| Reminder plan semantics | C2 perspective plus S1 and S2 perspectives where storage/sync changes |
| Dose action/history semantics | E/C3 perspective plus affected S1/S2 perspective |
| Room/WorkManager/AlarmManager | S1 or Android infrastructure backup |
| PostgreSQL migration/Storage | S2 or Server/persistence backup |
| Shared UI tokens/language | C1 perspective and E accessibility perspective |

Review and merge this table through PR #25 so the whole team can use the same main-branch assignments. Update `.github/CODEOWNERS` in a reviewed change to encode the stable review map where possible; this roster update does not change automatic reviewer routing.
