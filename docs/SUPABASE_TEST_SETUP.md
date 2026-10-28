# Isolated Supabase backend test setup

This is an opt-in S2 integration candidate for #16, using the selected Supabase PostgreSQL and private Storage stack. The [evidence record](evidence/S2_SUPABASE_RUNTIME.md) describes the actual cloud slice tested; following this guide alone is not a passed provider test or production release. The default remains memory, photos/plans disabled. Local fallback remains `postgres-local` plus `local-file`, independent of cloud settings and credentials. No shared API field changes.

## Account and project

Use an isolated test organization/project. Confirm the selected plan before creating resources. Creating a project provisions a database; use the dashboard password generator and save that administrator password privately. This password is not needed in chat, source or the generated application runtime configuration.

From the project dashboard:

1. Copy the **project reference** from project settings. It is the 20-character ID, not the organization ID or project display name.
2. In **Connect**, choose **Direct connection** (requires a reachable IPv6 route) or the shared **Session pooler** on port5432 (IPv4 alternative). Copy only the hostname. Transaction pooler port6543 and custom endpoints are intentionally not enabled by this slice.
3. Download the project's database CA certificate from database SSL settings. The runtime forces `sslmode=verify-full` and this CA; URL query parameters, embedded credentials, redirect/TLS overrides and unrelated hosts are rejected. A session-pooler connection verifies the client-facing pooler certificate; it does not establish facts about the provider's internal network hops.
4. In Storage create a **private** bucket, for example `group33-s2-test-photos`. Keep Public OFF and do not add client read/write policies. Permit `application/octet-stream` and at least5,242,880bytes per object (or leave MIME unrestricted). The business validator separately validates actual JPEG/PNG bytes and controls outward MIME. Do not add public/signed URL serving.
5. In API Keys obtain a backend `sb_secret_...` key. It bypasses Storage RLS and must be used only behind the existing backend JWT/current-identity/relationship checks. Do not use a publishable/anon key or put any key in Android. No Storage bucket is automatically created, repaired, made public or deleted by the backend.

Official references: [database connections](https://supabase.com/docs/guides/database/connecting-to-postgres), [SSL enforcement](https://supabase.com/docs/guides/platform/ssl-enforcement), [private buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals), [API keys](https://supabase.com/docs/guides/getting-started/api-keys), [pgJDBC TLS](https://jdbc.postgresql.org/documentation/ssl/). Dashboard labels can change; use the equivalent settings.

## Private local configuration

Use a directory outside the checkout, owned by you with mode700. For example, from the source checkout run:

```text
python3 scripts/cloud/prepare_supabase_test.py prepare /absolute/private-directory
```

Edit **only the private** `settings-input.json` generated there. Fill the project reference, database hostname, private bucket ID, backend key and certificate file path. Save the CA file to the indicated path. Do not attach this file or screenshots of its contents to GitHub/chat. The template and all generated files are owner-only; a local `.gitignore` excludes everything in that private directory.

```text
python3 scripts/cloud/prepare_supabase_test.py configure /absolute/private-directory
```

The helper performs no network request. It creates stable random JWT signing material, a 32-byte receipt-encryption key, a random unprivileged database login/password, a fresh random `group33_s2_...` schema and private `application.properties`. The runtime properties contain the password. The private `initialize-test-schema.sql` contains its SCRAM verifier, which is also sensitive; neither file may be committed or shared. Existing output is never overwritten or rotated by a rerun. Preserve the original private directory to recover old identities and encrypted receipts after restart.

In this test project's **SQL Editor**, run the complete private SQL file once as an administrator. It creates only the fresh named role/schema and applies the existing prototype v1-v8 SQL with source hashes in one atomic `DO` statement. The renderer qualifies the known prototypes' table/function identifiers and regclass literals, including function bodies, so they do not depend on a persistent search path. It is a renderer for those trusted prototypes, not a general SQL parser. Existing role/schema names cause failure instead of replacement. Runtime login receives schema USAGE, table SELECT/INSERT/UPDATE/DELETE, sequence USAGE/SELECT and function EXECUTE; it does not own the schema/tables or receive CREATE, superuser, role/database creation or BYPASSRLS. The schema must remain outside Supabase's exposed Data API schema list. This prototype initializer is not a production migration/upgrade/history/rollback system.

If the editor reports a missing relation, first check the selected execution scope and inspect the expected private schema with the restricted application account. Do not rerun initialization, regenerate keys or drop an existing schema merely because the editor shows an error. A local regression deliberately resets the DDL search path and verifies both atomic rollback on failure and successful schema-qualified initialization; it does not establish the cause of any particular dashboard error.

Build `:server:bootJar` with JDK17, then run the tested jar with:

```text
java -jar server/build/libs/server-0.1.0.jar --spring.config.additional-location=file:/absolute/private-directory/application.properties
```

The generated test config binds only `127.0.0.1:18086`. The command contains only a private file path, not credentials. Startup verifies required tables/columns/guard and decrypts stored issuance receipts with the supplied original key. A cloud role with superuser/BYPASSRLS is rejected. No startup DDL or fallback to memory/local files occurs on error. Storage configuration is validated locally at startup; private bucket metadata is checked on every object operation, not proven by a green `/health` response.

## Real-provider verification procedure

With fictional data only, record the exact commit and settings mode (never secret values), then use actual business HTTP to log in, request/accept pairing, upload a valid photo and read it as the owner/active caregiver. An unrelated or revoked caregiver must fail. Retry the same operation and original bytes to get the same receipt; conflicting bytes must fail. Verify the object is inaccessible through a public URL and without server credentials. Check provider bucket policies independently; the server's bucket metadata check cannot prove that no privileged policy exists or prevent an administrator changing it mid-request.

Restart the complete backend JVM with the same private directory: original JWT/current identity, accepted pairing, photo metadata/content and original receipt must survive. Repeat supported plan confirmation/edit/deactivation when enabled. Record network/provider failures and original-operation retry recovery without exposing SQL/provider diagnostics. Cloud project pause/restart is a separately authorized provider operation, not implied by restarting the local backend. This guide and local TLS protocol stand-in tests do not count as those cloud results.

Keep the local demo in a separate private directory with independent JWT/receipt/database/photo material, `postgres-local` and `local-file`. Cloud pending operations/sessions/data are not silently migrated. Android two-device workflows, Render deployment/proxy policy, production migrations/pooling/key rotation, full dose/sync/alerts and #24 pending protocols still require their own reviewed integration and acceptance. No tablet is needed for this initial backend/cloud setup.
