# S2 capability report persistence — #16 / #17

This follow-up adds an explicit PostgreSQL implementation of #56's existing
CapabilityStore seam. Based on Draft #56 f2906b3. Main was independently checked
as 64ddd4e; no existing PR head, main checkout, Shared DTO or public route changes.
The older parent needs the agreed rebase/integration before Ready. Scope, named
non-author Server/persistence backup and S1/C3/E perspectives remain to be recorded
by the lead; #17's accountable owner stays BRMRs. No prior backup is extended.

## Behavior

A database-row guard serializes independent processes/connections, including the
first report. Current state, immutable per-sequence observations and the complete
accepted/rejected operation binding share one READ COMMITTED transaction. The
local prototype uses a coarse deployment/schema-specific guard, not a production
throughput or full-snapshot consistency guarantee. Lock contention maps to the
existing CapabilityStoreBusy; the existing service returns InProgress. Other SQL/
commit failures propagate and roll back; HTTP safe-503/error mapping is deferred.

The service and its 21 tests are unchanged. Actor, installation, canonicalization
version, original typed report, reason strings/order/duplicates and operation UUID
remain bound. Rejections retain invalid original fields without creating a valid
observation or accepted receipt. An accepted original receipt replays independently
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
38 new real-PostgreSQL executions passed, including a distinct current UPDATE
fault and delayed-ack receipt fault, actual database restart and separate JVMs.
An independent latest-main 64ddd4e overlay using byte-identical #56/new sources
also passed Server61 and Shared18 reports plus bootJar; Server executed, Shared
came from cache. This is compatibility evidence, not a rebase/merge of #56 or a
new code contribution for its unchanged 21 tests. Final full-check counts,
exact source/manifest hashes and CI are recorded in the Draft PR.

## Remaining delivery

Formal migration/rollout/connection-pool/TLS, production driver/DataSource and
trusted identity/current relationship integration, HTTP/error mapping, full
snapshot, Android Room/outbox/sequence generation, device/cloud acceptance and
unresolved #24 protocols remain separate. No UI strings or real personal/medical
data are added. Keep Draft; no approval, merge, whole-Issue or Gate closure.
