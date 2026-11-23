# PS1-A: bound phone identity persistence

Refs #118/#96. S2/XinyanXue, 2026-11-23. This is the first independently reviewable
implementation slice after [same-head joint design approval](https://github.com/BRMRs/group33/pull/96#pullrequestreview-6012341899)
at `caecd8f8210cae6fc54adf847a65a4078c2bbaf2` and
[S1 design approval](https://github.com/BRMRs/group33/pull/96#pullrequestreview-6003380363).
It implements the account/installation/session persistence part of PS1, not all PS1
or PS2, provider authentication or a phone-enabled team service.

## Delivered code and caller contract

`BoundPhoneIdentityStore` is an explicitly constructed internal PostgreSQL adapter;
it has no Spring registration or routes. `BoundPhoneIdentityTransaction(Connection)`
can join the same explicit primary transaction as a future validated ticket and
business operation. Its methods preserve account -> account-installation -> session
lock order. No method calls a supplier, signs a JWT, executes DDL or falls back to memory.

- `registerVerifiedPhone`: accepts an already verified phone HMAC after the caller
  has validated its bound ticket. Concurrent registration retains one existing
  account, fixed role/name and stable user ID. HMAC key/phone verification/ticket
  consumption are not implemented by this method; it is never an HTTP credential.
- `accountForVerifiedPhone`: reads the current existing account under lock; a revoked
  account is not recreated. No raw phone is accepted or stored.
- `openSession`: creates a composite account/installation binding and independent
  random session/jti. Multiple accounts may share one installation, and an account
  may have multiple installations/sessions. New login does not silently revoke another
  session or reactivate a revoked installation. Signing belongs after commit.
- `lockCurrentSession`: compares the persisted current account/role, composite binding,
  session, profile, version and exact issued/expiry values on the caller's connection.
  Caller must first validate signature/algorithm/type/issuer/audience/claim types and
  time before entering the database; this method is not a JWT authentication filter.
  Its configurable allowance is at most60 seconds and must match that validator.
- Internal revoke methods use the same account-first lock order. They introduce no
  logout/revocation endpoint or automatic global logout.

The DataSource wrapper uses bounded lock/statement/query timeouts, applies deferred
constraints before commit, rolls back failures and exposes a sanitized unavailable
exception without SQL/credential causes. The future HTTP adapter must map temporary
failure to503, rather than treating it as invalid authentication. Pending business
evidence and original command identity remain outside this store.

## Schema and adoption

`server/src/test/resources/db/prototypes/bound-phone-identity.sql` is an isolated
integration fixture, not a migration or conversion script. No production migration
number is reserved: main has V10 and #102 proposes V11. Runtime does not create or
repair missing tables. An explicit reviewed forward migration/conversion must
preserve existing account IDs/HMACs/roles and reject incompatible or partial schema
before this adapter can be registered. It must use the same managed primary as P0.

The schema prototype adds account revocation, composite installations and bound
sessions; it stores no raw phone, JWT, capability or provider proof. It does not
declare old trial tables compatible or silently copy anonymous accounts/history.

## Validation

`PostgresBoundPhoneIdentityTest` has15 real PostgreSQL cases: stable fixed account,
two accounts on one installation, non-displacing re-login, eight concurrent connections,
foreign claim fields, exact clock boundaries, session/installation/account revocation,
deferred rollback, absent schema/no DDL, one-connection rollback, bounded lock failure,
actual SQL budget/connection cleanup and redacted diagnostics. These tests use unique
isolated schemas, fictional inputs and explicit loopback-only fixture configuration.

```sh
./gradlew -PserverOnly :server:postgresIntegrationTest \
  --tests '*PostgresBoundPhoneIdentityTest' --no-daemon
```

The dedicated CI job supplies its own short-lived PostgreSQL17 service. Local test
settings refer only to the existing private S2 practice database. Test execution
counts and task modes belong to the exact implementation head linked by the PR;
the SQL fixture and tests do not prove production migration or supplier acceptance.

## Next slices and C1 handoff

PS1-B still needs original attempt/refresh/lease/proof fingerprint, single-use bound
ticket, strict accepted request/response DTOs and typed JWT issuance/pre-DB validation.
The existing #97 provider budget and common S1 private session are preserved dependencies.
PS2 still needs the common current-principal directory and actual same-transaction
authorization/effect/receipt integration across P0. A synthetic same-connection test
does not claim those production stores are integrated.

C1's current #88 trial paths are unchanged by this PR. There is no new consumable
phone HTTP service or team APK in this slice. C1 can review the persistence boundary;
request adaptation starts with the separately delivered same-version wire/profile.
No unverified calendar date is promised for real integration: service access, reviewed
migrations, PS1-B/PS2, policy, registered certificate and S1 package must be evidenced
before that service/package is announced. Provider cleanup bounds remain unsourced;
this slice implements no fingerprint cleanup or supplier replay claim.

Non-author Server/security/persistence review, affected S1/C1 review and lead integration
remain required. No main merge, live supplier call, cloud write or device acceptance
is performed or claimed by this partial delivery.
