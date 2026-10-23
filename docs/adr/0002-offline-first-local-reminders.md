# ADR-0002: Use Room-backed local reminders with server synchronization

## Status

Accepted for P0; presentation/alert sufficiency partially superseded by [ADR-0008](0008-photo-first-elder-reminders-and-caregiver-handoff.md). Current behavior below is ELDER_REMINDER_V2.

## Context

An already synchronized elder reminder must continue to work without network connectivity, while caregiver changes and elder outcomes must eventually converge through the shared service. Android process death and reboot must not erase future reminders or locally recorded elder actions.

## Decision

Room stores the last successfully synchronized local copy and drives the Android UI and local reminders. The shared service holds the mutually recognized reminder-plan version and accepted dose-event results. AlarmManager presents local reminders; WorkManager retries deferrable synchronization after connectivity returns, process restart, and reboot. Elder actions are recorded locally first with a stable logical identity, retained internally as `Pending sync`, and become `Synced` only after service acceptance. Rejected actions are preserved as `Sync needs attention`. These technical states do not appear on ordinary Elder Home/reminder screens; Caregiver presentation reflects only known received facts. Explicit sync remains the data mechanism, but refresh alone is not sufficient for the required scheduled two-probe connection-failure, Help and thirty-minute nonresponse alerts; delivery design is tracked in V2-T04/T05.

## Consequences

- Caregivers can view stale cached data offline but cannot create or edit reminder plans offline.
- Elders can execute already synchronized plans offline. The original two-hour cutoff is enforced locally; snooze, restart and reconnection never extend it. Help/acknowledged takeover also suppress this occurrence's repeated prompting. The thirty-minute Caregiver notification alone does not. Connection checks occur at T−5/T+5 minutes and alert only when both fail; no all-day heartbeat is required.
- Caregiver follow-up and separate auditable outcome entry are required; a remote takeover is not claimed applied until device acknowledgement.
- Retries must be idempotent and must not duplicate caregiver results.
- Persisted future reminders must be restored after reboot and first unlock; expired or Taken events must not be replayed.
- [ADR-0004](0004-hosting-storage-and-explicit-sync.md) fixes Render hosting, Supabase PostgreSQL, Supabase Storage, and the local demonstration fallback. Schemas and concrete worker/receiver classes remain implementation choices as long as they preserve these behaviors.
