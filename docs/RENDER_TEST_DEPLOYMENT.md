# Isolated Render test deployment

Refs #16. This is a test candidate on top of the Supabase runtime PR #80 and latest merged main. Keep Draft dependencies and actual human review distinct from test deployment. No production release, migration execution or Gate completion is implied.

## Configure the web service

For the user-owned isolated test workspace, connect the public repository URL manually when the owner-scoped GitHub App installation is unavailable. This does not install an App on the repository owner's account or enable automatic deploys. Continue with the same existing isolated Supabase project/private bucket.

| Render field | Test value |
|---|---|
| Name | `group33-s2-test-api` (choose another unique suffix only if occupied) |
| Language | Docker |
| Branch | `feature/16-render-test-deploy` |
| Region | Virginia, USA, near the already selected US East test database |
| Root Directory | empty; the shared module/catalog are outside `server/` |
| Dockerfile Path | `deploy/render/Dockerfile` |
| Docker Build Context | repository root (`.`) |
| Docker Command | empty; use the image entrypoint |
| Compute | Free, `$0/month`; the form may initially select a paid plan |
| Health Check Path | `/health` |
| Automatic deploys | disabled/unavailable with this public-URL connection |

Build/Start command fields are replaced by Docker settings. The image builds the original Server/Shared sources and their tests using the repository wrapper/version catalog, with a small standalone Gradle root that excludes Android. It requires no Android SDK and changes no root Gradle or module source. Both Java17 image indexes are pinned by digest. The build has no private ARG/ENV, file imports, provider connections or schema setup; the allowlist `.dockerignore` excludes generated/local/private files.

## Import private runtime settings

From this source checkout, export the existing outside-checkout, owner-only Supabase test settings:

```text
python3 scripts/cloud/prepare_render_test.py /absolute/private-directory
```

This creates a private `render.env` beside the original settings. It retains the exact existing database/JWT/receipt/Storage values and refuses changed or symlink output. It contains recoverable credentials even where base64 encoded: import it only with Render's **Add from .env** for this independent test service, never into Git, chat, logs, screenshots or a build command. Existing files/keys are not rotated or overwritten.

The four keys are `GROUP33_RENDER_SETTINGS_B64` (the eight existing endpoint/role/password/JWT/project/bucket properties), `GROUP33_RENDER_RECEIPT_KEY_B64` (the exact32 binary bytes), `GROUP33_RENDER_DATABASE_CA_B64` (public database CA), and `GROUP33_RENDER_STORAGE_SECRET` (server key). No administrator password or Render API key is required. Do not add these values to Docker build arguments. Render makes service environment values available as build args, but this Dockerfile never declares or references them.

At runtime, the Java helper validates bounded canonical encoding/UTF8, exact property keys, certificate, receipt length, secret format and unprivileged port. It materializes mode700/mode600 private files on ephemeral storage. The business runtime still performs endpoint/role/TLS/schema/cipher checks; a missing/invalid private input exits64 without memory fallback. The launcher fixes cloud metadata/private object modes, binds `0.0.0.0:$PORT`, sets bounded container-aware heap percentages, removes injected Java options and replaces itself with the backend under an environment allowlist. Bootstrap exits before the backend starts. The final image runs as uid/gid10001; no keys are stored in its layers. Local harnesses may choose the documented `GROUP33_APP_DIR`, `GROUP33_BIND_ADDRESS=127.0.0.1` and `TMPDIR` overrides; test service defaults need none of them.

## Verify, then retain evidence

Only start the service after the exact branch has passed both repository CI and container CI, and the four private values have been imported. Record the deployed commit, image/deploy ID and public HTTPS base URL. Check `/health`, then repeat fictional login, pairing, private photo authorizations/conflict/original receipts, medication/plan confirmation/edit/deactivation and revision reads. Perform a manual service restart/redeploy and verify old identity/JWT/relations/photos/receipts; denied reads after revocation must remain denied. Health alone does not prove Storage permissions or every database operation. No cloud database restart, destructive schema action, real medical data or production migration is authorized by this procedure.

Free services sleep after inactivity and use ephemeral local files; this is a test/demonstration constraint. Business data remains in the already prepared PostgreSQL/private Storage, with stable original JWT/receipt keys. Never regenerate keys to fix a redeploy failure. Existing direct-peer rate limits and `forward-headers-strategy:none` remain unchanged; trusted proxy/client IP policy and production pooling/migration/rotation need their own reviewed configuration. Formal device/fallback/Render acceptance is recorded separately from local launcher tests. Stopping the service does not migrate identities or pending operations to the independent local fallback or delete cloud test rows/objects.

Official references: [Docker runtime/build context/secrets](https://render.com/docs/docker), [private environment import](https://render.com/docs/configure-environment-variables), [web service ports](https://render.com/docs/web-services), [regions](https://render.com/docs/regions), [Free limitations](https://render.com/docs/free).
