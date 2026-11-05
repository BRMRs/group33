# Phone login and registration database availability

2026-10-31. S2 / XinyanXue. Refs #49 / #10 / #16. Child of #94 at `7cc66543407cb11eaa76b394027a43bd43d095b2`; this branch does not claim that its C1/S1 or persistence prerequisites have merged.

## Trigger and behavior

The current-account JWT lookup already used bounded, read-only PostgreSQL access. The other existing phone-account paths had no explicit per-path connection/socket or SQL limits and used an unbounded local initialization monitor. A verified phone login or first registration with an unavailable database returned generic500, although the account check distinguished temporary503 from invalid401.

The existing lookup, ticket creation and registration paths now share the current lookup's PostgreSQL connect5s/socket10s and per-statement5s budgets. Local trial initialization waits at most5s for its per-store lock; its two retained trial DDL statements have the same SQL budget. A waiting initializer interrupted locally retains the thread interruption and fails closed. These are connection/statement/initialization bounds, not an end-to-end HTTP or supplier deadline.

SQL or invalid database configuration on those account paths is normalized to a sanitized storage exception without the original cause. Phone-auth-only advice returns the existing `SERVICE_UNAVAILABLE` error shape with HTTP503, retryable=true, `Cache-Control: no-store` and matching `X-Request-ID`, without `WWW-Authenticate: Bearer`, phone/proof/ticket, JDBC, credentials or SQL diagnostics. Invalid tickets/names keep the existing422 behavior. The handler does not catch every application's SQL exception, authorize another user, issue a token on database failure or invalidate an unrelated session.

Registration still locks the original ticket, preserves one fixed-role account per phone hash, deletes the ticket and creates/reads the account in its original transaction. Before-commit SQL failure rolls back that transaction. If cancellation/transport/COMMIT outcome is uncertain,503 is not proof that no account was committed: obtain fresh verified authentication to recover the stable account rather than promising that every failed response leaves a reusable ticket. No automatic verification or registration replay was added.

## Verification scope

- `PhoneAuthDatabaseAvailabilityHttpTest`: real MVC plus the real account store pointed at an unused dedicated loopback endpoint; mocked supplier only. Both unavailable lookup and registration formerly returned500 instead of503. After the fix, verify generic error, retryability, no-store, request-id and absence of token challenge/private diagnostics; no session signing or supplier call during registration.
- `PhoneAccountAvailabilityTest`: unsupported configuration is sanitized; malformed ticket/name is still rejected before any database access. Three of four new ordinary cases failed on unchanged parent production code; logs and XML are retained outside the repository. Original assertions are unchanged.
- `PostgresPhoneAccountAvailabilityTest`: actual isolated PostgreSQL access-exclusive locks exercise lookup, ticket creation and account insertion after ticket deletion. Each must fail in the tested4–15s range, release resources, preserve original state and recover after unlocking. The registration case checks that rollback restores the original ticket and that it succeeds exactly once afterward.
- Existing phone auth/current-account tests, the original seven real PostgreSQL current-account tests, original Android/S1 session tests and whole-repository gate remain required. Exact executed/cache counts and final commands are recorded in the PR/local verification packet; test source is not itself a passing acceptance claim.

Commands: `./gradlew :server:test --offline`; explicitly configured dedicated-local `./gradlew :server:phonePostgresIntegrationTest --offline`; `./gradlew gate1Check test --offline`. PostgreSQL checks require the guarded loopback database configured by #94, create randomized schemas and remove them afterward; they do not use Supabase credentials. Final evidence must record completed execution and a stopped local server.

## Preserved scope and remaining work

No request/response success fields, JWT claims, stable identity/role, ticket lifetime, Shared/Android/provider code, cloud configuration or migration is changed. C1/tianiridescent retains the original phone feature; BRMRs retains logout fencing and private-signing attribution. Current-account reads remain read-only and never initialize tables.

This keeps the original **trial-only first-use DDL**, not a production migration strategy. Explicit forward migrations and removal of runtime DDL remain required before production adoption. #96 D1–D4 account/installation/session/P0 binding is still a separate pending proposal; no installation-bound phone-to-pairing/photo/plan access or complete phone sign-in is claimed. No provider/SMS, device, cloud, anonymous-history conversion, Ready transition, formal approval or merge. C1/S1 and Server/security review the final availability behavior and existing dependency order.

Rollback reverts the code/advice only; it deletes no account, ticket or business evidence. Reverting restores the old availability defect and is not recommended as a production remedy.

## Completed local verification — 2026-10-31

The unchanged-parent control had4 new ordinary cases,3 failures (two expected503/observed500 HTTP cases and one unsanitized configuration exception); all4 pass after the fix without changing assertions. Original full gate/test passed with208 report instances: Server68,Shared18,Android Debug61/Release61,zero failures/errors/skips. Server and both Android variants executed; Shared restored from cache.104 tasks:45 executed,52 cache,7 up-to-date. Real dedicated PostgreSQL task passed10 cases (3 new availability/rollback cases plus7 inherited current-account cases), with zero failures/errors/skips; randomized schemas cleaned and the local instance stopped. No supplier, cloud or device was used. Repository policy and184 artifact hashes verified; precise final-head CI is checked separately after Draft publication.
