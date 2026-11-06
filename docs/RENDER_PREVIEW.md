# Render PR #110 preview

This deployment is an explicitly temporary cross-network test environment for
the PR #110 integration revision. It is not a production environment.

## Runtime

- Render Free Web Service in Singapore, built from `Dockerfile`.
- In-memory business state (`PERSISTENCE_MODE=memory`).
- Photos stored under the instance-local `/tmp` filesystem.
- Plan, photo, and occurrence endpoints enabled for the end-to-end device flow.
- Render generates `JWT_SECRET`; no credential is committed to Git.
- Automatic deploys are disabled so the reviewed preview revision remains fixed.

## Wake and keep-alive

Render spins a free web service down after 15 minutes without inbound traffic.
The container starts a small keep-alive loop when `KEEP_ALIVE_ENABLED=true`.
Every 600 seconds it requests its own public Render URL:

```text
https://<service>.onrender.com/health
```

The interval is deliberately shorter than 15 minutes. Because the request goes
through the public Render URL, it arrives as inbound traffic and resets the idle
timer. The loop runs in the same container, so it prevents idle spin-down but
cannot wake an instance that Render has already stopped for another reason.

For an on-demand wake-up from a developer machine:

```bash
scripts/wake_render_preview.sh https://<service>.onrender.com
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
