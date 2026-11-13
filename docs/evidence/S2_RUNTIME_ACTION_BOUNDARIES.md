# Runtime action confirmation and scope boundaries

Refs #18 / #24 / PR #119. This narrow correction is stacked on the exact #119
`7fc62171b0928be27052bd5709525d5bbc6d2119` input. The original contributor branch
and all its history remain intact. S1 owns Android infrastructure review; S2
checks the Server acceptance boundary. It does not close either issue.

## Trigger and resulting behavior

- A 400/422 occurrence-registration or group-action response moves that original
  cache action to needs-attention. A successful batch drain previously caused the
  runtime to record ACKNOWLEDGED anyway. Read that action's persisted outcome under
  the current session fence: only its accepted receipt is ACKNOWLEDGED; a known
  rejection is PERMANENT_FAILURE; pending/missing/uncertain work remains RETRY.
- A pending reminder belongs to its frozen environment, installation and Elder.
  A different active execution scope waits without importing that action into the
  new scope or permanently discarding the old account's pending work. Existing
  generation/credential checks still fence the database and HTTP work.
- At T+110 minutes or later a full ten-minute Snooze cannot finish strictly before
  the original T+2h deadline. The coordinator rejects it before committing an
  action or cancelling the current prompt, matching the existing timing policy,
  feature validation and Server. Taken/Help remain available until the original
  deadline. A valid ten-minute Snooze keeps the original deadline unchanged.

Help still has no accepted Server endpoint and remains explicitly unacknowledged.
This change adds no DTO, HTTP endpoint, database migration, production dispatcher,
phone authentication, notification delivery guarantee or new product timing.

## Verification scope

`RuntimeActionBoundaryTest` runs the real Android session store, Room cache,
coordinator and debug dispatch adapter with injected fictional HTTP replies. The
same ten tests first ran against the unchanged #119 implementation: six failed
(two rejection/ACK cases, environment and installation scope mismatch, different
account discard, and the last-ten-minute Snooze). Successful original-action
replay, 503 recovery, unsupported Help and valid Snooze were controls.

The original local supplemental run used a fresh read-only emulator on API37.
Its results remain attributable to that exact input. The follow-up workflow
`runtime-action-api35.yml` checks out the exact reviewed head, builds the same ten
unchanged tests, and runs them on a disposable API35 google_apis x86_64 emulator.
`scripts/run_runtime_action_api35.py` verifies API/ABI/head, requires all ten
individual successes and the complete runner result, retains APK/source hashes
and raw output, and stops only its own emulator. Missing, skipped or partial
runs cannot count as success. The workflow uploads its report even after failure.

Read the API35 workflow result and retained artifact for the current head before
claiming API35 validation. This is not a physical-device test, live cloud HTTP,
full two-device path, vendor background behavior or formal human approval. S1
infrastructure and the requested C1/E reviews remain required. The existing #119
API35 evidence is not reassigned to this correction.

## 中文交接

仅原动作已保存的服务端接受结果可记为已确认；服务端明确拒绝仍保留待处理结果。
切换环境、安装或账号时，旧动作等待原身份恢复，不串写到新身份，也不提前丢弃。
距离原两小时截止不足完整十分钟时，不提交无法被现有服务端接受的延后动作；
已服用和求助仍按原窗口处理。本地补充反例验证不代替 API 35、实机、双端或线上验收。
