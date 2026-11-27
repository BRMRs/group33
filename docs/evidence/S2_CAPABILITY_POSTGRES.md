# S2 capability report persistence — #16 / #17

This follow-up adds an explicit PostgreSQL implementation of #56's existing
CapabilityStore seam. The current candidate is based on reviewed #56 head
`270c0ed0c3ca18255d760ce5f1f5fcdf48ea49fe`, which contains current main
`08f901ac4e44393dc850459caf3924e463f3f5c9`. It changes no Shared DTO or public
route. The lead-recorded review scope names tianiridescent as the temporary
non-author Server/security/persistence backup, Cle-cre for C3 consumption and
Katherine-Tang for Elder/event meaning; those assignments do not create approval.
#17's accountable owner stays BRMRs.

## Behavior

A database-row guard serializes independent processes/connections, including the
first report. Current state, immutable per-sequence observations and each accepted
or post-authorization validation/sequence rejection binding share one READ COMMITTED transaction. The
local prototype uses a coarse deployment/schema-specific guard, not a production
throughput or full-snapshot consistency guarantee. Lock contention maps to the
existing CapabilityStoreBusy; the existing service returns InProgress. Other SQL/
commit failures propagate and roll back; HTTP safe-503/error mapping is deferred.

The service and its 23 tests from the current #56 parent are unchanged. Actor, installation, canonicalization
version, original typed report, reason strings/order/duplicates and operation UUID
remain bound. INVALID_REPORT and CAPABILITY_SEQUENCE_CONFLICT retain the authorized
original fields without creating a valid observation or accepted receipt.
ROLE_NOT_ALLOWED, RESOURCE_NOT_ACCESSIBLE and IDEMPOTENCY_KEY_REUSED are checked
before a new binding can be written: untrusted or mismatched payloads are not
retained as capability business evidence. The Android command journal remains
responsible for preserving the original local request after an explicit
non-retryable rejection. An accepted original receipt replays independently
of today's current row, including its original receivedAt, applied flag and
historical current view. Only a higher observationSequence advances current;
late lower reports are retained/acknowledged with applied=false. Wall-clock order
does not override sequence. No TTL/automatic evidence purge is introduced.

The adapter uses only JDBC/DataSource in production code and is not registered as
a Spring bean. Its caller must supply a trusted private PostgreSQL DataSource.
There is no startup DDL, database fallback, permissive relationship fake, HTTP
endpoint, JWT resolver or service-runtime switch in this slice. The new driver
dependency (42.7.14, matching the other prepared PostgreSQL adapters) is test-only;
production packaging/runtime driver wiring remains an explicit integration step.

## Explicit schema / atomicity

Install server/src/main/resources/db/prototypes/capability-store-v1.sql explicitly
in a disposable private schema/transaction. This standalone capability version is
independent of pairing/plan prototype versions, not a new project migration order.
Reapplying it retains accepted data. No shared/demo schema is upgraded by tests.

The schema retains current, observation and operation rows. Deferred reverse FKs
prevent an observation or current-only partial Store call committing without its
accepted receipt. Deferred consistency triggers bind original report payload and
current snapshots to retained observations and their original source receipt.
Current updates must increase sequence; original observation/binding/accepted
receipt evidence cannot be rewritten or deleted. Accepted original receipt JSON
never references a mutable current row for reconstruction. Explicit SQL/commit
faults verify rollback at each write, including a failed commit after all writes.

Private source configuration and schema selection define environment separation.
No reinstallation/device/identity migration is introduced. Trusted actor/target
resolution and the CareRelationshipAccess policy remain the #56 seams. Sequential
revocation via a controlled test policy is not real pairing/database authorization
or a concurrent multi-table Repeatable Read snapshot proof. Capability means
last-known reported permissions/conditions, not real-time connectivity, reminder
arrival, #24 probe response, or notification delivery.

## Actual validation and reproduction

Use JDK17/SDK35. Standard regression: `./gradlew :server:test --tests
'*CapabilityServiceTest' :server:bootJar`, followed by `./gradlew gate1Check test`.
The 21 existing capability cases must remain byte-for-byte unchanged and pass.
The real-database suite is separate: `./gradlew :server:postgresIntegrationTest`.
It fails closed when explicit settings are absent and disables up-to-date/cache
for the database task. Ordinary CI does not claim real-database execution.

Required private test environment: S2_PG_INTEGRATION_URL, S2_PG_INTEGRATION_USER,
S2_PG_INTEGRATION_PASSWORD. Tests deliberately allow only the dedicated loopback
practice URL. Database-restart cases additionally require S2_PG_MANAGER_SCRIPT
and S2_PG_PYTHON for that dedicated instance. Never point injected faults/restarts
at shared/deployed data. No passwords or raw private process output are printed.

Tests actually execute independent JDBC adapters and retained reads, all bound
fields, rejected identities, lower/higher sequence and backward clocks, exact
original replay, copies, separate environments, same-key eight-way retries,
different-sequence competition, same-sequence conflicts, every SQL write fault
and deferred COMMIT fault, partial Store rollback, immutable history/current
constraints, bounded lock wait, nondestructive installation, missing guard failure,
actual PostgreSQL stop/start and independent issuing/replay JVM exits after a
newer report. The process probe is test-only and excluded from production jars.
The synchronized candidate passes 41 real-PostgreSQL executions, including a distinct current UPDATE
fault and delayed-ack receipt fault, actual database restart and separate JVMs.
The current-parent service suite passes 23/23 and bootJar builds. Final full-check
counts, exact source/manifest hashes and CI are recorded in the PR.

## Remaining delivery

Formal migration/rollout/connection-pool/TLS, production driver/DataSource and
trusted identity/current relationship integration, HTTP/error mapping, full
snapshot, Android Room/outbox/sequence generation, device/cloud acceptance and
unresolved #24 protocols remain separate. No UI strings or real personal/medical
data are added. No approval, merge, whole-Issue or Gate closure is implied.
