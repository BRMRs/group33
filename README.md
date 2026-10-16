# Group 33 Medication Reminder

Kotlin monorepo for the CSI301 group project. The repository contains an Android client, a Spring Boot service written in Kotlin, and shared API models.

This repository is an engineering workspace. Course reports, app design rationale, and demo scripts must be written by the team in accordance with the module's AI policy.

## Project status

The current code is a foundation, not the finished product. It provides:

- Kotlin Android application with XML layouts and ViewBinding
- Elder and caregiver role entry points
- ViewModel and Repository boundaries
- In-memory schedule data for parallel UI development
- Shared serializable models used by Android and server code
- Spring Boot health, anonymous login, JWT security, and schedule API skeleton
- Unit tests and continuous integration configuration

The Android role buttons open a local development preview, not authenticated sessions. Preview data uses explicit demo identities and `Asia/Shanghai` dates; Elder counts include only enabled plans for that demo Elder within their inclusive date range. The preview uses a light theme and canonical bilingual role labels. Private in-memory data still disappears when the process ends.

The scaffold now validates legacy dates/times and emits structured `ApiError` JSON from both controllers and JWT security failures. Legacy Caregiver access checks a `CareRelationshipAccess` seam; its shipped adapter denies every relationship because real Pairing is not implemented. Consequently unpaired Caregiver writes return generic `404 RESOURCE_NOT_ACCESSIBLE` and their list is empty. This prevents the legacy endpoint from granting cross-user access using request IDs alone. Elder self-reads remain available; Pairing/consent, persistent relationship lookup and final P0 routes are still future work.

OCR, Android-side authentication storage, Room persistence, remote sync, medication alarms, and photo upload are intentionally not implemented yet.

The current Android screens are functional placeholders. The approved P0 visual direction and Elder accessibility profile are recorded in [UI design decision](docs/UI_DESIGN_DECISION.md); the sole detailed baseline is [ELDER_REMINDER_V2](docs/ELDER_UI_BASELINE.md), accepted by [ADR-0008](docs/adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). It requires separate thirty-minute nonresponse notification and two-hour Elder cutoff, caregiver supplement entries and T−5/T+5 two-failure connection alerts; no all-day heartbeat monitoring. [Technical migration questions](docs/ELDER_UI_TECHNICAL_REVIEW.md) remain; existing r1 code/fixtures/tests do not yet implement V2. Reusable tokens and final screens are still implementation work.

The approved P0 deployment uses Room on Android, Spring Boot on Render, Supabase PostgreSQL for business data, and Supabase Storage for Medication photos, with explicit Room ↔ Server synchronization and a local demonstration fallback. This records the target stack; cloud integration and the fallback configuration are still to be implemented. See [ADR-0004](docs/adr/0004-hosting-storage-and-explicit-sync.md).

## Repository structure

```text
android-app/   Android application and UI
server/        Spring Boot API service written in Kotlin
shared/        Models shared by Android and server
docs/          Architecture, scope, API, and team briefing
.github/       Pull request, issue, and CI configuration
```

## Prerequisites

- JDK 17
- Android Studio with Android SDK 35
- An Android device or emulator running API 23 or later

Android Gradle Plugin 8.7 is deliberately used because it supports API 35 and JDK 17.

## Build

From the repository root:

```bash
./gradlew test
./gradlew :android-app:assembleDebug
./gradlew :server:bootRun
```

The server listens on `0.0.0.0:8080`. Verify it with:

```bash
curl http://localhost:8080/health
```

The checked-in JWT secret is only a local-development fallback. Before any shared deployment, set a secret of at least 32 bytes:

```bash
export JWT_SECRET='replace-with-a-random-secret-at-least-32-bytes'
./gradlew :server:bootRun
```

Android Studio normally creates `local.properties` automatically. If it does not, create it locally with your Android SDK path. Never commit that file.

## Start here

New to development? Start with the Chinese [agent onboarding guide](docs/AGENT_START_HERE.md): copy the read-only startup prompt, supply your role, and ask for one bounded first task. For Gate 1, use the [six-role review handoff](docs/contracts/ROLE_REVIEW_HANDOFF.md) for files, code/tests, evidence and same-revision signature forms. These are navigation/checklists, not automatic implementation, approval or merge permission.

1. Read [Scope and roadmap](docs/SCOPE_AND_ROADMAP.md).
2. Read [Architecture](docs/ARCHITECTURE.md).
3. Use [Team briefing](docs/TEAM_BRIEFING.md) for the kickoff meeting.
4. Follow [Contributing](CONTRIBUTING.md) for every code change.
5. Use the [Engineering playbook](docs/ENGINEERING_PLAYBOOK.md) as the complete operating agreement.
6. Confirm the actual member mapping in [Team roster](docs/TEAM_ROSTER.md).
7. Apply the [required GitHub settings](docs/GITHUB_SETTINGS.md) after the first push.
8. Treat [API contract](docs/API_CONTRACT.md) as the agreement between Android and server members.
9. Apply the confirmed shared direction and Elder accessibility profile from [UI design decision](docs/UI_DESIGN_DECISION.md) before styling screens.
10. Update [Contribution log](CONTRIBUTIONS.md) every week.
