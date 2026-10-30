# Private Android session foundation

Refs #10/#17. Owner: BRMRs (S1). Consumers: C1 authentication/session host and feature HTTP adapters. Required non-author perspectives: tianiridescent (C1 lifecycle), XinyanXue (S2 credential/privacy boundary). This is not a review/signature by them.

## Observable behavior and consumption

`core/session/PrivateSessionStore` implements real device-local persistence. It consumes existing `UserView`, `RepositoryScope`, `SessionView`, `Outcome` and local failure codes. It does not change Shared DTOs, the public AuthRepository declaration, authentication endpoints or product role-selection behavior.

1. The application host chooses a configured environment and creates its store. Collect `observeSession()`; only existing user/readiness metadata is emitted, never token text. A collector restores the private record and schedules expiry while observed. Credential acquisition always rechecks local validity; a client clock is not proof of server authorization.
2. C1's trusted authentication adapter calls `beginAuthentication()`. The random installation UUID is durably created before returning; reauthentication keeps it. Beginning a new authentication invalidates older attempts/leases and clears the previous credential. This is an internal lifecycle operation, not a UI decision to start authentication.
3. Only after a verified authentication response may that adapter call `completeAuthentication(attempt, user, token, expiresInSeconds)`. Use the original attempt and the server-returned user/role. The store does not validate a JWT or turn a client-selected principal into an authenticated caller. Expiry is conservatively measured from attempt start; delayed, stale, expired, invalid or overflowing responses cannot create READY.
4. Feature HTTP infrastructure acquires a `SessionLease`, obtains the internal bearer credential, then checks that lease again before applying asynchronous results. Tokens never belong in ViewModels, Shared business records, logs, URLs or diagnostic strings. An obsolete 401 uses `invalidate(originalLease)` and cannot clear a newer login.
5. Explicit logout/environment deactivation uses `clear()`. The host cancels old subscriptions/work before activating a different environment. Clearing affects credentials only; it preserves the installation ID, business cache, pending commands, accepted receipts and private photo sources. Scope isolation is not migration or authorization.

Both stores and observers in the same application process share metadata and an invalidation epoch. Encrypted records and generations survive process termination; runtime leases intentionally do not. Cross-process writes are file-locked and credential reads revalidate the persistent generation. Cross-process live Flow notification is not provided; the present manifest uses one application process. A future separate-process component needs an explicit invalidation bridge rather than assuming in-process Flow is cross-process.

## Failure and privacy boundary

- Files live exclusively under `noBackupFilesDir/sessions/` with hashed namespace/environment filenames. Android Keystore owns a per-profile AES-256 key; AES/GCM authenticates ciphertext and the selected environment. No plaintext/fallback credential file is written. This is not hardware-attestation or unlocked-device/biometric authentication.
- A bounded, versioned encrypted record retains installation UUID, session generation, authenticated user, token and timing. Per-file JVM monitor and OS file lock protect independent instances; AtomicFile supplies recovery/atomic replacement but not locking. File writes sync and verify readback before reporting success. No new dependency or permission is introduced.
- Corrupt/unreadable records and unavailable keys produce typed STORAGE_UNAVAILABLE, clear observable user metadata and invalidate in-process leases. Existing records are not silently replaced with a new installation identity or encryption key. Recovery UI/account policy requires C1/S2 agreement.
- A failed clear is not reported successful. The current process blocks previous attempts/leases and retains a clear intent; later operations retry durable clear before restoring or granting credentials. If the process dies while storage remains unwritable, an unpersisted clear cannot be guaranteed across restart. The host must surface/retry the failure, not claim durable logout succeeded. Server token revocation is not invented by this local store.
- There is no destructive migration/reset or deletion of business history. Version 1 is private initial format; future changes need compatibility/migration review. Rolling back the unused adapter leaves encrypted records and keys intact.

## Evidence so far — 2026-10-29

- API35 real Keystore/file tests cover stable installation and cold-store restoration, no plaintext credential/user/installation in files or diagnostic text, concurrent instances, stale-auth rejection, clear preserving real journal entries, old-401 fencing, exact expiry/clock-before-issue, environment isolation, corruption, missing key, invalid response, actual filesystem failure and observable expiry.
- Four regressions were actually reproduced before production correction: expiry between READY emission and timer setup left the observer waiting; a failed clear allowed the old credential after storage recovered; same-user reauthentication with conflated public metadata lost its expiry notification; wall-clock rollback caused an early wakeup without rearming. Original rejection/timeout assertions were preserved. Internal generation/expiry/epoch observations now drive a rechecking timer, while public observers still receive only SessionView.
- A separate two-phase probe persisted the session, force-stopped the test app, started a different process and recovered the same installation/user/token from the real encrypted record. Expected identifiers stayed in private test files and were not printed. This is actual process termination, not a database-close simulation, and not actual reboot acceptance.
- Reproduction: build `:android-app:assembleDebug :android-app:assembleDebugAndroidTest`, install on a designated test emulator, run `core.session.PrivateSessionStoreTest` and `SessionProcessRecoveryTest`. The latter normally performs a single-process reopen; the explicit `sessionProbe` plus `phase=prepare`/force-stop/`phase=verify` invocation proves cold-process recovery. All fixtures and key aliases are test-owned and removed after successful verification.

Final independent checks on this candidate: API23 and API35 each execute 27 core instrumentation tests successfully (12 existing Room journal + 14 new session cases + 1 session reopen probe), zero failures/skips. The separate actual process-termination prepare/verify probe previously executed twice on API35 and proved a different PID restores the original private record; the subsequent observer-only correction does not change the disk format. These two phase executions are not two independent business cases. New session tests are actual Keystore/filesystem tests, not MockK/JVM replacements.

`gate1Check test` passes: 100 tasks, 24 executed / 76 up-to-date. Shared18, Server40, Android Debug51/Release51 reports have zero failures/errors/skips. Lint, APK/bootJar, policy, whitespace and manifest checks pass. This does not claim all baseline methods were re-executed. Exact final head/remote CI are recorded separately after publication.

Android tests are separate from ordinary CI JVM checks. There is no rendered UI change, fake registered authentication, real login HTTP, phone-provider SDK, current-Elder selector, feature Repository wiring, full command recovery coordinator, WorkManager/AlarmManager, V2 protocol or two-device delivery in this slice. Those remain separately tracked responsibilities.

## Primary platform references checked 2026-10-29

- [AtomicFile](https://developer.android.com/reference/android/util/AtomicFile): requires separate locking.
- [Android cryptography](https://developer.android.com/privacy-and-security/cryptography): Android Keystore and AES/GCM guidance.
- [Context.noBackupFilesDir](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()): private files excluded from automatic backup.
