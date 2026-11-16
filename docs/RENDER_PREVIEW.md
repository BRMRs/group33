# Render cross-network test service

This deployment is an explicitly temporary cross-network test environment for
the integration revision merged by PR #110. It is not a production environment.

The shared test endpoint is:

```text
https://group33-pr110-preview.onrender.com
```

The service keeps its original preview name for continuity, but new deployments
are built from `main`. Build a Debug APK for this environment with:

```bash
./gradlew :android-app:assembleDebug \
  -PdebugServerUrl=https://group33-pr110-preview.onrender.com
```

## Runtime

- Render Free Web Service in Singapore, built from `Dockerfile`.
- In-memory business state (`PERSISTENCE_MODE=memory`).
- Photos stored under the instance-local `/tmp` filesystem.
- Plan, photo, and occurrence endpoints enabled for the end-to-end device flow.
- Render generates `JWT_SECRET`; no credential is committed to Git.
- Automatic deploys are disabled. A maintainer must deliberately deploy a
  reviewed `main` revision from the Render dashboard.

## Wake and keep-alive

Render spins a free web service down after 15 minutes without inbound traffic.
The container starts a small keep-alive loop when `KEEP_ALIVE_ENABLED=true`.
Every 600 seconds it requests its own public Render URL:

```text
https://group33-pr110-preview.onrender.com/health
```

The interval is deliberately shorter than 15 minutes. Because the request goes
through the public Render URL, it arrives as inbound traffic and resets the idle
timer. The loop runs in the same container, so it prevents idle spin-down but
cannot wake an instance that Render has already stopped for another reason.

For an on-demand wake-up from a developer machine:

```bash
scripts/wake_render_preview.sh https://group33-pr110-preview.onrender.com
```

## Limitations

- Keeping `/health` active prevents idle spin-down only. Render can still
  restart or replace a free instance.
- The self-check produces a tiny amount of outbound and inbound traffic. Render
  can suspend free services that exceed workspace usage limits.
- A restart, redeploy, or spin-down loses all in-memory state and local photos.
- The 750 free instance hours are shared by all free services in the Render
  workspace. A continuously active preview can use almost the full allowance.
- Persistent PostgreSQL and object storage remain follow-up work in issue #16.
