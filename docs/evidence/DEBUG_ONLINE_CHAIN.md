# Shared entry and navigation online-chain evidence — 2026-11-05

Refs #15. Read [scope and reproduction](../DEBUG_ONLINE_CHAIN.md) before interpreting these results.

The independent installations are two dedicated API35 arm64 AOSP emulators. The service is the actual assembled local Spring service with memory business state and private local-file photos. No phone supplier, SMS, cloud account, deployed migration or physical device was used.

## Automated build

`gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest --offline --console=plain` passed. Ordinary XML report instances: Shared18, Server627, Android Debug113, Android Release113; zero failures/errors/skips. Executed, cached and up-to-date tasks are distinguished in the retained local logs; repeated baseline reports are not counted as new distinct feature coverage.

After the merge-readiness fixes, the 67 Android instrumentation regressions passed on API35: the original session/Keystore, Room journal/recovery, private photo source/layout and editor tests, plus shared entry, language persistence and #92 navigation. Explicit real-reboot probe phases were not rerun or inferred from default checks.

Release inspection confirms SharedLoginActivity is the sole launcher with Group33Application. It contains shared business views/interfaces, but no DebugChain Application/Activity/repository/HTTP/cache, AnonymousLoginModel, anonymous endpoint, anonymous controls/resources, INTERNET permission or cleartext opt-in. Release does not install an authenticated provider yet.

## Two-emulator causal phases

The final harness runs fourteen phases in English and fourteen in Simplified Chinese, including the real UI path, private image bytes, plan save, actual Elder display, cold-process restoration, response-loss replay, stale401 isolation, 503 stale-cache retention and revocation. The generated images and names/dosage are fictional.

| Evidence | Result |
|---|---|
| Package launcher is #59 shared entry; authenticated routing opens real role Activity | PASS |
| #92 Plans/Records/Settings, language recreation and Back retain actual current Elder | PASS |
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
| Native emulated camera shutter/Done → private preview → confirmed JPEG upload | PASS at `faa9abd`, before this merge-readiness correction; unchanged camera component, emulator only |

The first shared-entry run exposed an issue-code tap racing the automatic home refresh: the busy operation gate ignored it. Operation buttons now reflect busy state, the lifecycle refresh waits for a ready session, and the harness waits for enabled controls. Both full final language runs passed after this correction; the failed run remains in the local evidence.

The earlier integration RED run exposed a zero `BitmapFactory.Options.inSampleSize` division in the new image host. It was corrected to start at one, bounded decoding and safe failure handling were retained, and the actual photo/editor path reran successfully. A JUnit initialization failure in the first harness attempt was also corrected; failed runs were retained, not called successful evidence.

## Merge-readiness correction

Automated Standards review found an old-Elder publication race and same-Elder replacement subscription gap. Automated Spec review found Elder sync-management controls and false recovery success. Nine new ViewModel cases cover context switching/revocation/replacement, offline/failed/unknown recovery, refresh-required, confirmed/reconciled success and rejection. Eight were run before correction (seven failed); all nine pass afterward in both Debug and Release. The two original review axes were rechecked; this supplies no human approval.

The full gate passed again; final ordinary counts above include the nine new cases. Both languages reran all fourteen actual-HTTP phases, including automatic foreground Elder plan loading with no refresh/recovery buttons or successful-sync status on the Elder page. The 67 original device regressions also reran successfully. See [the per-person merge checklist](../PR110_MERGE_READINESS.md) for actual approval requirements.

## UI records

Screenshots contain only fictional fixture data and no pairing codes or credentials.

| English | Simplified Chinese |
|---|---|
| [Caregiver confirmed save](debug-chain/en-caregiver-plan.png) | [照护者保存成功](debug-chain/zh-caregiver-plan.png) |
| [Elder sees the same plan](debug-chain/en-elder-plan.png) | [长辈看到同一计划](debug-chain/zh-elder-plan.png) |

Current phase logs/screenshots, the 67-test regression log, build log and Release inspection results are retained in the local `reviews/formal-navigation-evidence` directory. The new shared-entry native-camera screenshot/status are retained there too; earlier evidence remains in `reviews/debug-online-evidence`. Raw logs, APKs, live credentials/bridge files and private runtime photo storage are not committed.

Physical devices, physical camera capture, API23/OEM behavior for the new host, persistent PostgreSQL runtime/restart, Supabase/Render, real phone login, alarms and V2 are not independently accepted by this run.
