# ADR-0006: Rehearse a fully cloud-independent local demonstration fallback

## Status

Accepted by the project lead on 2026-10-16, baseline `T01-T07/2026-10-16-r1`, T07. Recorded in [Issue 12](https://github.com/BRMRs/group33/issues/12); implementation and acceptance are tracked through [Issue 20](https://github.com/BRMRs/group33/issues/20) and remain outstanding. Supplements ADR-0004; selected cloud providers are unchanged.

## Context and trade-off

A local Spring Boot process that still calls Supabase cannot cover a cloud data/photo outage. A full local Supabase control stack would increase setup burden. The fallback must exercise the real product flow, not a pre-seeded final screen.

## Decision

Run the same Spring Boot artifact with local PostgreSQL and a private local-file Storage adapter. Use the same business API, migrations, anonymous installation/JWT/relationship authorization and photo staging/READY protocol as cloud deployment. Cloud remains Render + Supabase PostgreSQL/Storage. Storage implementations vary at an internal adapter seam, not through different client business contracts.

Preprepare APKs, runtime dependencies and images. With cloud egress unavailable and LAN intact, two Android devices must perform real login, explicit Pairing, photo handoff, plan setup, reminder/action/feedback and offline recovery; service restart must retain business data and photo bytes. No Render, Supabase, cloud auth/CDN or runtime download can be required. Environment profiles configure JDBC/Storage/JWT/API base; keep session, Room, outbox and upload intents isolated. Do not migrate cloud identities or pending reports automatically.

## Consequences

- S2 delivers the private file adapter, local persistence/configuration and actual Server functionality; S1 delivers environment isolation; feature owners integrate their own flows and tests; C3 coordinates rehearsal evidence.
- Maintaining two Storage adapters and a local runtime/rehearsal costs extra work, but the local test now covers all chosen cloud providers being unavailable.
- A local server connected to cloud data is no longer sufficient acceptance. Team sign-off and actual recorded two-device tests are mandatory and still outstanding.
- This ADR is specification only; no deployment, migration or feature code is included.
