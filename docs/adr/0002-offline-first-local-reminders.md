# ADR-0002: Use Room-backed local reminders with server synchronization

## Status

Accepted for P0.

## Context

An already synchronized elder reminder must continue to work without network connectivity, while caregiver changes and elder outcomes must eventually converge through the shared service. Android process death and reboot must not erase future reminders or locally recorded elder actions.

## Decision

Room stores the last successfully synchronized local copy and drives the Android UI and local reminders. The shared service holds the mutually recognized reminder-plan version and accepted dose-event results. AlarmManager presents local reminders; WorkManager retries deferrable synchronization after connectivity returns, process restart, and reboot. Elder actions are recorded locally first with a stable logical identity, shown as `Pending sync`, and become `Synced` only after service acceptance. Rejected actions remain visible as `Sync needs attention`. P0 does not require real-time push; explicit refresh or periodic synchronization is sufficient.

## Consequences

- Caregivers can view stale cached data offline but cannot create or edit reminder plans offline.
- Elders can execute already synchronized plans offline.
- Retries must be idempotent and must not duplicate caregiver results.
- Persisted future reminders must be restored after reboot and first unlock; expired or Taken events must not be replayed.
- [ADR-0004](0004-hosting-storage-and-explicit-sync.md) fixes Render hosting, Supabase PostgreSQL, Supabase Storage, and the local demonstration fallback. Schemas and concrete worker/receiver classes remain implementation choices as long as they preserve these behaviors.
