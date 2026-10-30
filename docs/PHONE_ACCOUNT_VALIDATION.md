# Current phone account validation

Refs #10/#16; S2 follow-up to C1 Draft #88 (`f2c922e`) and its [S2 review](https://github.com/BRMRs/group33/pull/88#issuecomment-6517412657). This implements the existing-token/current-account seam without choosing the pending account/installation contract. C1 retains SDK, UI, provider and registration ownership. No Shared DTO, token claim, route or phone schema changes.

## Authentication behavior

A signature-valid, issuer-valid and time-valid token with existing `type=phone` must match a current persisted account by canonical userId and fixed stored role. Missing accounts, malformed subject/unsupported role, or role mismatch use the existing generic401 UNAUTHENTICATED response; no account detail is exposed. Invalid signature/issuer/time is rejected before database access. Existing non-phone authentication does not acquire a phone database dependency.

A database read failure is **not evidence of an invalid session**: the request is denied using the existing503 SERVICE_UNAVAILABLE envelope with retryable=true and no-store. It has no invalid-token WWW-Authenticate challenge and can retry the same still-valid token after recovery. A narrow Bearer failure handler is needed because Spring's default handler rethrows authentication service exceptions; other failure types retain their existing handler. No private SQL/configuration/cause is carried into the response or exception.

Current account validation is one authentication check, not a lock through the subsequent business transaction. Pairing/photo/plan authorization must still resolve current account/installation/relationship inside its appropriate transaction. This does not map a phone account to an anonymous installation or trust client-selected actor/role.

## Persistence and signing

`PhoneAccountStore.findByUserId` is a prepared, read-only, rollback/close query; it never calls prototype table initialization, writes accounts or consumes tickets. PostgreSQL uses a five-second connect bound, ten-second socket bound and five-second statement timeout. H2 remains a development test path and does not stand in for PostgreSQL. Registration/provider paths and their existing prototype first-use DDL remain unchanged; a proper reviewed migration/current-installation design is still required. No deployed schema or permission is changed.

The parent #88 still contains the old public default JWT signing sentinel. This delta mechanically retains the previously delivered [#81 private default signing fix](https://github.com/BRMRs/group33/pull/81): each application start generates a private default key; explicit private signing configuration remains stable. This is prior BRMRs work, not a new S2 implementation claim. Stable private JWT_SECRET is necessary for durable sessions across process restarts. Keep this fix when integrating the phone and P0 branches.

## Evidence and remaining work

`CurrentPhoneAccountValidatorTest` and `CurrentPhoneAccountHttpTest` cover current id/role, safe missing/failure results, invalid identity/role, unchanged anonymous behavior, signed bearer acceptance/rejection through the real decoder, signature/issuer/expiry checks before lookup, temporary503 and recovery with the same token, and prior private-signing retention. No security-test jwt() injection substitutes for actual signature validation.

`PostgresCurrentPhoneAccountTest` uses the dedicated loopback database: read by id with unchanged row/tickets, empty-schema no-DDL, database-enforced read-only operation, current role/deletion, temporary missing-table failure/recovery, actual exclusive lock timeout, and independent JVM/database restart. Six registration regression probes from the prior independent S2 review are rerun from an external evidence packet and are not added to C1's PR or claimed as new production code. Ordinary CI excludes tagged database tests; explicit `:server:phonePostgresIntegrationTest` refuses absent dedicated local settings rather than silently skipping.

Full local gate, exact packaged TCP checks and actual executed counts are recorded in [evidence](evidence/S2_CURRENT_PHONE_ACCOUNT.md) and the external S2-phone-current-account-20261030 packet after execution. Do not count external probes as tests contained in the candidate. No provider/SMS, cloud, ordinary Android UI/device or full phone→pairing/photo/plan acceptance is claimed. C1/S1/S2 still need to settle installation/session claims, API/Shared account response and explicit schema migration under #88; group/Help/follow-up/alerts remain #24. Formal human/security review and actual dependency landing remain required.
