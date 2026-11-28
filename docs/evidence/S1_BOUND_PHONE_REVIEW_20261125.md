# S1 bound-phone review corrections — 2026-11-25

Refs #118 / #144. Exact original input:
`8048037a6d2fe4cd27817fdeae27106388124010`. This is a follow-up to the
existing S2 implementation, retaining its original author and regression cases.

## Standards

One confirmed contract-boundary finding on the original input: a successful
reservation commit could consume the remaining original deadline or lease before
supplier entry. The original code still called grant/verify, then rejected only
completion. The accepted refresh design RF03 says no grant/verify at D; accepted
contracts take priority under ENGINEERING_PLAYBOOK section1. Three independent
real-PostgreSQL commit-delay cases failed on the actual provider call counters.
The timely control passed. It is not a live provider or SMS result.

The fix revalidates the original bound attempt/lease in a short transaction, then
checks the remaining deadline/lease budget after that validation commit returns,
immediately before calling the provider. A full15-second supplier budget must fit;
no provider call happens after expiry or when that budget cannot fit. Supplier work
remains outside DB transactions. Agent-assisted incremental standards review found
no remaining blocker or unrelated scope.

## Spec

One confirmed original-scheme binding finding: the persisted attempt only stored
JWT audience/profile, while refresh returned the current constructor scheme.
Recreating the service with a different scheme but the same audience could refresh
an old capability into that scheme or use its new provider for proof verification.
Two independent real-PG counterexamples failed. The accepted refresh proposal
requires the original persisted profile/scheme, not a client-selected or latest
configuration value; it does not require an audience change to hide this case.

The fix persists `scheme_code` with attempts and registration tickets and checks
it on lookup/claim/completion/close/ticket consumption. Foreign-scheme requests are
rejected before provider work. A foreign-scheme ticket rejection leaves the original
ticket usable in its original scheme. JWT audience/profile and the existing wider
profile-scoped proof-fingerprint retention are unchanged. Incremental spec review
found the original gap closed without a new authentication mode or wire field.

## Before and after execution

- Original exact head: BoundPhoneJwtTest2 and15 identity +6 flow PostgreSQL tests
  passed independently.
- Original external probes:12 methods,7 passed/5 failed,0 errors/skips. The failures
  were3 supplier-entry boundary cases and2 scheme-binding cases; inherited flow
  methods and timely control passed.
- Corrected tree: actual PostgreSQL16 fixture,15 identity +9 flow +7 supplier-entry
  methods =31 executed, zero failures/errors/skips. BoundPhoneJwtTest2 also passed.
  The fixture was a newly initialized loopback-only cluster on55432, with a fresh
  database and random schemas. It touched no pre-existing cluster, cloud DB or
  supplier. Each runner stopped its own cluster and removed its fixture password.
- Full `gate1Check test :android-app:lintDebug :android-app:assembleDebug
  :android-app:assembleRelease --console=plain --no-daemon` passed. Server630,
  Android Debug135/Release134 reports executed; Shared18 was FROM-CACHE. All have
  zero failures/errors/skips. Variant/full/focused overlaps are not distinct tests.
- The current dedicated CI selector `*BoundPhone*PostgresTest` already includes the
  new `BoundPhoneSupplierEntryPostgresTest`, so no weakened/disabled CI or extra
  workflow is needed. Final published-head CI is a separate execution.

The three added scheme/ticket cases are in BoundPhoneFlowPostgresTest. The new
supplier-entry class preserves the original three failure probes and timely
control, then adds lease-expiry, insufficient-budget and validation-commit delay
controls. Prior author test methods remain unchanged.

## Schema and rollout boundary

Only the **unapplied test prototype** gains scheme_code on attempt/ticket rows.
No existing versioned migration or deployed DB is modified. The subsequent reviewed
forward migration and actual primary deployment composition must include these
bindings before activation. Do not copy prototype SQL to a live database, enable
old/new auth routes together or treat this fix as the whole PS2/production service.
This review is agent-assisted technical evidence, not a teammate signature or proof
of live SDK/phone/Render/Supabase/device acceptance.
