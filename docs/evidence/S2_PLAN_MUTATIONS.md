# S2 prospective plan editing and deactivation — #14 / #16

Prepared against Draft #74 (`a6ede0ee5b35ec498a95f2369e6f45748fef9099`).
Existing Shared DTOs and API/product rules are unchanged. Draft visibility is not
Ready, human approval, migration/cloud readiness, or completion of #14/Gate 1.

## Behavior and transaction

PATCH `/api/v1/reminder-plans/{planId}` returns the atomic Medication/plan pair.
POST `/api/v1/reminder-plans/{planId}/deactivate` returns the inactive new plan
revision. Both require signed current stored identity, a current active Caregiver
relationship, exactly one complete UUID Idempotency-Key and strict existing JSON.
A known owning Elder gets 403; unrelated/missing resources get generic 404.
Boolean/string/fractional/overflow/missing/null revision encodings are rejected;
well-formed invalid business input gets 422 with scoped fields.

Current authorization precedes operation lookup. Operation identity is scoped by
actor and action, bound to installation, canonical target and full typed payload
(P0_CANONICAL_V1; sorted times but duplicates validated, exact text retained).
Accepted original receipts replay before expectedRevision checks. A fresh stale
request gets 409 PLAN_VERSION_CONFLICT; changed bound content gets 409
IDEMPOTENCY_KEY_REUSED. Rejected evidence stays separate from accepted results;
explicit identical retry may re-evaluate a repaired precondition. Bounded guard
contention is retryable 409 OPERATION_IN_PROGRESS with Retry-After: 1. SQL/commit
failure returns safe retryable 503 and leaves no partial success.

The guarded transaction updates current text/plan, inserts a new immutable full
revision and stores the original typed receipt. Revision is checked/incremented
without overflow. effectiveFrom/confirmedAt are the service acceptance clock time.
Omitted Medication text retains the current text; specified text preserves exact
whitespace/Unicode and optional instructions. Photo, Elder and Medication identity
are not editable. Deactivation never deletes Medication/history. Edits preserve
current active=false; this DTO does not request reactivation. A new deactivation
intent creates a prospective inactive revision; identical retry replays its first
result. Existing event records are never generated or rewritten by this service.
This retained-revision behavior alone is not complete offline-event integration.

## Full-range overlap

Only active plans for the same Elder and medicationId compete; an edit excludes
its own old revision. Distinct medications, inactive plans, nonintersecting
instants and one-minute spacing are allowed. Same names do not establish identity.
Existing self-overlap validation and the new cross-plan validator use P0_TIME_V1:
shift gaps by their whole duration and choose the earlier instant once in folds.

The cross-plan algorithm partitions nominal local time at each TZDB transition's
max(before,after) boundary. In each constant-selected-offset segment, daily times
become residues modulo 86,400 seconds inside bounded instant intervals. An
interval sweep checks matching residues; it retains overlapping gap intervals
rather than assuming resolved mapping is globally monotone. Inclusive end dates
become the next local midnight; open end is 9999-12-31. It visits all historical
and recurring transitions across the entire four-digit ISO range (including year
0000), not seven days, sampled years or a finite downloaded window. Historical
second offsets and international date-line carry are retained. Cost grows with
TZDB transitions and configured times; global guard throughput is still a local
prototype limitation requiring implementation review, not a production SLA.

Tests compare both directions against an independent daily Java resolver oracle
for 144 finite schedule pairs (288 comparisons), plus exact gap/fold, skipped
Apia date, historical seconds, disjoint dates, far-future/open-ended and supported
endpoint counterexamples. Same-Medication multi-plan service cases use clearly
marked test-only read fixtures: v7's initial confirmation intentionally creates
one plan per new Medication. This slice adds no public same-Medication creation
route and does not weaken initial-confirmation SQL constraints to manufacture it.

## PostgreSQL and rollout

Explicitly install additive `plan-mutation-store-v8.sql` after v1-v7 in a trusted
isolated schema/transaction. It adds the bound mutation journal, retained-version
FK, deferred complete-transition/Medication-edit guards and immutable-revision trigger. Existing
v7 initial receipt/reverse links remain intact. Startup with PLAN_ENABLED=true
now requires v8 columns and fails before serving when absent; it performs no DDL
and never falls back to memory. Keep the feature disabled until prerequisites
are installed. The runtime retains all earlier explicit private-photo, key and
loopback-only PostgreSQL requirements. Formal migrations, upgrades in a shared
installation, pooling/TLS, rollback procedures and cloud rollout need separate
review; never delete accepted data as a code rollback.

## Verification

New test classes: ReminderPlanMutationTest, ReminderPlanMutationHttpTest,
PlanCrossOverlapValidatorTest, PostgresPlanMutationTest. The same behavioral
contract runs against both real copy-on-write memory and real PostgreSQL stores.

Run `./gradlew :server:test --tests '*ReminderPlanMutationTest' --tests
'*ReminderPlanMutationHttpTest' --tests '*PlanCrossOverlapValidatorTest'
:server:bootJar`, then `./gradlew gate1Check test`. Tagged PostgreSQL tests require
a disposable local instance and S2_PG_INTEGRATION_URL/USER/PASSWORD,
S2_PG_RECEIPT_KEY_FILE; existing restart regressions additionally require their
private test manager settings. Execute `:server:postgresIntegrationTest` there.
Never run injected faults/restart tests against a shared/deployed database.

The packaged TCP probe also exercises actual SQL write faults and COMMIT failure,
eight same-key requests and different stale-key competition, two entire database
and server restarts with old JWTs, original edit/stop/confirmation receipts,
retained historical text, current revocation and missing-v8 startup rejection.
Exact final counts, execution/cache modes and CI are recorded in the PR after
execution; ordinary CI does not execute the dedicated real-database suite.

## Remaining boundaries

Formal review still needs lead-confirmed scope, a named non-author Server/
persistence reviewer and C2/S1 perspectives plus dependency/landing order. The
#47 backup is not automatically extended. #61's separately reviewed PNG repair
must be retained when this dependency stack is integrated/rebased; inherited
validator code is not counted as a new mutation contribution. No Ready/Approve/
merge/Issue closure is implied. This coherent service/controller/two-adapter
change is larger than 500 lines mainly because rollback, authorization, receipt,
time and real-database counterexamples must be reviewed with the same semantics.

No new UI strings, Shared fields, event registration/actions/history/snapshot,
V2 follow-up/probe protocol, cloud/Storage cleanup or Android device acceptance
is delivered here. Current S2 work does not require the Xiaomi tablet; actual
client integration and two-device acceptance remain later steps.
