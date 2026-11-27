# S2 capability PUT and explicit local runtime

Refs #17 / #16. #17 accountable owner remains BRMRs.

## Dependency and scope

The current candidate is based on #76 head
`2c0b39db316613ba98428d18ea1371e3c4113738`, which already contains reviewed
#56 and current main `08f901ac4e44393dc850459caf3924e463f3f5c9`. The incremental
diff contains only the owner PUT/runtime configuration, tests and evidence; no
inherited capability service, Store, SQL or public Shared contract is modified.
The lead-recorded review scope names tianiridescent as temporary non-author
Server/security/runtime backup, Cle-cre for C3 consumption and Katherine-Tang
for Elder/event meaning; assignments do not create approval.

This bounded delta registers only the existing `PUT /api/v1/elders/{elderId}/reminder-capability`. `CAPABILITY_ENABLED=false` by default. Explicit opt-in selects one shared memory adapter or the local PostgreSQL adapter alongside existing persistent identities/pairing. No photo or medication-plan dependency is needed.

Verified JWT subject/type/role must agree with the current stored anonymous identity on every call, including accepted retries. The installation comes from that identity, never request fields. Only the owning Elder can write, including while unpaired or after relationship revocation. Another Elder or unrelated Caregiver receives generic 404; a Caregiver's own known target/current active relationship receives 403 and never grants write access. The relationship check only classifies a denied request: it does not enable capability reads or change the existing deny-all medical-access adapter.

The route-local JSON tree decoder requires exactly the eight existing fields, exact enum names and actual JSON strings, booleans, signed 64-bit integers and string lists. Missing/null fields, null/non-string reasons, numeric/string/float coercions, duplicate fields, unknown identity fields and trailing JSON fail with 400. No global ObjectMapper coercion settings change. Exactly one full UUID operation header and no query parameters are accepted. Well-formed invalid business reports return 422. Accepted bindings and authorized validation/sequence rejections retain the parent service semantics. Authorization denials and mismatched-payload idempotency conflicts do not create new capability business-evidence rows; the Android journal retains the original rejected local request. An accepted retry returns its complete original historical receipt, even after a newer report exists.

Bounded capability lock contention is retryable 409 OPERATION_IN_PROGRESS with Retry-After: 1. Identity/database/commit failures become safe retryable 503, without SQL, credentials, reasons, installation, token or private causes. Responses are no-store; errors correlate body requestId with X-Request-ID. Existing global framework handling still covers unsupported methods/media. There is no new GET route, snapshot, protocol field or UI string.

## Local verification

- 32 new standard HTTP executions: real anonymous signed login, owner-only and actual pairing/revocation classification, current-identity/claim mismatch and expired token, historical/delayed replay, changed bindings, 20 strict decoding cases, rejected intent, eight parallel HTTP retries, safe busy/database errors and unchanged other decoder behavior. The default-disabled route and absent Bean are also verified.
- 16 real PostgreSQL runtime cases: durable identity/pairing/capability selection,
  every required capability table/column and guard row failing closed at startup, and
  feature-disabled startup with no capability tables or implicit DDL.
- The current #76 parent passes 41 capability PostgreSQL executions and 23 service cases. The #77 runtime does not modify the parent SQL or receipt behavior.
- Earlier packaged TCP evidence covers persisted login/pairing, owner PUT, write/commit
  rollback, concurrent retries, lock contention, delayed reports, database/server
  restart and disabled/missing-schema startup. It remains attributable to its named
  historical head and is not relabelled as a current execution.
- Only disposable loopback schemas/processes were used; temporary servers/schema removed, pre-existing schemas preserved and dedicated database stopped. No actual personal or medical data used.
- Current targeted checks pass 23 service, 31 HTTP, 1 disabled-route and 14 runtime-configuration cases plus `bootJar`; the PostgreSQL runtime suite passes 16/16. Full clean `gate1Check test` passes 109 tasks (102 executed, 7 up-to-date); exact-head CI is recorded on the PR.

## Reproduce

Use JDK17 and SDK35. Run `./gradlew gate1Check test` and the repository policy/hash checks. `:server:postgresIntegrationTest` requires explicit S2_PG_INTEGRATION_URL/USER/PASSWORD, S2_PG_RECEIPT_KEY_FILE, and S2_PG_MANAGER_SCRIPT/S2_PG_PYTHON for inherited process/restart cases. The test guards accept only the dedicated loopback practice database; do not run restarts/fault injection on shared data.

For an isolated runtime, explicitly initialize pairing prototype v1-v4, identity v5 and `capability-store-v1.sql` in one trusted schema transaction; startup never applies DDL. Keep database password, stable >=32-byte JWT signing secret and owner-only 32-binary-byte pairing receipt key outside Git. Set the existing `.env.example` persistence fields and CAPABILITY_ENABLED=true. Start the packaged server bound to loopback, perform real anonymous login and PUT the existing Shared report using its original reportId as Idempotency-Key. Restart database and server with identical private configuration; the old token/operation must replay the same receipt after newer accepted reports. Enablement without capability tables/guard must fail before health becomes available. With the flag false, the capability route stays absent.

## Remaining acceptance

This is last-known reporting, not real-time connectivity or proof of notification delivery. No capability GET/snapshot, Repeatable Read aggregate, atomic cross-Store read, phone-provider identity, migration/reinstall, cloud runtime, production pool/TLS/migrations, Android Room/outbox, device acceptance or #24 probe/group/follow-up proposals are delivered. A denied Caregiver classification can observe a relationship transition between requests, but always denies the write; no medical data is returned and no snapshot consistency is claimed.

The dependency order remains #56 → #76 → #77. Each parent must land and the actual final base must be revalidated before merge. Formal approval, merge and whole Issue/Gate closure remain separate.
