# Pairing lifecycle HTTP — local integration candidate

Refs #10 / #16. Main `64ddd4e26440d60a60f209dd7eb12d102e2a55dd`, Issue #10 latest comments and open PR scope were checked on 2026-10-27. This candidate combines the earlier issuance HTTP and PostgreSQL decision candidates. Twenty-seven existing source/test/build dependencies are recorded separately; twenty-five remain unchanged. Only the issuance HTTP configuration and its exception boundary change among those dependencies. This is local implementation/evidence, not a published PR or production deployment.

## Routes and identity

The existing anonymous-installation login and real signed JWT decoder are used without an authentication bypass. VerifiedPairingActorResolver resolves the current stored user and verifies claim type/role before calling application services; request bodies, queries and operation keys cannot select the actor or installation. The unresolved phone/account-authentication discussion is not implemented or frozen by this slice. Anonymous identities are still process-local: database pairing records alone do not establish account recovery after a server restart or reinstall.

New adapters implement only already documented routes:

- POST /api/v1/pairing-requests: strict `{code: string}`, 201 original PairingRequestView.
- GET /api/v1/pairing-requests/{id}: participant-only current detail, 200.
- POST /api/v1/pairing-requests/{id}/accept: empty JSON, 200 CareRelationshipView.
- POST /api/v1/pairing-requests/{id}/reject: empty JSON, 200 terminal PairingRequestView.
- DELETE /api/v1/pairing-requests/{id}: absent body or empty JSON, empty 204 including exact replay.
- POST /api/v1/care-relationships/{id}/revoke: empty JSON, 200 CareRelationshipView.

Existing POST /pairing-codes is reused. No new relationship-detail route or Shared field is invented. Participant lists and deleting an unused code remain unimplemented. Default local configuration shares one InMemoryPairingCodeStore across issuance, consumption and decisions. PostgreSQL selection in the integrated HTTP tests is test-only configuration; there is no production DataSource/pool/credential loader or automatic schema application in this candidate.

## Transport and failures

Mutations require one complete UUID Idempotency-Key; abbreviated, comma-combined or duplicate headers fail. Upper/lowercase UUID spellings normalize to the same UUID. JSON duplicate keys, extra fields, null/numeric codes, unknown identity fields, trailing content and query arguments are rejected. String leading zeros are retained. JSON shape errors are 400; well-formed invalid/used/expired code is 422. Known wrong-role actions are 403; unrelated and absent resources share generic 404. Closed request, reused key and already-paired Elder map to existing 409 codes. Quota exhaustion is 429 RETRY_LATER with rounded Retry-After; lock contention is retryable 409 OPERATION_IN_PROGRESS with Retry-After: 1. Responses use existing ApiError, opaque matching X-Request-ID and no-store. Missing/bad JWT retains Bearer challenge and 401.

The direct-peer candidate explicitly sets server.forward-headers-strategy to none. Numeric IPv4/IPv6 socket addresses are converted to typed InetAddress; hostname-looking addresses fail safely without DNS. Client-supplied Forwarded/X-Forwarded-For does not select the rate bucket. Equivalent IPv6 spellings share the bucket. Behind a proxy this limits the proxy peer collectively; a reviewed trusted-proxy/CIDR configuration and tests are required before a deployment relies on per-client IP limits. No DNS, external auth service or user-provided forwarding trust is introduced.

An actual deferred JDBC COMMIT failure exposed a transport defect: SQLException is an Exception but not RuntimeException, so the earlier catch allowed global 500 instead of the intended scoped 503. Both lifecycle and issuance invocation boundaries now catch Exception, preserve the interrupt flag for InterruptedException, and return private-detail-free retryable 503. Errors are not caught. SQL failure tests keep their original 503 assertions; no test was relaxed. A failed commit is verified to roll back, and retry uses the original operation. A separate lost-response test commits first, returns 503, then proves replay returns the accepted effect rather than creating another one.

## Evidence

22 new standard HTTP executions (21 controlled Boot/filter/JWT cases plus one default-configuration wiring case) and 7 real PostgreSQL HTTP cases execute. The old 17 issuance HTTP tests remain byte-identical. JWT coverage includes absent, tampered, expired, wrong issuer, unknown subject, mismatched role and wrong claim type; there are no mock-authentication shortcuts. Other cases exercise strict decoding, role/participant isolation, original deadlines, current state versus old receipts, conflict/retry, both quotas, spoofed forwarding, concurrency, contention, safe failure and default deny-all preservation.

The PostgreSQL HTTP suite uses real login/JWT/filter/controller/service/JDBC in one path. It verifies persisted lifecycle, rejection/cancellation, foreign caller isolation and deferred COMMIT failure/retry for issuance, consumption, acceptance and revocation. Together with the earlier 69 database cases, the database task has 76 actual executions. Original service/SQL/driver/adapter tests are retained.

Standard reports total 261 (Server 163, Shared 18, Android 40+40), all without failures/errors/skips. The final Server suite executes; unchanged Shared/Android results are up-to-date in the final run (initial Shared cache, Android actual execution are retained in the initial log). lint/build, repository policy, source manifest and layered patch checks are recorded separately. This is not GitHub CI or device validation.

A separate packaged-bootJar loopback TCP smoke runs 21 checks with real HTTP on a temporary local port. It creates fictional sessions, exercises consent/revocation/replay/cancellation, rejects wrong actors and malformed numeric codes, then stops its own process. JWT secrets are temporary in process environment; no token, pairing code or response body is written to its result file. TCP smoke uses the default in-memory Store; the real PostgreSQL HTTP evidence is MockMvc through actual filters and JDBC, not a packaged PostgreSQL deployment. Both evidence types are identified explicitly.

The first standard run also exposed a test-client issue: an absent-body test omitted Content-Type, correctly receiving 415 first. The client now sends application/json for that POST so its original 400 missing-body assertion tests the intended condition. Initial failed logs and correction notes are preserved. No business rule was weakened.

## Boundaries and handoff

CareRelationshipAccess remains the existing UnpairedCareRelationshipAccess bean. The default-wiring test confirms an accepted local relationship does not enable unready medication/plan endpoints. Production protected reads/writes still need current relationship authorization inside their business transaction, serialized with revocation. Snapshot/list integration, photo/plan business transactions, stable identity persistence, phone auth, production PostgreSQL configuration/migration, deployment and two-device UI acceptance remain open.

Formal PR dependency order, scope and non-author Server/security/persistence and Android review must be recorded. The original #47 issuance-only backup does not automatically cover these new boundaries. The patch exceeds the usual 500-line guideline because one HTTP/JWT/Store boundary needs strict-input, failure, replay and real-database tests; splitting tests away from the adapter would hide the observed failure contract. No current PR was altered, approved or merged; #10/#16 stay open.
