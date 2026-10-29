# S2 occurrence registration HTTP candidate

Refs #14/#16. This bounded candidate adapts the existing registration DTO and #78 service/transaction. It does not introduce group/action/Help/caregiver-report fields or generate events on the Server.

## Behavior

- Explicit `OCCURRENCE_ENABLED=true`, default disabled. Requires enabled plan/private-photo modules; PostgreSQL requires explicitly initialized prototype v1-v9 tables/columns. Startup never applies schema or silently falls back.
- `POST /api/v1/elders/{elderId}/occurrence-registrations` with the original `Idempotency-Key`, returning existing `OccurrenceRegistrationResponse`. Current verified Elder/self only; a paired caregiver cannot register on the Elder's behalf. Unauthorized callers receive no foreign plan details.
- Exact root/evidence/occurrence fields, integral bounded numbers, strict enums and single UUID header. Duplicate keys, trailing JSON, scalar coercions and query parameters are malformed requests; they cannot bind a service operation.
- Existing complete-batch validation, retained revision/text/frozen time, canonical uniqueness and original accepted/rejected binding remain in the service. Error mapping is 400 malformed, 401 authentication, 403 role, 404 inaccessible, 409 reused key or busy, 422 scoped validation, 503 unavailable. Busy includes Retry-After 1; private database diagnostics never enter errors; responses are no-store.

## Verification

Actual local checks and exact result counts are collected after execution in the candidate evidence folder. Test sources use real Spring filters, signed login, explicit consent, photo file IO and confirmed plans. Dedicated PostgreSQL startup checks and packaged HTTP/database failure/restart checks are separate from ordinary JUnit and CI.

The development assembly retains #78 unchanged service/JDBC/SQL plus main `999e182` Android/Room/photo changes and the unchanged #81 private signing fix. These inherited changes are not new HTTP contribution. No main/old PR branch is rewritten by this local candidate. Actual prerequisites, final head, formal review and subsequent main integration remain separate.

## Remaining boundaries

This is the existing r1 registration interface, not complete ELDER_REMINDER_V2. It does not implement dose actions, group atomicity, Help/takeover, caregiver follow-up/audit, history/snapshot, probes/notification delivery, phone-provider authentication, production migrations or Android/device acceptance. A receipt confirms this service transaction, not a real Room-generated fact or verified client clock. V2-T01–T05 affected declarations stay under #24 joint review.
