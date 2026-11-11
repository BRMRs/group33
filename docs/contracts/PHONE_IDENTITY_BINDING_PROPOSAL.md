# Phone account, installation and business actor binding

**Proposed design for #118 / #49 / #10 / #12 / #16; ready for joint design review, not accepted or implemented.** S2 proposal, updated 2026-11-11. Exact recommendations below require C1/S1 and Server review before production DTO/claim/schema changes. Explicit Elder consent and current care relationships remain mandatory.

## 1. Evidence and decisions requested

Current review base: main `25f4482fd1dd27c69121e71f12be697ce1c0972a`, including #117 and #91. The lead requested synchronization and design review in [#96](https://github.com/BRMRs/group33/pull/96#issuecomment-6686037146); [#118](https://github.com/BRMRs/group33/issues/118) now assigns S2 accountable ownership. Current #88 `66c67de8b11c57e8c7b759d6065aa1ca5929d210` already uses the common S1 session and keeps phone JWTs out of anonymous P0 business authorization. Its actual handoff, team-build document, provider and controller were inspected at that head. Original #94/#97 current-account protections remain separate dependencies, not implied merged implementation. No required reviewer has accepted D1–D4.

The [lead requests precise fields/errors](https://github.com/BRMRs/group33/issues/49#issuecomment-6526229077). C1 owns SDK/login/role UI; S1 owns the common private session, installation and HTTP lease; S2 owns current account/session reads, business authorization and migrations. No person's approval is recorded here.

| Joint decision | Concrete recommendation | Status |
|---|---|---|
| D1: attempt/ticket wire | Bind existing phone routes to the original installation using section3 fields | C1/S1/Server PENDING |
| D2: signed/current identity | Typed JWT plus current account/installation/session rows; UUID is a scope label, not device attestation | S1/Server PENDING |
| D3: business authority/replay | Common actor/directory, same-primary transaction checks, original installation binding | S1/S2 and C1/C2/E consumption PENDING |
| D4: errors/adoption/fallback | Section7 errors; reject old unbound credentials on new P0 path; independent anonymous LAN demonstration | C1/S1/Server PENDING |

## 2. Existing facts and preserved boundaries

- `UserView` already aliases `User`: keep that response shape. Stable userId and first registered role come from the current Server account, never a client role or phone number.
- #94 checks current phone account after signature/issuer/time and separates invalid401 from temporary503, but adds no installation binding. `/schedules` remains a legacy development result.
- Existing `VerifiedPairingActorResolver` accepts `type=anonymous`, reads `AnonymousUserStore` and returns `AnonymousUser`. Downstream P0 stores also read anonymous user metadata and retain original installation in receipts. Accepting `type=phone` at only the first resolver is insufficient.
- S1 already has `RepositoryScope(environmentId, installationId, userId)`, authentication attempt and lease. Its generation/epoch remain private local state, not Server credentials or wire fields. Current #88 has removed the parallel trial PhoneSessionStore and uses the common private session. Do not list that completed client integration as missing or recreate it; Server-bound identity and business authorization remain pending.
- No anonymous-history/account merge, fixed-role overwrite, new cross-user permission, whole-account logout on relation failure, group/Help protocol change or completed deployment follows from sign-in.

## 3. Proposed authentication requests and results

Paths remain under `/api/v1/auth`: `/fusion/token`, `/fusion/verify`, `/phone/register`. **New required fields below are proposed incompatible trial changes**, not supported requests today. Coordinate Server/C1 rollout; missing fields must not silently produce an unbound session. Real phone mode requires TLS. Auth request fields never choose an existing userId/role.

### Start the original attempt

C1 calls S1 `beginAuthentication()` before asynchronous SDK work and retains that same local attempt through registration. Proposed `POST /fusion/token` body is exactly `{installationId}`. The id is the original S1 per-environment canonical lowercase UUID; the trial currently has no body.

Response200 retains `fusionAuthToken`, `schemeCode`, `expiresInSeconds:900` and adds `authenticationAttemptToken`: Server CSPRNG32-byte base64url capability,43characters without padding. Persist only SHA256, installationId, Server profile, creation/deadline and state. Deadline is900seconds, bounded by provider token validity. A public UUID alone cannot resume/authenticate the attempt. The network adapter retains the capability privately; do not give it to Alibaba or UI/logs.

### Verify provider proof

Proposed `POST /fusion/verify` body requires exactly `installationId`, `authenticationAttemptToken`, `verifyToken`, with optional `expectedPhone` for the existing SDK SMS intent. Installation must match the original Server attempt. Capability is43 base64url characters; provider proof is nonblank, at most8192characters, passed unchanged. The SMS adapter carries its original entered number as `expectedPhone`; one-tap omits it. Preserve the existing comparison only after provider PASS, using the reviewed normalization rule: a mismatch returns generic401 `PHONE_MISMATCH` before account lookup or ticket/session creation. `expectedPhone` is never identity proof, the account lookup key, or a substitute for the provider-verified number. No client `userId` or `role` is accepted here. This explicitly preserves current #88 protection; final field and normalization acceptance remains D1/D4 review.

Claim a pending attempt using a short Server verification lease; call provider **outside** DB transaction. Proposed connect/overall/lease bounds are5/20/30seconds. Only a still-current lease may commit PASS. Persist a SHA256 proof fingerprint, unique per provider scheme/profile until the proof validity bound plus clock allowance. This claim prevents the same opaque proof issuing outcomes through multiple attempts; provider PASS remains mandatory. No raw proof/capability/phone/fingerprint diagnostics.

Concurrent live work returns409 `OPERATION_IN_PROGRESS`, `Retry-After: 1`. A failed/expired lease cannot be reopened by a late worker. For uncertain provider calls, retain the fingerprint and require fresh SDK proof rather than blindly resending it. Local cancellation does not revoke a Server result already issued; S1 completion fencing discards the obsolete response locally.

The proof fingerprint retention bound must come from verified supplier behavior/configuration, not an assumption that SDK token900seconds is also the proof lifetime. Until that bound is reviewed, do not delete consumed proof fingerprints or claim cross-attempt replay protection beyond the retained interval.

On PASS, read current account by phone HMAC. Existing account returns200 `SIGNED_IN` with stored id/role. New phone returns200 `REGISTRATION_REQUIRED` with exactly `status`, `registrationTicket`, `registrationTicketExpiresInSeconds:300`, `installationId`; no accessToken/user. Ticket is another random32-byte base64url capability. Store its SHA256, phone HMAC, original attempt FK, profile, installationId and300-second expiry, consuming the verify attempt atomically. No business operation id is created by auth.

### Complete first registration

Proposed `POST /phone/register` body has exactly `registrationTicket`, `installationId`, `role` and optional `displayName`. Ticket/profile/installation must match. ELDER allows omitted/null displayName and keeps the trial's Server name; CAREGIVER requires trimmed1–40characters. Unknown enums/fields are malformed. Ordinary existing-account login has no role-selection field.

In one transaction: lock/validate ticket, create-or-read unique phone-HMAC account, preserve an existing fixed id/role, create/read its installation row and create the session row, consume ticket on commit. Concurrent tickets converge to one account/role; a losing registration cannot overwrite it. Sign the committed session afterward. Signing failure is503; fresh provider authentication recovers that same account, not a new account or destructive rollback.

Both verify-existing and register success return200 with exactly `status:SIGNED_IN`, `accessToken`, `expiresInSeconds`, existing `user`, and **new** `installationId` echo. No ticket/TTL. Adapter compares the echo with its original S1 attempt before `completeAuthentication(attempt,user,token,expiresInSeconds)`. UI does not decode JWT to establish identity. Lost response/consumed ticket requires fresh provider authentication; it never justifies a new business key or another account. These auth capabilities are not durable Room/journal business payload.

## 4. Proposed signed phone profile

| Header/claim | Required proposed meaning |
|---|---|
| protected `alg` | Existing configured HS256 allowlist; no `none` or untrusted algorithm/key selection |
| protected `typ` | `group33-phone+jwt`, checked by phone profile |
| `iss` / `aud` | Exact deployment issuer / one audience string, e.g. `group33-api:cloud-test`; LAN has distinct audience, key and database |
| `sub` | Current stable phone account canonical UUID, same account across installations |
| `type` / `authVersion` | Existing `phone` discriminator / new integer `1`; missing/wrong version is unbound |
| `installationId` | Original attempt UUID signed by Server; never sub/hardware id/client role |
| `jti` | Server random session UUID matching one persisted session; not business key or S1 generation |
| `iat` / `exp` | NumericDate seconds; match session times. Retain configured lifetime (trial default86400seconds), no refresh-token feature. Proposed maximum clock allowance60seconds, identical at JWT/current-session boundary |
| `role` | Current stored `ELDER`/`CAREGIVER`, checked against account again |

No phone/provider proof/ticket/photo/dosage in claims. Audience is Server profile configuration, not a client environment field. Business requests carry `Authorization: Bearer ...`; body/header identity never replaces signed installation.

Validate algorithm/crypto/profile/issuer/audience/time/claim types **before DB**. Then read current account + installation + session together. Missing/revoked/mismatched rows are generic401; temporary DB failure is503. Never initialize tables or reconstruct missing rows from claims.

**Security limit:** this is account/operation scope binding, not attestation or sender constraint. A stolen Bearer remains usable as Bearer. A freshly phone-authenticated caller can assert a UUID, so UUID must not be the sole access/rate-limit credential or globally displace another account. Physical-device anti-cloning would need a separate reviewed design, not a false claim here.

## 5. Proposed persistent shape and atomicity

Logical columns/constraints only: **no executable SQL or reserved migration number**. Current-auth and P0 tables must share one managed primary DataSource/schema before transactional phone-business mode. Separate trial DB/H2/first-use DDL cannot prove that guarantee.

| Record | Required fields/constraints |
|---|---|
| `phone_accounts` | Preserve unique phone_hash/user_id, fixed role/display_name, account ids and HMAC key |
| `phone_auth_attempts` | Random id PK; unique attempt_token_hash; profile, installation_id, creation/expiry, state, lease id/deadline, nullable unique proof fingerprint per provider/profile; consumed outcomes cannot reopen |
| `phone_registration_tickets` | Existing hash/phone-HMAC/expiry plus original attempt FK, profile, installation_id; atomic single consumption; no plaintext ticket/phone |
| `phone_account_installations` | Composite PK(user_id,installation_id), account FK, created/revoked millis; **no global unique owner on installation_id** |
| `phone_auth_sessions` | Random session_id PK=jti, composite FK to account/installation, issued/expires/revoked millis, fixed auth version/profile; no raw JWT |

Account+installation rows isolate multiple accounts on one installation. Multiple installations/sessions for one account can coexist; new login does not silently revoke another device. Local logout retains installation/history/Room/journal/photos and clears credentials through S1. It is **not Server token revocation**; issued credentials remain valid until expiry unless Server state is explicitly revoked. A future Server logout/revoke endpoint needs its own contract.

Pre-authentication lookup is not commit authorization. Every protected write rechecks current account/role, installation/session and active relationship on the **same connection/transaction** as effect+original receipt. Proposed lock order: account → account-installation → session → existing feature guard/relationship/receipt. All account/session/relation revokers and writers must serialize consistently, so requests linearized after revocation cannot commit using stale authority. No second JDBC lookup, cached boolean or claims-only actor proves this.

Read snapshots keep primary read-only Repeatable Read and existing budgets. Photo proxy checks current access before serving; later requests fail after revocation, but already delivered bytes/in-flight responses cannot be retracted. Do not hold long DB locks across provider/Storage calls. Client still clears target display on access loss.

## 6. Actor, directory and original command recovery

Proposed internal `VerifiedBusinessActor(userId,installationId,displayName,role,authenticationKind)` replaces anonymous-only service parameters. Resolver accepts bound phone profile after section4 checks. Explicitly isolated anonymous demonstration uses a separate validated path. Do not insert phone users into anonymous_identity, alias accountId to installationId or trust request-selected caregiver/elder ids.

P0 stores also need a same-transaction current-principal directory for both explicit namespaces; changing only the first resolver leaves downstream anonymous-only metadata reads broken. Detect cross-namespace id collisions during conversion; ambiguous lookup fails closed, no implicit merge. Historical relationship/event snapshots remain facts, not current account-role authority.

Preserve actor+operation+target+canonical payload and original installation checks. Same account/same installation after new authentication can recover the original key/payload; different installation reusing that key gets existing409 `IDEMPOTENCY_KEY_REUSED`, without the original receipt. It reads current authorized resources, not another installation's journal. Different account/environment cannot recover it. Do **not** add jti/expiry to the business fingerprint: re-login must not itself change command identity.

Unknown retains original key; confirmed/cache-pending remains confirmed; rejection never automatically replays. Original receipt is not current resource state or a new access grant. Current relationship checks still precede medical-data/receipt disclosure. Existing business wait, quotas, canonicalization and frozen event meanings remain; supplementary installation quotas cannot rely on UUID alone.

## 7. Proposed error normalization

Existing ApiError + `X-Request-ID`; auth responses/errors and current-auth failures use `Cache-Control: no-store`. Generic messages exclude account/SQL/provider/token/installation details. These recommendations include changes from trial behavior; they are not implemented by this PR.

| Condition | Proposed HTTP/code/headers | Client handling |
|---|---|---|
| Malformed/missing/unknown JSON or enum |400 `MALFORMED_REQUEST` | No provider call |
| Well-formed invalid UUID/length/displayName |422 `VALIDATION_FAILED`, scoped field code | Retain input, no role mutation |
| Provider denies; invalid/expired/consumed/foreign attempt |401 `PHONE_VERIFICATION_FAILED`, retryable=false | Fresh auth attempt; cannot clear newer login |
| Provider PASS but SMS `expectedPhone` does not match |401 `PHONE_MISMATCH`, retryable=false | No account lookup/ticket/session; fresh matching SDK attempt, no unrelated-session invalidation |
| Invalid/expired/consumed/foreign-install ticket |422 `REGISTRATION_INVALID`, retryable=false | Fresh proof recovers current account, no consumed-ticket retry |
| Live verification/business worker |409 `OPERATION_IN_PROGRESS`, retryable=true, `Retry-After: 1` | Bounded wait with original identity |
| Invalid/old-unbound JWT, missing/revoked/mismatched current account/install/session |401 `UNAUTHENTICATED`, retryable=false, `WWW-Authenticate: Bearer` | Invalidate only captured matching lease; retain business evidence |
| Own known wrong-role operation |403 `ROLE_NOT_ALLOWED` | No whole-account logout/history deletion |
| Unrelated/revoked cross-user resource |404 `RESOURCE_NOT_ACCESSIBLE`, existence-blind shape | Clear target access/display, retain session |
| Trusted-IP auth quota |429 `TOO_MANY_REQUESTS`, retryable=true, positive `Retry-After` | Honor wait; retain evidence. Preserve current20/IP/minute as initial ceiling, not a UUID-only limiter |
| Provider/config temporarily unavailable at auth routes |503 `PHONE_AUTH_UNAVAILABLE`, retryable=true | No anonymous fallback; uncertain proof may require fresh SDK work |
| Current-auth/P0 DB unavailable or bounded DB lock/query failure |503 `SERVICE_UNAVAILABLE`, retryable=true, no invalid-token challenge | Retain session/original operation; same token may recover |

Resource404 retains the existing privacy rule; S1's client handling of403 does not mandate exposing existence with a new wire rule. Late401/success must use the captured lease and cannot clear/restore another login. Auth-endpoint failures without a matching lease do not invalidate an unrelated session. 503/429 is not definite business rejection; consumed/uncertain auth attempts follow section3, not business replay rules.

## 8. Adoption and rollout gates

1. Record D1–D4 joint decisions, then coordinate compiled Shared/JSON/Jackson/S1/C1 changes in implementation PRs. This proposal's examples are not compiled fixtures or passing acceptance tests.
2. Add forward migrations after the history actually accepted on main. Main at this review has V1–V9; the lead has separately ordered #116 dose actions as V10, then #102 indexes as V11 after #116 merges. This phone proposal does not reserve any next number or turn those unmerged versions into main history. Do not edit v1–v9, reserve version numbers or baseline a nonempty trial schema. Explicit backup/conversion preserves current ids/HMAC uniqueness/roles; collisions abort safely. Unbound tickets expire; old JWTs cannot be upgraded from claims. No history deletion.
3. Co-locate auth/business state; implement the current directory/transaction seam and reviewed profile. Runtime has no DDL/admin/memory fallback. Preserve #81 private signing and #61 PNG.
4. Preserve #88's completed common-session integration. After D1–D4 acceptance, C1 adapts the same S1 attempt/session/lease to the accepted fields and installation echo; do not recreate or remove another already-removed trial store. Gate real phone P0 on the entire accepted profile; legacy `/schedules` success cannot enable unbound phone business access.
5. Cloud remains Render+Supabase. Fully cloud-independent LAN keeps explicitly selected isolated existing anonymous demonstration auth, local PG/private files, different key/audience/data. No phone outage automatically selects it; it is not verified phone login. Full LAN acceptance remains required.
6. Review config/backup/conversion; validate actual migrated startup/packaged HTTP/rollback before cloud actions. Rollback cannot restore unbound P0 phone access, public signing or unfenced writes. Draft publication itself authorizes no deployment.

## 9. Required implementation counterexamples — NOT RUN here

| ID | Required proof / owners |
|---|---|
| PB01 | C1/S1: same attempt verify→register; foreign/expired/consumed capabilities and wrong echo never persist |
| PB02 | S2/C1: cross-attempt proof replay, provider timeout, lease loss/crash/late worker; no DB lock across supplier call |
| PB03 | S2: two tickets/eight connections yield one fixed-role account; real SQL/COMMIT/signing failures preserve documented boundaries |
| PB04 | S2: signed MVC/TCP wrong issuer/audience/type/version/account/role/install/session; crypto/time rejection before DB; no diagnostics leak |
| PB05 | S2/S1: DB outage→retryable503→same-token recovery; deletion/revocation→401; old401 cannot clear new login |
| PB06 | C1/E/S2: real phone→pending pairing→Elder consent→private photo→plan confirm/edit/deactivate; denied before consent/after revoke |
| PB07 | S1/S2/C2: same-install re-login/process restart recovers original key/payload; other install409; old receipt cannot replace newer cache/read |
| PB08 | S2: real account/session/relation revoke versus write/receipt races serialize on same primary connection |
| PB09 | S1/C1: actual Keystore/files/Room, logout failure, process loss, accounts/environments; no parallel session truth |
| PB10 | S2: real forward migration preserves ids/roles/HMAC/business; collisions/manual/partial schema fail safely; runtime cannot DDL |
| PB11 | All feature owners: real two-device cloud and isolated cloud-independent LAN; restart retains data; no implicit migration |

See [review-only examples](proposals/phone-identity-binding-v1.examples.json), [Proposed ADR0010](../adr/0010-phone-accounts-and-installation-binding.md) and [API draft](../API_CONTRACT.md). Suggested later slices: wire/profile+migration; actor/directory/transaction integration; C1/S1 session consumption; feature/device acceptance. None is implemented or signed by this proposal.

## References

- [RFC6750](https://www.rfc-editor.org/rfc/rfc6750): Bearer possession and protected transport/storage; this custom phone flow is not claimed as an OAuth authorization server.
- [RFC8725 §§3.8–3.12](https://www.rfc-editor.org/rfc/rfc8725): issuer/audience validation, explicit typing and mutually exclusive profiles.
- [RFC7519 §§4.1.3–4.1.7](https://www.rfc-editor.org/rfc/rfc7519): audience, NumericDate and JWT id. Server session persistence is this application's recommendation, not an RFC-imposed schema.


## 10. SDK SMS path and bounded verification handoff

Both one-tap and SMS continue through the existing Alibaba Fusion SDK: token acquisition, SDK result, then Server `/fusion/verify`; a new account continues through `/phone/register`. The SDK owns SMS sending/resend/risk challenges. This proposal does not add manual send-code/verify-code or password/reset endpoints. For SMS, preserve the current `expectedPhone` comparison after supplier PASS as described above. A valid signed trial JWT still cannot itself authorize P0 pairing/photos/plans.

The current #88 `FusionAuthProvider` does not set explicit connect/read/runtime options at its SDK call site. That source finding is **not a measured root cause** for the recorded 20-second client timeout, and increasing Android wait to45seconds is not proof of recovery. The following transport work is S2-owned, separate from accepting every D1 field: record sanitized stage/result category and monotonic elapsed time; bound upstream connect/total work and retries; test nonresponse/late PASS/cancellation and restoration without retaining proof, phone or credentials. The5/20/30-second recommendations in section3 remain proposed budgets until reviewed. No real-provider call or latency result is claimed by this documentation PR.

403/404 examples below are review-only client expectations. An own-account wrong-role403 retains the common session and original journal evidence. An inaccessible/unrelated/revoked target404 clears only that target presentation/access; it retains the common session and original operation evidence. Neither grants target existence information or permits a new write. A401 may invalidate only the exact captured matching lease;503/429 retains recovery state. C1 and S1 must confirm observable presentation against these S2 recommendations before wire/adapter freeze.

## 11. Delivery order and configuration responsibility

All rows are pending implementation/acceptance unless explicitly described as existing code. S2 is the accountable owner of #118; role collaboration here does not appoint a reviewer as already approving.

| Step / owner | Concrete output | Dependency and evidence |
|---|---|---|
| Design / S2 with C1 and S1 | Recorded D1–D4 decisions, remaining supplier proof-retention bound, `expectedPhone` compatibility and403/404 handling | This Ready request starts design review; ADR0010 remains Proposed. Accept/reject/modify each item with links; do not infer consent from silence |
| Existing-provider diagnostics / S2 | Bounded transport and sanitized category/elapsed-time evidence, local delayed-provider failures and independent live verification evidence when available | Do not wait for a new manual SMS design; retain existing SDK flow and #94/#97 safety. A local fixture does not prove supplier recovery |
| Bound persistence and identity / S2, S1 and non-author Server/security reviewer | Explicit forward-migration implementation task/PR; stable account, independent installation/session, current actor and same-transaction receipt checks | Only after affected D1–D4 decisions. Use then-current main history, no first-use DDL, automatic anonymous merge or guessed migration number |
| Client adaptation / C1 with S1 | Consume accepted wire using the already-shared attempt/session/lease and original operation identity | Same reviewed Server profile, strict late-response/401 fencing; no anonymous fallback |
| Deployment/configuration / S2 accountable, lead controls service access | Exact HTTPS URL + Server commit + enabled route list + approved configuration owner/rollback | #115 is an anonymous memory/local-photo test setup; its endpoint/health is not phone deployment or a secret-entry page. Current phone console access and enabled routes are **not verified** |
| Team package / S1 with C1/S2 | Existing `assemblePhoneTest` checks; same HTTPS service/policy, actual applicationId, registered signing-certificate MD5, APK source commit/SHA-256 | Preserve #88 build entry rather than inventing another; different local Debug certificates do not automatically satisfy supplier registration |
| Joint acceptance / S2 aggregates, C1 device, S1 session/build | One-tap and SDK SMS, role/account restoration, consent-based pairing/data, retry/reinstall/account-switch evidence | Record supplier, Server, business and device results separately; keep unpassed items open |

Following the official [Render environment-variable procedure](https://render.com/docs/configure-environment-variables), for a future approved Render service, the service owner provides the actual service's Render Dashboard **Environment** settings and grants appropriate team access; secrets are entered there by an authorized owner, never on the public `/health` URL. This is the platform procedure, not a claim that an existing service or teammate permission was verified. Publish only variable names/status and commit/route evidence. Keep `ALIBABA_CLOUD_ACCESS_KEY_ID`, `ALIBABA_CLOUD_ACCESS_KEY_SECRET`, stable `PHONE_AUTH_HASH_KEY`, `JWT_SECRET` and database credentials private. Reuse the current `PHONE_AUTH_*` names from #88's handoff where applicable; the final unified DataSource/profile names follow implementation review. Testers install the checked APK without entering AccessKey. No deployment, configuration write, secret injection, billing or real SMS is authorized/performed by this proposal update.
