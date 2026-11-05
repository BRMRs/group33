# Debug online chain evidence — 2026-11-05

Refs #15. Read [scope and reproduction](../DEBUG_ONLINE_CHAIN.md) before interpreting these results.

The independent installations are two dedicated API35 arm64 AOSP emulators. The service is the actual assembled local Spring service with memory business state and private local-file photos. No phone supplier, SMS, cloud account, deployed migration or physical device was used.

## Automated build

`gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --console=plain` passed. Ordinary XML report instances: Shared18, Server627, Android Debug99, Android Release99; zero failures/errors/skips. Executed, cached and up-to-date tasks are distinguished in the retained local logs; repeated baseline reports are not counted as new distinct feature coverage.

The 61 inherited Android instrumentation regressions also passed on API35 (session/Keystore, Room journal/recovery, private photo sources/layout and the original editor). Explicit real-reboot probe phases were not rerun or inferred from their default checks. The final Release manifest retains MainActivity and contains no DebugChain Application/Activity, INTERNET permission or cleartext opt-in; the new integration classes are absent from its DEX.

## Two-emulator causal phases

The final harness runs thirteen phases in English and thirteen in Simplified Chinese, including the real UI path, private image bytes, plan save, actual Elder display, cold-process restoration, response-loss replay, stale401 isolation, 503 stale-cache retention and revocation. The generated images and names/dosage are fictional.

| Evidence | Result |
|---|---|
| Actual anonymous login and server-issued actor on two installations | PASS |
| Pending request does not grant current-Elder/content access | PASS |
| Elder explicitly accepts, caregiver context comes from authorized relationships | PASS |
| Private source → real upload → exact downloaded image bytes | PASS |
| Existing editor saves through HTTP/journal, same plan appears on Elder page | PASS |
| Force-stop restores actual private session, Room data and original command | PASS |
| Server commit with lost response, force-stop, same-ID replay, no duplicate plan | PASS |
| Late old401 does not invalidate newer successful session | PASS |
| Transient503 retains data and last sync time, reports stale, retains session | PASS |
| Revocation clears UI and real subsequent reads are denied | PASS |
| Native emulated camera shutter/Done → private preview → confirmed JPEG upload | PASS; emulator only |

The early RED run exposed a zero `BitmapFactory.Options.inSampleSize` division in the new image host. It was corrected to start at one, bounded decoding and safe failure handling were retained, and the actual photo/editor path reran successfully. A JUnit initialization failure in the first harness attempt was also corrected; failed runs were retained, not called successful evidence.

## UI records

Screenshots contain only fictional fixture data and no pairing codes or credentials.

| English | Simplified Chinese |
|---|---|
| [Caregiver confirmed save](debug-chain/en-caregiver-plan.png) | [照护者保存成功](debug-chain/zh-caregiver-plan.png) |
| [Elder sees the same plan](debug-chain/en-elder-plan.png) | [长辈看到同一计划](debug-chain/zh-elder-plan.png) |

The native emulated-camera image and original local test logs remain in the local `reviews/debug-online-evidence` run directory. Raw logs, APKs, live credentials/bridge files and private runtime photo storage are not committed.

Physical devices, physical camera capture, API23/OEM behavior for the new host, persistent PostgreSQL runtime/restart, Supabase/Render, real phone login, alarms and V2 are not independently accepted by this run.
