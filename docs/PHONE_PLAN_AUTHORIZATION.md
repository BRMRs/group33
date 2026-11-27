# Bound phone authorization for plan, photo, pairing, occurrence and dose surfaces

Refs #118 and #53. This document covers the complete locally verifiable PS2 business authorization surfaces in bound-phone mode:
1. Edits and deactivation of existing reminder plans (#145).
2. Initial plan confirmation and authorized medication/plan collection reads.
3. Medication photo upload, metadata listing and binary content reads.
4. Pairing code issuance/deletion, pairing request consumption/detail, accept/reject/cancel/revoke decisions and participant directory reads.
5. Occurrence generation registration and dose action group recording/event queries.

All surfaces use the database adapter from #143, the typed phone profile from #144, and the V13 `server_principal` registry from #156.

The internal entry points validate the token before acquiring a database connection. For all state-changing operations (plan confirmation/mutation, photo `STAGING`/`READY` publication and content read, pairing code issuance/deletion, request consumption/detail, relationship decisions, occurrence registration, and dose action groups), the PostgreSQL adapter locks the current account, installation and session (`BoundPhoneIdentityTransaction.lockCurrentSession`), then acquires the existing `pairing_issuance_guard` in that order. For read-only collection queries (`medications`, `reminder-plans`, `medication-photos`, `care-relationships`, and `dose-events`), the PostgreSQL adapter opens a single read-only `REPEATABLE READ` snapshot transaction (`BoundPhoneIdentityTransaction.readCurrentSession`), verifying the current non-revoked phone account, installation, session, non-ambiguous `PrimaryPrincipalDirectory` identity, and either self-Elder ownership or active Caregiver relationship without acquiring `pairing_issuance_guard` or writing receipts. Both paths refuse ambiguous IDs present in both identity stores. Existing anonymous routes continue to use their own actor path when `app.bound-phone.enabled=false`.

A new session on the original installation can recover the original operation receipt while authorization is current. A different installation cannot take over the operation. Revoking the session or the care relationship blocks replay and reads as well as new effects.

The PostgreSQL tests verify single-connection writes, read-only collection snapshots, replay across re-login, cross-installation rejection, session and relationship revocation, Elder vs Caregiver role enforcement, non-blocking read snapshots when `pairing_issuance_guard` is locked, ambiguous identity rejection, signature failure before any database connection, and a full end-to-end phone HTTP journey (login → pairing code → request → accept → photo upload/read → plan confirm/edit → occurrence registration → dose action group → dose events read → relationship revoke) with zero `anonymous_identity` rows.

Managed cloud DB/Storage/RLS adoption, real provider, signed APK and joint physical-device cases remain separate.
