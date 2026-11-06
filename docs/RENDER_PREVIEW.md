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
Configure one free external HTTP monitor to request:

```text
https://<service>.onrender.com/health
```

The monitor interval must be shorter than 15 minutes. The selected monitor for
this preview is UptimeRobot Free at its five-minute interval. It both keeps the
preview active during the test window and reports failed health checks.

For an on-demand wake-up from a developer machine:

```bash
scripts/wake_render_preview.sh https://<service>.onrender.com
```

## Limitations

- Keeping `/health` active prevents idle spin-down only. Render can still
  restart or replace a free instance.
- A restart, redeploy, or spin-down loses all in-memory state and local photos.
- The 750 free instance hours are shared by all free services in the Render
  workspace. A continuously active preview can use almost the full allowance.
- Persistent PostgreSQL and object storage remain follow-up work in issue #16.
