# ADR-0004: Use Render, Supabase storage, and explicit Room–Server synchronization

## Status

Accepted for P0 by the project lead on 2026-10-16. Recorded in [Confirm the P0 hosting, storage, and explicit synchronization stack](https://github.com/BRMRs/group33/issues/11). Alert sufficiency is partially superseded by [ADR-0008](0008-photo-first-elder-reminders-and-caregiver-handoff.md); the selected stack remains unchanged. Supplements [ADR-0002](0002-offline-first-local-reminders.md).

## Decision

Android uses Room as its local data source and offline execution foundation. Render runs the Spring Boot service; Supabase PostgreSQL stores structured business data; Supabase Storage stores Medication photos. Synchronization is an explicit Room ↔ Server mechanism and does not require a Realtime database subscription. Required V2 alerting is a separate delivery obligation, not satisfied by refresh alone. A local deployment configuration must also be retained and rehearsed as a fallback for the formal demonstration.

This fixes the hosting and storage responsibilities while preserving the existing local-reminder and authorization boundaries. It commits implementation to the selected providers and adds the maintenance and rehearsal cost of a second deployment configuration.

## Consequences

- Android screens and reminders use Room through the Repository. Application data crosses the Spring Boot API for synchronization; Room ↔ Server is not database replication.
- Spring Boot continues to own anonymous installation login, JWT authentication, active-relationship authorization, and validation. Selecting Supabase for storage does not introduce Supabase Auth or permit Android to bypass these checks.
- PostgreSQL holds structured identities, care relationships, Medication metadata, Reminder plans, and Dose events; photo objects live in Supabase Storage. Final schemas and photo-transfer details remain implementation work.
- Explicit synchronization retains Pending sync, Synced, Sync needs attention, background retry, caregiver refresh, and idempotency behavior from the closed decisions. A Supabase Realtime subscription is not mandated, but current required Help/nonresponse/contact-loss alerts need a reviewed timely notification mechanism (V2-T04/T05), including local fallback. It may not be deferred to P1 or reduced to manual refresh.
- The Render and local configurations must run the same application contract. Local database/photo-service setup, environment selection, and demonstration data preparation still need implementation and documentation.
- Running only Spring Boot locally while retaining cloud data/photo dependencies is not a fully cloud-independent fallback. The fallback's actual dependency coverage must be stated and tested. Do not assume cloud identities or pending actions migrate automatically when switching environments.

No deployment resources, database migrations, or runtime configuration are implemented by this ADR.

## Confirmed fallback coverage — 2026-10-16

The project lead approved T07 as fully cloud-independent: local Spring Boot + PostgreSQL + private local-file Storage, with preprepared dependencies and a real two-device LAN acceptance run while all cloud services are unavailable. [ADR-0006](0006-cloud-independent-demonstration-fallback.md) records the added maintenance cost and environment isolation. Dependency coverage is no longer an open choice; the original cloud provider selection above remains unchanged.
