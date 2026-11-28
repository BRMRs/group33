# Bounded Alibaba supplier for bound phone authentication

Refs #118 A. This programmatic adapter implements the current `BoundPhoneProvider` seam on the corrected #147 input. It reuses #97's bounded transport without registering Spring beans, selecting application routes or creating another account/session implementation. Production composition remains a separate delivery; this adapter alone does not enable phone login.

## Configuration and call boundary

`AlibabaBoundPhoneProvider` takes explicit access-key ID/secret, scheme code, Android package name/signature and the same `Clock` used by `BoundPhoneAuthentication`. Blank inputs fail at construction with a fixed private-configuration error. Its string representation and supplier exceptions do not contain values. There are no new environment variables, property names, credential discovery or application/anonymous-profile fallbacks in this slice. The deployment composition must provide reviewed private configuration and pass the same scheme to the adapter and authentication service.

The catalog pins `com.aliyun:dypnsapi20170525:2.0.0`, as in #97. Only Server uses the SDK. The default client uses the fixed `dypnsapi.aliyuncs.com` endpoint with HTTPS and normal certificate verification. The injectable client factory is an internal test seam, not an environment-configurable endpoint.

```text
BoundPhoneAuthentication original attempt / profile / scheme / lease
  → AlibabaBoundPhoneProvider
    → #97 FusionCallExecutor: 4 daemon workers, no queue, 15s monotonic caller budget
      → SDK: connect5s / read10s / autoretry=false / maxAttempts1
  → original attempt completion fencing
  → same-primary identity/ticket/session commit
```

Supplier calls stay outside the authentication transaction. Cancellation is best effort and cannot revoke remote work. A late supplier result is discarded by the executor; original attempt/proof fencing prevents replay or late identity/ticket/session creation. Socket and caller limits are component limits, not a promise that all HTTP/SQL/cleanup work completes within15seconds. Diagnostics contain only fixed stage/outcome enums and monotonic elapsed milliseconds.

## Grant and verification semantics

The grant request specifies900seconds. `BoundPhoneGrant.expiresAtMillis` is computed before entering the bounded call, so transport latency reduces remaining lifetime. The existing authentication service further caps every response by the original attempt deadline and preserves scheme/capability on refresh. The grant lifetime says nothing about supplier proof validity or fingerprint-retention limits.

Verification returns a phone only for an API-success `OK` response with `PASS` and a valid-shaped phone. The documented `UNKNOWN` authentication result returns explicit denial. API errors, absent/malformed success data and unrecognized future results throw the sanitized supplier-unavailable exception; the bound controller returns its existing retryable503 without upstream details. Invalid blank/oversized proof is rejected locally. No automatic SDK or application replay is added.

The supplier's official references, checked2026-11-25, are [grant request and duration](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-getfusionauthtoken), [verification result and API status](https://help.aliyun.com/zh/pnvs/developer-reference/api-dypnsapi-2017-05-25-verifywithfusionauthtoken), [Java timeout configuration](https://help.aliyun.com/en/sdk/developer-reference/timeout-configuration) and [Java retry configuration](https://help.aliyun.com/en/sdk/developer-reference/configuration-for-retries). These support API/SDK behavior, not an actual successful provider call or proof-reuse policy.

## Attribution and verification

Original source input: #97 `d320841986015f109f0ecd2260026ea1be598b23`. `FusionCallExecutor` retains its implementation with only the trial/attempt comment updated; its six tests are copied unchanged. The standalone SDK socket/streaming tests are retained from `FusionAuthTransportTest`, adapted to the new provider type; the old controller/account-specific test is replaced with current-bound-controller and real-PostgreSQL tests. Supplier request fields, explicit socket/no-retry controls and private failure diagnostics are reused. No legacy phone-controller/account/JWT/first-use DDL source is imported.

New coverage checks pre-call grant expiry, explicit denial versus API/malformed-data outages, pinned-SDK HTTP socket timeout, continuously readable slow responses, late completion, saturation and interruption. The real-PostgreSQL profile combines all existing identity/original-scheme/supplier-entry controls with pinned-SDK seam→current service/controller tests. It checks no transaction remains open at supplier entry, original identity after registration/relogin, retained proof/closed attempt after unavailable outcomes, unchanged supplier call count on retry and no identity/ticket/session writes after late PASS. Every supplier response is fictional; loopback HTTP is used only for SDK transport faults.

Commands and executed results are recorded in the PR. The inherited dedicated PostgreSQL CI selects `*BoundPhone*PostgresTest` and includes the new integration class. This slice adds its scoped XML/HTML artifact with7-day retention so the exact hosted execution count can be inspected; the job uses only its fictional PostgreSQL/SDK fixtures. No physical phone, actual provider/SMS, private credential, Render/Supabase deployment or production schema is used for these tests.

## Remaining #118 handoff

S2 still supplies the production Boot/configuration composition: explicit old/new route cutover, same primary DataSource and transaction/clock/scheme inputs, typed JWT and independent signing/HMAC secrets, trusted-proxy IP rate limiting, complete configuration mapping, reviewed forward migrations/history and same-version deployment evidence. C1 supplies actual SDK/proof scope/reuse/retention facts and eventual real one-click/SMS acceptance; S1 consumes the accepted service with its private installation/session and verifies the team APK. The test prototype SQL remains test-only, and the other PS2 business surfaces remain separately incomplete.

Normal non-author C1 and Server/security review of the final adapter and its corrected #147/#144 prerequisites remains required. Reverting this source slice removes the supplier implementation/SDK dependency; no database or service rollback has been performed or is implied.


## Local verification — 2026-11-25

Fresh owned PostgreSQL16 fixture:36 tests passed,0 failures/errors/skips (identity15, flow9, original supplier-entry7, new provider integration5). This uses a non-superuser test DB owner and only new random schemas; its cluster, roles, receipt key and generated credentials were stopped/deleted. Initial four new row-count assertions used two incorrect fixture table names; those test-authoring failures were corrected against the unchanged original SQL and are not presented as product counterexamples.

Full `gate1Check test :server:schemaMigrationDist --offline` passed:106 tasks,88 executed/7 cached/11 up-to-date. Server647 and Android Debug135/Release134 JVM cases executed with0 failures/errors/skips; Shared18 reused cache. Lint, Debug APK, bootJar and migration package passed; no Android device or new Release APK run is claimed. Both technical review axes found0 issues; this is not human approval. Exact-head hosted PostgreSQL17 results are tracked in the PR separately.
