# Bound phone authentication HTTP flow

Refs #118. The reviewed #96 design defines the original attempt and typed session. This implementation builds on #143. The local HTTP fixture constructs the controller with a simulated provider and the same PostgreSQL adapter used by the authentication service.

- `POST /api/v1/auth/fusion/token` starts with `installationId`. To refresh, send the same `installationId` and `authenticationAttemptToken`. A successful response includes `fusionAuthToken`, `schemeCode`, remaining `expiresInSeconds`, and the original authentication capability. Refresh does not extend the original deadline.
- `POST /api/v1/auth/fusion/verify` takes `installationId`, `authenticationAttemptToken`, `verifyToken`, and optional `expectedPhone`. A successful proof against an existing account returns `SIGNED_IN`, `accessToken`, `expiresInSeconds`, `user`, and `installationId`. A first account returns `REGISTRATION_REQUIRED`, a bound `registrationTicket`, `registrationTicketExpiresInSeconds`, and `installationId`.
- `POST /api/v1/auth/phone/register` takes the `installationId`, `registrationTicket`, fixed `role`, and a caregiver `displayName`. Ticket consumption, account creation or lookup, and session persistence commit together. Only the committed session is signed. C1 compares the installation echo to the original S1 attempt before completing the local session.

A durable lease serializes refresh and verify. Supplier calls run outside the database transaction. A lease token and deadline fence completion. The store retains a unique proof fingerprint per authentication profile, including after an uncertain result. An unknown or expired lease cannot restart supplier work. Provider calls must reuse the existing bounded #97 adapter when composing a deployment. The 20-second database lease is separate from the 15-second supplier limit and the fixed attempt deadline. The registration ticket has an independent 300-second deadline.

The phone JWT profile verifies HS256, explicit type, issuer, audience, version, exact claim types, canonical UUID identities and the configured clock window before database access. The local receipt of a signed token still requires current account, installation and session state for protected business effects.

The HTTP controller requires explicit opt-in and an injected service and trusted IP limiter. Production composition depends on a verified supplier adapter, explicit forward migrations on the same primary database, reviewed configuration and an agreed cutover from #88. The SQL under test resources creates isolated fixture tables. Provider proof retention limits are still unconfirmed, so the fixture and implementation retain fingerprints. Live SMS, team APK, policy URL, and deployment evidence follow separately.


## S1 review follow-up — 2026-11-25

The original scheme is now persisted with attempts and registration tickets; changing the configured scheme cannot retarget an existing capability or consume its ticket. Supplier entry rechecks the original lease/deadline and complete15-second budget after the validation transaction returns. The test prototype gains scheme_code columns only; reviewed forward migrations and deployment composition remain prerequisites. Exact before/after findings, real-PG regression scopes and limits are recorded in [S1 review corrections](evidence/S1_BOUND_PHONE_REVIEW_20261125.md).
