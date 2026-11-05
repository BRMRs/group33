# Debug 双端照片与计划在线链路

Refs #15, #10, #13, #14, #17. Integration owner: Rui / BRMRs.

The project lead explicitly authorized this integration on 2026-11-05: reuse existing PRs across the previous role boundaries, use Debug anonymous identities first, connect pairing → private photos → plan creation → Elder plan reading, and defer physical-device acceptance. This authorizes implementation, not other members' reviews, protocol signatures, or a main-branch merge bypass.

## What is connected

```text
Debug launcher (two independent installations)
  anonymous HTTP login → private encrypted session / captured generation
  actual pairing request → Elder acceptance → authorized current-Elder context
  native photo component → retained private source → real multipart upload
  caregiver private content GET → bounded image decode → existing plan editor
  original operation journal → real plan confirmation → scoped Room collections
  Elder refresh → Room observation → same medication, dose, time and date bounds
```

The default Debug launcher uses these real adapters. UI calls ViewModels; ViewModels consume Repository interfaces. HTTP responses update private, account/environment/installation-scoped Room rows; they are not a separate UI data source. Multiple active relationships require selection from the server-authorized list. Pending requests grant no current-Elder context.

The Android wiring, test launcher, HTTP client, cache and anonymous-login composition are in `src/debug`. Release keeps the pre-existing launcher and does not register this Application, Internet permission, cleartext opt-in or online Activity. This does not claim the Release app has this online flow.

## Reused sources, with original authorship retained

| PR | Exact assembled head | Reused scope |
|---|---|---|
| #99 | `42f44ebe7889d97d96fd679cd42c309a5b24fbd9` | Cumulative S2 pairing/photo/plan HTTP and persistence prerequisites, schema tooling and bounded SQL; includes upstream fixes |
| #84 | `6bc1e4e8e45320bce0266e7c3198093d72bfac19` | Private environment-scoped session and installation identity |
| #85 | `efd566e2373e1da80cdf6cd3e916658dce546792` | Original-operation journal recovery and platform clock |
| #82 | `460c97fb2c97bb075b2e405512bd1811888110a2` | Camera/picker, private source retention, preview and submission ViewModel |
| #86 | `f484a4a24406ec1aabf3154cbeaa75d5b494f9fb` | Plan editor and save/form ViewModels, including its #43/#58 prerequisites |
| #100 | `a0b5cd8bb3ae5b31ad23e5e52f5f1df6d0ee810b` | Trusted caregiver/Elder context resolver |

Assembly merge commits preserve those histories. An assembled feature branch is not main delivery, approval of its prerequisites, or completion of their parent issues. This integrated PR is intentionally larger than the normal 500-line preference: it retains the existing prerequisite histories and adds one runnable cross-module outcome; the wiring is reviewed separately from the original component contributions.

## Run the actual local service

Prerequisites: JDK 17, SDK 35, Gradle wrapper, two independent Android installations. Use fictional data only.

```sh
CHAIN_PHOTO_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/group33-debug-photos.XXXXXX")"
PHOTO_ENABLED=true PLAN_ENABLED=true PERSISTENCE_MODE=memory \
  PHOTO_OBJECT_ROOT="$CHAIN_PHOTO_ROOT" \
  ./gradlew :server:bootRun --args='--server.port=18082 --server.address=127.0.0.1'
```

The private local root must have POSIX 0700 permissions, as produced by `mktemp -d`. This test runtime uses the real HTTP/domain services with an **in-memory business store and private local-file photos**. It does not prove PostgreSQL/Supabase/Render operation or Server restart persistence. Restarting this test service loses its business state and random signing key; sign in again and repeat the fictional flow. Android process restart is tested independently below.

```sh
./gradlew :android-app:assembleDebug :android-app:assembleDebugAndroidTest
```

The default address is `http://10.0.2.2:18082`, the emulator-to-host route. Install `android-app/build/outputs/apk/debug/android-app-debug.apk` on each emulator. Pick Elder on one and Caregiver on the other; an installation's first role is fixed by the actual service.

1. Elder: generate a pairing code.
2. Caregiver: enter that code and request pairing.
3. Elder: refresh and explicitly accept the caregiver.
4. Caregiver: refresh; choose from authorized Elders if more than one exists.
5. Elder: photograph/select a fictional medicine box, inspect the preview, then submit.
6. Caregiver: refresh, open the actual private image and create a plan using the existing editor. Confirm image inspection and the timezone comparison; unknown Elder timezone is not invented.
7. Elder: refresh. The same medication, dose, times, execution zone, date range and active/inactive state appear from Room-backed authorized reads.

For a later USB-connected physical device, forward the local test port on each device and build the Debug APK for `http://127.0.0.1:18082`:

```sh
adb -s DEVICE_SERIAL reverse tcp:18082 tcp:18082
./gradlew :android-app:assembleDebug -PdebugServerUrl=http://127.0.0.1:18082
```

Physical camera, OEM lifecycle/background behavior and two-real-device acceptance remain deferred as requested. No physical-device result is inferred from an emulator run.

## Automated local two-emulator verification

Start the actual service above, then run on two **disposable, fictional emulator installations**:

```sh
ANDROID_HOME=/path/to/sdk python3 scripts/run_debug_online_chain.py \
  --elder emulator-5560 --caregiver emulator-5562 \
  --locale en-US --output /tmp/group33-chain-en --reset-test-apps
ANDROID_HOME=/path/to/sdk python3 scripts/run_debug_online_chain.py \
  --elder emulator-5560 --caregiver emulator-5562 \
  --locale zh-CN --output /tmp/group33-chain-zh --reset-test-apps
```

The harness refuses physical-device serials. `--reset-test-apps` explicitly clears only these designated fictional emulator installations. The per-app `--locale` option requires API 33+. Pairing codes and bridge IDs travel through process memory and app-private `no_backup` files, never command-line values or printed logs; no credentials enter the bridge.

The opt-in instrumentation sequence exercises the actual launcher login, pairing controls, original photo component, real editor save and Elder read-only view. Its photo source is a generated fictional PNG passed through the native private-source/preview/submission path, not a mocked upload. The separate native-camera smoke test uses the emulator's emulated camera, its shutter/Done result, the private preview, and a real confirmed JPEG upload.

Additional phases verify:

- A pending relationship provides no access; unrelated/unknown content is refused without signing out a valid session.
- A force-stopped caregiver process restores its encrypted session, original command record and actual Room plan; PIDs differ.
- The real service commits a new plan, then a test transport drops the response. After another force-stop, recovery retains the original operation ID, returns the original receipt and leaves the plan count unchanged on repeated recovery.
- A delayed old 401 cannot clear a newer successful login.
- A transient 503 read preserves the last successful data/time, marks it stale and keeps the authenticated session.
- Revocation clears the current-Elder/editor/visible plans and subsequent real photo/plan reads are denied.

Fault responses exist only in instrumentation transport subclasses around real HTTP calls. The normal Debug launcher registers the actual transport without faults or fake feature repositories.

## Persistence and concurrency boundaries

Credentials remain in #84's encrypted private store. Business snapshots and operation payloads live in private `noBackupFilesDir` Room databases; photo draft and replay files stay private and are retained for unresolved operations. No destructive migration, automatic age deletion, or upload cleanup is added.

HTTP uses a single captured credential generation, bounded connections/reads/bodies, no redirects and no credential-bearing URLs. A shared session gate guards cache publication against explicit sign-out/login. Serialized refreshes prevent older reads from overtaking newer refreshes. A successful plan receipt is persisted before cache reconciliation; reconciliation performs current authorized GETs instead of overwriting Room with the potentially old receipt fields. Unknown completion is not rejection and does not create a replacement operation ID.

The plan and medication GETs use the same captured client session and are published in one Room transaction after validating references. They are **two service reads**, not a claim of one cross-collection Server snapshot transaction. Full snapshot/occurrence overlays remain separate work.

## Deliberately remaining

- Formal phone-account/installation/business binding and #96 joint acceptance; no phone SDK or SMS call is introduced.
- V2 group actions, Help/takeover, caregiver follow-up, probes and notification delivery under #24.
- AlarmManager/WorkManager scheduling, Due execution, offline dose actions, dashboard/history and final two-device acceptance.
- Plan editing/deactivation and seven-day occurrence-overlay preview are not exposed by this create/read slice; unsupported writes return NotSubmitted and preview reports UNKNOWN, not a fabricated successful empty preview.
- Existing human/role reviews for original PRs and independent review of this new wiring. Nothing here signs for another member or closes #15/#12.

## Validation record

Final execution details, screenshots and phase results are recorded in [the integration evidence](evidence/DEBUG_ONLINE_CHAIN.md). Local build commands are `gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest`; ordinary tests, device phases, caches and deferred physical/Server-persistence evidence are reported separately.
