# Team roster and review map

This file is the authoritative mapping from the six responsibility slots to people. A feature issue cannot become Ready until its primary role and required reviewers are assigned below.

The project lead is **Rui Zheng (`BRMRs`)**. The lead owns integration, build health, release decisions, and backup assignment.

Any teammate may be assigned as backup for any role. The lead records the active backup in the affected issue; there are no permanent backup silos. Backup assignment does not permit self-approval or remove mandatory Android/Server perspective review.

| Role | Member | GitHub handle | Primary review boundary | Status |
|---|---|---|---|---|
| E — Elder client | To assign | To assign | Elder UI, actions, photo capture, accessibility | Unassigned |
| C1 — Caregiver identity and pairing | To assign | To assign | Auth/pairing, relationship UI, shared language resources | Unassigned |
| C2 — Caregiver Medication and plans | To assign | To assign | Photo viewing, Medication/plan forms, validation and time zone | Unassigned |
| C3 — Caregiver feedback and evidence | To assign | To assign | Dashboard/history, refresh, status/evidence mapping | Unassigned |
| S1 — Android infrastructure | To assign | To assign | Room, networking/session, sync/outbox, alarms and recovery | Unassigned |
| S2 — Server and deployment | To assign | To assign | Server business behavior, persistence, Storage and deployment | Unassigned |

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

Update the table in a reviewed PR before assigning the first implementation issues. After GitHub handles are known, update `.github/CODEOWNERS` to encode the stable review map where possible.
