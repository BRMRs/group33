# S1 confirmed-interface foundation work

Authorized by Rui on 2026-10-29: implement the confirmed public foundations until further behavior needs another role's confirmation. Base: main `999e182`. Existing dirty checkout is preserved; this independent branch owns only the S1 change.

## Deliveries

1. Private session storage/lifecycle on the existing UserView, RepositoryScope and SessionView models: private no-backup encrypted records, stable per-environment installation identity, atomic writes, generation-checked authentication completion, expiry/clear, metadata-only observation and internal credential acquisition. No new authentication wire/endpoint or production fake.
2. Independently implement existing OnlineCommandRepository recovery semantics over merged #45, with feature-owned transport/cache integration. Preserve original key/payload, explicit rejection retry, unknown completion and confirmed cache reconciliation. This follows after session validation, with a separate reviewable change if needed.
3. Record concrete remaining cross-role boundaries: actual login HTTP/role selection/current Elder host with C1/S2; V2 groups, Help, probes/notification wire with #24. Do not invent these to claim a full app flow.

## Verification

Use real Android noBackupFilesDir, Android Keystore AES/GCM and AtomicFile, with explicit per-file thread/process locking; AtomicFile alone provides no locking. Test real reopen, concurrent installation creation, stale-auth completion, logout/expiry, corruption, environment isolation and absence of plaintext credentials. Pure state counterexamples use deterministic clock/fault injection where useful. Run repository policy, manifest checks, gate1Check/test, lint/APK and applicable API23/35 device tests. Record actual results and limits, not test-only authentication as production.

Scope/review: Refs #10/#17. BRMRs authors the S1 work; C1 tianiridescent reviews consuming lifecycle boundaries, S2 XinyanXue reviews credential/privacy/authorization boundaries. Their actual final review remains outstanding until performed. The existing public AuthRepository login wire is not changed; C1 owns feature login integration.

References checked 2026-10-29: [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile), [cryptography](https://developer.android.com/privacy-and-security/cryptography), [no-backup files](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()).
