# Bound phone runtime composition — partial #118 A delivery

Status: implementation candidate, not deployment or #118 completion. S1 (`BRMRs`) is the recorded temporary implementation backup in #118; S2 (`XinyanXue`) remains accountable for Server/security/deployment. C1 (`tianiridescent`) reviews the client/auth boundary. Required human reviews are not replaced by test results.

## Executable composition

```text
private startup snapshot
  exact managed V1–V12 history + read-only phone schema/privilege gate
  PersistenceRuntime.primary (also used by existing persistence adapters)
    BoundPhoneIdentityStore + attempts/tickets
    dedicated typed phone JWT + UTC clock
    bounded Alibaba adapter from #150
    explicit trusted-proxy address resolver + single-process quota
      POST /api/v1/auth/fusion/token
      POST /api/v1/auth/fusion/verify
      POST /api/v1/auth/phone/register
```

The disabled default retains the old preview behavior and does not read phone key files. Enabling bound phone disables anonymous issuance and requires the old fusion mode to be false. The three capability/proof routes have their own stateless security chain; they do not authenticate through the legacy bearer decoder. Original scheme, deadline, lease, proof replay and post-commit signing fences from #147/#150 are retained.

**Protected P0 business adapters still use their legacy identity/authorization path. Bound-phone mode therefore denies every other route except GET /health until PS2 is actually wired; legacy bearers cannot enter that path through this candidate.** This candidate does not make a phone JWT usable by pairing, photos, plans, occurrence registration, dose groups or history. PS2 current-state/effect/receipt authorization on the same primary transaction, trusted date input, formal client hosts and device acceptance remain separate #118 work. Do not distribute this as a completed phone-enabled APK or release.

## Required private configuration

Direct properties and environment mappings are in `application.yml` and `.env.example`:

| Input | Requirement / supplier |
|---|---|
| `BOUND_PHONE_AUTH_ENABLED` | Explicit `true`; old `PHONE_AUTH_ENABLED` must be `false` |
| `PERSISTENCE_MODE` | `postgres-local` for private loopback fixture, `postgres-supabase` for reviewed cloud |
| `PERSISTENCE_REQUIRE_VERSIONED_SCHEMA` | `true`; exact candidate V1–V12 release and checksums |
| `PERSISTENCE_JDBC_URL/SCHEMA/USERNAME/PASSWORD` | S2 supplies existing managed primary and restricted runtime role; no phone-only second database |
| `PERSISTENCE_RECEIPT_KEY_FILE` | Preserve the existing receipt key, exactly 32 raw binary bytes, owner-only absolute regular file |
| `JWT_SECRET` | Explicit stable legacy UTF-8 key ≥32 bytes, separate from all three binary keys |
| `BOUND_PHONE_JWT_KEY_FILE` | Separate stable 32 raw binary byte phone signing key; not hex text |
| `BOUND_PHONE_HASH_KEY_FILE` | Separate stable 32 raw binary byte phone HMAC key; not hex text |
| `BOUND_PHONE_JWT_ISSUER` | S2 supplies final HTTPS issuer, separate from legacy issuer |
| `BOUND_PHONE_JWT_AUDIENCE` | S2 supplies stable ASCII deployment profile; not installation/account ID |
| `BOUND_PHONE_ACCESS_KEY_ID/ACCESS_KEY_SECRET` | S2 supplies least-privilege supplier credentials privately; no default discovery |
| `BOUND_PHONE_SCHEME_CODE` | S2 confirms actual scheme; scene ID is not a scheme code |
| `BOUND_PHONE_ANDROID_PACKAGE` | Exactly `com.group33.medicationreminder` |
| `BOUND_PHONE_ANDROID_SIGNATURE` | S2/C1 confirm installed APK signing MD5, 32 hex digits; do not guess debug vs release |
| `BOUND_PHONE_TRUSTED_PROXY_CIDRS` | Explicit verified socket-peer/proxy CIDRs; no default, `0.0.0.0/0` or `::/0` |
| `BOUND_PHONE_SESSION_SECONDS` | Default 86400, range 1–86400 |
| `BOUND_PHONE_CLOCK_ALLOWANCE_SECONDS` | Default 60, range 0–60; future protected-state checks must use the same value |
| `BOUND_PHONE_REQUESTS_PER_MINUTE` | Default 30, range 1–120, per resolved address across all three routes |
| `BOUND_PHONE_MAX_TRACKED_CLIENTS` | Default 10000, range 1–10000; capacity exhaustion rejects rather than evicts quotas |

No real credential or account value belongs in Git, an issue, screenshots or logs. Changing the HMAC key changes phone account lookup; changing receipt/JWT keys affects receipts/sessions. No automatic key rotation or identity adoption is implemented.

## Render import and next deployment steps

Use the separate free integration service authorized in #118. Retain the existing preview and its data. Render provisioning, online migration and provider activation have **not** happened as part of this change.

1. S2/C1 review the final candidate and dependencies: #147 supplier-entry fix → #150 adapter; #142 accepted-design record; #79/#148/#149/#104 managed runtime/container/history gate; #102 reserved V11. These are inherited source candidates, not evidence that they merged or were adopted online.
2. S2 prepares an owner-only directory **outside the repository**, containing the existing `application.properties`, `receipt.key`, `database-ca.crt`, `storage-secret.key` and new `phone-jwt.key`, `phone-hash.key`. Keep the original receipt/storage material. Generate new phone keys once in the private secret system, not per deploy. Every file is owner-only; directory is 0700.
3. `application.properties` retains the eight canonical cloud keys accepted by `prepare_render_test.py`, adds `app.persistence.require-versioned-schema=true`, `app.bound-phone.enabled=true`, the eight required `app.bound-phone.*` string properties listed above and any selected bounded numeric overrides. Supply literal single-line `key=value` properties; no escaped keys, aliases, duplicate keys or continuations. Private values may use canonical Java Properties value escaping. Key paths are selected by the bootstrap.
4. Run `python3 scripts/cloud/prepare_render_test.py <private-directory>`. It creates `render.env` without printing secrets, replacing old output, or rotating input files. Base64 is transport encoding, not encryption. Import only into the independent private Render integration service.
5. The import contains the existing four variables (`GROUP33_RENDER_SETTINGS_B64`, `GROUP33_RENDER_RECEIPT_KEY_B64`, `GROUP33_RENDER_DATABASE_CA_B64`, `GROUP33_RENDER_STORAGE_SECRET`) plus `GROUP33_RENDER_PHONE_JWT_KEY_B64` and `GROUP33_RENDER_PHONE_HASH_KEY_B64`. `PORT` is selected by Render. `deploy/render/start.sh` materializes private 0600 files, forces old fusion off and forwarding strategy none, then execs the JVM without private environment variables. Do not use the root preview Dockerfile; this candidate uses `deploy/render/Dockerfile` and its paired allowlist.
6. Before any runtime start, S2 records the actual database/schema/owner/runtime-role split, migration release/SQL checksums, tested backup/restore point and explicit adoption plan. Use the separate `schemaMigrationDist` administrative tool to validate/migrate the selected schema under migration credentials. Startup performs no DDL. **Do not run the old `prepare_supabase_test.py` V1–V8 prototype initializer to adopt this phone release.**
7. Verify the actual Render socket peer and complete X-Forwarded-For chain without logging client addresses. Configure verified proxy CIDRs only after this evidence exists. Right-to-left resolution removes only configured trusted hops; caller-supplied left prefixes cannot select the quota key. Missing/duplicate/unparseable headers and untrusted socket peers fail with the API envelope. The quota is process-local: verify exactly one instance, no parallel old/new instances or autoscaling; it is not a distributed or restart-persistent quota. Test spoofed headers, alias addresses, quota exhaustion and client separation on the selected service. Do not substitute illustrative internet CIDRs for actual Render ingress evidence.
8. S2 records final storage bucket/privacy/region/retention facts and adopts the managed DB/Storage/RLS release. Publish the reviewed #146 privacy policy to a stable public HTTPS page; record the final URL and check it anonymously. Draft text and a PR link do not satisfy the SDK privacy URL.
9. Finish PS2 and remaining formal hosts before C1/S1 run the final APK against this exact service/primary/schema/profile. Record actual supplier token/verify, real SMS where used, original-attempt retry/refresh/mismatch/replay, new/existing account, revocation, protected effects, English/Chinese UI, physical device and restart evidence. Synthetic provider tests below do not prove any of these real-provider/device events.

## Formal V12 and privilege adoption

V1–V10 bytes are unchanged. V11 is inherited from #102; V12 is new and must be coordinated with S2 before another migration reserves that number. V12 creates six private phone tables with fixed identity/session keys, original attempt/scheme/lease data, single-use registration tickets and proof fingerprints. It refuses any `phone_`/`fusion_` trial object in the selected schema. There is no silent baseline, schema repair, prototype import, arbitrary fingerprint expiry or startup migration.

Use separate migration/schema owner and runtime login. The runtime must not be superuser, create roles/databases, replicate, bypass RLS, or be a member of the table/schema owner. Existing P0 grants remain separately reviewed. After administrative migration, add these explicit phone grants using `psql` identifier variables for the **reviewed actual** schema and runtime role:

```sql
BEGIN;
GRANT USAGE ON SCHEMA :"schema" TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE ON TABLE
  :"schema".phone_accounts, :"schema".phone_account_installations,
  :"schema".phone_auth_sessions, :"schema".phone_auth_attempts,
  :"schema".phone_auth_proofs, :"schema".phone_bound_registration_tickets TO :"runtime_role";
GRANT DELETE ON TABLE :"schema".phone_bound_registration_tickets TO :"runtime_role";
COMMIT;
```

These are backend-only tables in a private schema, not PostgREST tables. RLS is off for this selected backend model; PUBLIC, `anon` and `authenticated` must have no table or column privileges, including privileges reachable through role membership. V12 revokes their direct grants, while startup also rejects client-role table/column ACLs through direct or indirect membership and owner-role membership. ACL inspection covers all recorded privilege types, including PG17 MAINTAIN, without asking PG16 to parse a newer privilege name. [PostgreSQL privilege inquiry documentation](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-INFO-ACCESS-TABLE) explains why table-only SELECT checks do not cover column grants. Do not disable RLS on existing Storage/API tables. Verify required constraints and restricted role with the startup gate before enabling traffic.

Application rollback does not reverse V12. Stop new phone issuance/traffic first, retain tables/keys/accounts/attempts/history and select an application compatible with the actual release history. A V11-only application that requires exact history cannot boot against V12. Restoring a pre-V12 database loses subsequent phone state and requires an explicit reviewed recovery decision; do not drop tables, repair history or regenerate keys as a rollback shortcut.

## Reproducible evidence

- `BoundPhoneRuntimeSettingsTest`: private configuration, key separation/permissions, exclusive routes and disabled-mode controls.
- `BoundPhoneProxyRateLimitTest`: header/CIDR validation, right-to-left spoof resistance, IPv6 aliases, bounded/concurrent quota and window arithmetic.
- `BoundPhoneRuntimePostgresTest`: actual Spring Boot and HTTP, formal migrations, separate owner/runtime roles, same primary, register/login, original attempt and session retained through full Server restart, replay rejection, quota before provider/effect, schema/permission drift and old/trial release refusal. Provider is an in-test singleton with fictional inputs, no production fake-mode switch.
- Existing `PostgresSchemaMigrationTest`: full ordered migration, checksum tampering refusal, actual CLI and database restart, original encrypted receipts preserved, transactional rollback and repeat validation. Existing supplier/identity PostgreSQL tests are retained.
- `scripts/cloud/test_render_bootstrap.py`: private export/materialization, original binary key preservation, old preview compatibility, rejection/cleanup, launcher exec/environment controls. This is not a deployed Render container or production TLS check.

Evidence is scoped to the final source SHA recorded in the PR. Local logs stay outside the repository; hosted CI artifacts must name the same final SHA. S2/C1 independent approval, main adoption, cloud deployment and physical-device acceptance each require their own actual evidence.
