# Phone identity: decisions required before implementation

Status: **PROPOSED / all decisions PENDING**. Refs #96 / #118 / #49 / #10.
This is a review form for the existing proposal, not accepted wire, schema,
implementation, a signature, or permission to configure a production service.
Review the exact current #96 revision and
[PHONE_IDENTITY_BINDING_PROPOSAL.md](PHONE_IDENTITY_BINDING_PROPOSAL.md).

## Existing work to preserve

- #88 uses the shared S1 attempt/private session and preserves SDK SMS
  `expectedPhone`; do not recreate the removed parallel session store.
- #94 checks current phone accounts; #97 `d3208419` adds the internal15-second
  caller budget, four workers/no queue, pinned-SDK options/no automatic resend,
  late-result exclusion and safe retryable503. Their branch CI/failure evidence
  exists; neither is proof of main adoption, real supplier recovery or phone P0.
- #116 dose V10 must actually merge before #102 adopts index V11 on actual main.
  This proposal reserves no phone migration number and rewrites no applied SQL.

## Record one attributable decision per row

For each D1–D4, record **accept / modify / defer**, exact #96 head, reviewer/role,
rationale, required edits and unresolved dependencies. Silence, a CI pass or a
review request never fills this record. C1/S1 and the required independent
Server/security perspective must actually review their affected boundaries.

| Decision | Existing proposal to accept or modify | Required implementation evidence | Status |
|---|---|---|---|
| D1 — original attempt/proof/ticket | Section3: SDK token -> verify -> optional register; original installation and private attempt capability; preserve post-PASS expectedPhone; provider call outside DB transaction, current verification lease/fingerprint, single-use bound ticket. Proposed5/20/30 budgets are not the delivered internal15-second bound. | PB01/PB02: wrong/expired/consumed/foreign attempt and echo, proof replay, late PASS/lease loss/crash, no supplier call under DB lock; real supplier proof-validity bound must be sourced. | PENDING |
| D2 — account/installation/session | Sections4–5: stable account and fixed role; independent installation/current persisted session; typed issuer/audience/profile/version JWT; UUID is a scope label, not device attestation. | PB03–PB05: concurrent tickets converge to one account; signing/commit/current-account failure; invalid crypto/time/profile rejected before DB, current revocation and outage behavior. | PENDING |
| D3 — current business actor/recovery | Section6: common current principal directory and same-primary-transaction authorization/effect/original receipt; consent/current relationship checks; same-install re-login recovers the original command, another install gets409; receipt is not current state. | PB06–PB08: actual phone through consent/photo/plan, original key after restart/re-login, other install denied, account/relation/session revoke versus write/receipt races. | PENDING |
| D4 — errors/adoption/LAN | Sections7–8: lease-scoped401, own-role403, private-target404, existing409/503/429 recovery; forward migrations and explicit namespaces; cloud-independent LAN authentication reviewed separately. | PB09–PB11: Keystore/Room/logout/process loss and account/environment changes; real upgrade/collision/partial-schema safety; actual cloud and separate two-device LAN path. | PENDING |

The PB identifiers describe **future required tests**, not passing tests supplied
by this documentation update. Existing local transport tests are separate proof.
ADR0010 remains Proposed / Not Accepted until the recorded process accepts it.

## Configuration evidence needed for real joint testing

The lead/service owner should supply the following **non-secret facts** in #118.
No tester should paste AccessKey, provider proof, registration ticket, phone,
JWT/signing/hash keys, service-role token or database password into GitHub.

| Needed fact | Current status / accountable input |
|---|---|
| Actual phone-enabled HTTPS service, platform/project, current Server commit, enabled auth and business routes | Not verified; lead identifies the service and owner, S2 verifies route/profile evidence |
| Authorized configuration owner and dashboard/team access, variable names/configured status, health/rollback procedure | Not verified; owner grants suitable access and enters secrets only through the controlled service configuration |
| Provider scheme/profile and proof-validity/replay-retention bound, with supplier documentation or attributable confirmation | Not supplied; SDK token900seconds alone is not proof lifetime |
| S1 team APK source/hash, same service/policy URL, applicationId and supplier-registered signing-certificate MD5 | Follow existing assemblePhoneTest; actual checked package still required |
| Supabase project vckbuhcsheghuggssaxi restored/current status and private database/Storage access | Dashboard not readable from this task; pause email/local PG are not restore evidence |

After affected decisions and service access exist, S2 can implement explicit
forward migrations/current-identity/actor transaction slices; C1 adapts the
already-shared S1 session, S1 supplies the checked package, then the team records
real supplier/SMS/one-tap and consent-based business/device results separately.

## 中文评审回复格式

请分别回复：`D编号；受审head；接受/修改/暂缓；角色/评审人；理由/具体改动；剩余依赖`。
服务接线另给实际HTTPS服务、版本/路由、配置owner及安全入口、供应商proof有效期来源、
团队APK版本/摘要。不要在评论中提交密钥或真实个人数据。本表没有替任何人作决定或签署。
