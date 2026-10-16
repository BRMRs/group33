# Team briefing for the project lead

## What you need to understand

You do not need to write every feature. You need to protect the boundaries that let everyone work independently.

Be able to explain these statements in your own words:

1. Both roles use the same Android application; their accounts lead to different screens.
2. Activities render UI and forward actions. They do not own network or database code.
3. ViewModels own screen state and survive ordinary Activity recreation.
4. Repositories hide whether data currently comes from memory, Room, or the server.
5. Shared models are the agreement between Android and server code.
6. Room will become the Android source of truth so existing reminders work offline.
7. AlarmManager fires medication reminders; WorkManager handles deferrable sync.
8. OCR only suggests text and always requires human confirmation.
9. The server stores relationships and synchronized state; it does not decide medication treatment.
10. `main` must remain buildable and no member develops for weeks on an isolated branch.
11. Anonymous login restores an installation identity; JWT authenticates API calls; pairing authorizes access to another person's data.
12. The current UI is a placeholder; the shared visual direction and Elder accessibility profile are already confirmed.
13. Room supports local execution; Spring Boot runs on Render; Supabase PostgreSQL and Storage persist business data and photos. Synchronization is explicit through the server, and a local demonstration fallback must be rehearsed.

## What to tell the team at kickoff

### Product rule

We are building remote setup, local reminder, elder confirmation, and caregiver feedback. We are not building a medical decision system.

### Engineering rule

Develop against interfaces and shared models. Do not reach across another feature's package to reuse an internal Activity or mutable object.

### Git rule

Every task starts with an issue and ends with a reviewed pull request. Branches are short-lived and start from the latest `main`.

### Integration rule

Integrate one complete path early. A polished isolated screen is less valuable than a basic flow that crosses Android and server boundaries.

### UI rule

No feature branch invents its own colours, text sizes, spacing, or button style. Apply the confirmed direction centrally through shared UI tokens and reusable components. Elder screens use the same product identity with an accessibility profile, not a separate visual brand.

### Evidence rule

Each member keeps links to issues, commits, pull requests, tests, and demonstrated behavior. Contribution percentages are decided from evidence, not memory.

## Agreed ownership model for six members

The team accepted one Elder client member, three Caregiver client members, and two synchronization/architecture members. Role codes below are agreed responsibility slots; individual GitHub accounts have not yet been assigned.

| Role | Primary responsibility | Integration boundary |
|---|---|---|
| E — Elder client | Elder home and Due screen, Taken confirmation, Remind later, Elder photo capture/selection/upload UI, accessibility | Shared pairing UI with C1; Room and reminder interfaces with S1; photo API with S2 |
| C1 — Caregiver identity and pairing | Shared Android login/session UI, Caregiver pairing, Elder list and relationship controls; shared language settings and UI resources with review from E | Authentication and pairing API with S2; S1 owns token persistence and authenticated networking |
| C2 — Caregiver Medication and plans | Authorized photo viewing, Medication and Reminder plan forms, time-zone confirmation, validation and prospective editing | Medication/photo/plan API with S2; Repository with S1; photo handoff with E |
| C3 — Caregiver feedback and evidence | Current Elder overview, Dose event history, sync timestamps, refresh and capability notices; cross-device acceptance and state/evidence checklist | Dose-event and capability API with S2; local observable data/refresh with S1; recovery evidence with E |
| S1 — Android data and background execution | Infrastructure owner and implementer for Room, shared Repository foundations, authenticated HTTP/session infrastructure, outbox/WorkManager, event timing, AlarmManager and recovery | Co-designs feature Repository contracts and data models with E/C1/C2/C3; each feature owner implements their own integration and tests |
| S2 — Server and deployment | Primary Server feature-code owner: pairing, authorization, Medication/Reminder plan/Dose event controllers and services, idempotency, PostgreSQL repositories/migrations and photo-storage integration; also Render/local deployment | Implements and tests business behavior with each feature owner; CI, Docker or Render configuration alone do not satisfy this role |

Every member must deliver attributable code, tests, and integration evidence linked to a reviewed pull request. E/C1/C2/C3 own their screen/ViewModel code, feature Repository interface and model contributions, DTO-to-domain/UI mapping where needed, and feature-level integration tests with the server. S1 reviews shared infrastructure changes but is not the sole implementer or integrator of Android data access. S2 must deliver the principal Server business APIs and persistence code, not only deployment tooling. Architecture review is shared.

S1 and S2 are likely bottlenecks, especially early. C1 implements auth/pairing Repository integration and shared UI/language resources; C2 implements Medication/photo/plan Repository integration; C3 implements event/history/refresh integration and acceptance tests; E implements photo and reminder-action integration. Shared contracts are agreed before these parallel changes, and each author handles their own integration fixes. A Caregiver owner may also take a bounded Server task under S2's review when needed, while S2 retains the main Server implementation responsibility.

Every feature owner supplies their own tests, bilingual states, and failure evidence; C3 coordinates evidence instead of being the sole tester. The first shared checkpoint is a jointly reviewed API/model/error contract, then the team integrates the seven gates from [Set the P0 integration boundary and delivery sequence](https://github.com/BRMRs/group33/issues/9). See [Implementation handoff](IMPLEMENTATION_HANDOFF.md) for ordinary implementation issues and dependencies.

The project lead role is additional to one row. The lead owns integration, shared contracts, build health, and release decisions, but must still deliver a visible coded feature.

S2 maintains Spring Security and token issuance; S1 owns the Android private session and authenticated client. Other server code must read the authenticated subject and role rather than trusting user IDs sent by the client.

## Thirty-minute kickoff agenda

1. Five minutes: restate P0 and out-of-scope items.
2. Five minutes: draw the six-step user flow.
3. Five minutes: explain modules and dependency direction.
4. Five minutes: assign ownership and first issues.
5. Five minutes: demonstrate branch, pull request, and review rules.
6. Five minutes: agree on the walking-skeleton acceptance test and date.

## Questions to ask during reviews

- Does this change preserve the agreed scope?
- Which layer owns this logic?
- Can the other side continue using a fake implementation?
- What happens with no network or denied permission?
- Does this introduce a new shared field or state?
- How is it tested and demonstrated?
- Is any sensitive information logged or committed?

## First issues to create

1. Confirm all six machines can build and run the Android app.
2. Confirm all six machines can run tests.
3. Implement the confirmed pairing and authorization contract.
4. Implement the confirmed UI direction and Elder accessibility profile.
5. Add Android anonymous login and private token storage.
6. Replace the in-memory Android repository with Room-backed Reminder plans and Dose events.
7. Add an HTTP client behind `MedicationRepository`.
8. Replace the server in-memory stores with persistent storage.
9. Implement one test reminder with AlarmManager.
10. Complete the walking-skeleton flow before adding OCR.
