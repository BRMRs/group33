# Phone identity: decisions required before implementation

> Current design status — 2026-11-23: **D1–D4 jointly accepted at #96 `caecd8f`**, preserving the recorded D1/D4 modifications. See the [attributable acceptance ledger](PHONE_IDENTITY_ACCEPTANCE_20261123.md). The dated pending-review statements retained below describe the prior review state; they do not reinstate a completed design-review prerequisite. Implementation, migration, provider, configuration and device gates remain.

## Recorded S1/lead decision — 2026-11-17

Authority: [BRMRs on #118](https://github.com/BRMRs/group33/issues/118#issuecomment-6940293073), reviewing #96 `d4afbdb62cd707d8063bf3336cfc03575c785b81`. D1 and D4 are **accepted with modifications by S1/lead**; D2 and D3 are **accepted by S1/lead**. C1's prior consumer recommendations are retained, not upgraded to full approval. Independent non-author Server/security approval is still pending; BRMRs contributed integration commits and cannot provide that independent approval. No agent signs for a reviewer. These recorded decisions remove the former wait for S1/lead selection, not the implementation/configuration gates.

- D1: Start→same-attempt Refresh→proof→verify→optional register retains installation/profile/private capability and fixed900-second deadline. Strict Start/Refresh forms; unknown/empty capability never falls back to Start. expectedPhone is checked only after PASS. Use #97's delivered **15-second internal total call budget**, not the former proposed5/20/30-second values. Work lease is shorter than remaining attempt time; an unknown supplier result closes the attempt and requires an explicit new scene, with no automatic resend. No consumed fingerprint deletion until supplier proof validity/association evidence supports a reviewed retention bound.
- D2: Stable Server account/fixed role, installation and persisted session/jti are separate. Validate alg/typ/iss/aud/authVersion/installationId/jti/time/role before current account/install/session reads. UUID is scope, not device proof; no automatic other-install logout.
- D3: Current actor/relationship, effects and original receipt are checked in one primary transaction. Same-install re-login can recover original command identity; another installation may read authorized current data but cannot take over the original journal/outbox and replay returns409. No anonymous-history merge or authority merely from login.
- D4: Matching-current-generation401 clears current authentication only, preserving command evidence.403 ROLE_NOT_ALLOWED preserves common session/account home, terminates only that restricted operation and never changes role.404 RESOURCE_NOT_ACCESSIBLE clears/locks that target's photo/plan/event presentation and returns to Elder selection while preserving session and operation evidence, without resource-existence disclosure or new keys.503/429 preserve recovery. Forward migration only; LAN has separate audience/key/database/environment and never imports cloud pending/account/proof.

ApplicationId is confirmed as com.group33.medicationreminder; reuse assemblePhoneTest and require same phone/business HTTPS origin, actual HTTPS policy URL and registered signing MD5. The anonymous Render preview is not a phone-enabled service. No verified phone-enabled HTTPS service/policy/registered MD5 currently exists in the recorded evidence. S2/XinyanXue is configuration accountable owner; lead controls access; secrets enter controlled Environment only. S1 builds the controlled package after configuration, C1 performs real one-tap/SMS/pairing/business acceptance. Provider proof evidence, actual configuration and independent security approval remain rollout gates.


Historical status before the 2026-11-22 joint conclusion: **S1/lead D1/D4 ACCEPTED WITH MODIFICATIONS; D2/D3 ACCEPTED; independent Server/security was PENDING**. Refs #96 / #118 / #49 / #10.
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
- #116 dose V10 is now on main bbe4997; #102 has separately advanced to index V11 at aabff7d.
  This proposal reserves no phone migration number and rewrites no applied SQL.

## Record one attributable decision per row

For each D1–D4, record **accept / modify / defer**, exact #96 head, reviewer/role,
rationale, required edits and unresolved dependencies. Silence, a CI pass or a
review request never fills this record. C1/S1 and the required independent
Server/security perspective must actually review their affected boundaries.

| Decision | Existing proposal to accept or modify | Required implementation evidence | Status |
|---|---|---|---|
| D1 — original attempt/proof/ticket | Section3 + [SDK refresh clarification](PHONE_SDK_REFRESH_PROPOSAL.md): original token -> same-attempt refresh -> proof -> verify -> optional register; original installation and private attempt capability; preserve post-PASS expectedPhone; provider call outside DB transaction, current verification lease/fingerprint, single-use bound ticket. Proposed5/20/30 budgets are not the delivered internal15-second bound. | PB01/PB02: wrong/expired/consumed/foreign attempt and echo, proof replay, late PASS/lease loss/crash, no supplier call under DB lock; RF01–RF09 add refresh/deadline/cancel/switch/stale-result/lease races; these are future tests, not implemented here. Real supplier proof-validity/association bounds must be sourced. | S1/lead recorded above; independent Server/security PENDING |
| D2 — account/installation/session | Sections4–5: stable account and fixed role; independent installation/current persisted session; typed issuer/audience/profile/version JWT; UUID is a scope label, not device attestation. | PB03–PB05: concurrent tickets converge to one account; signing/commit/current-account failure; invalid crypto/time/profile rejected before DB, current revocation and outage behavior. | S1/lead recorded above; independent Server/security PENDING |
| D3 — current business actor/recovery | Section6: common current principal directory and same-primary-transaction authorization/effect/original receipt; consent/current relationship checks; same-install re-login recovers the original command, another install gets409; receipt is not current state. | PB06–PB08: actual phone through consent/photo/plan, original key after restart/re-login, other install denied, account/relation/session revoke versus write/receipt races. | S1/lead recorded above; independent Server/security PENDING |
| D4 — errors/adoption/LAN | Sections7–8: lease-scoped401, own-role403, private-target404, existing409/503/429 recovery; forward migrations and explicit namespaces; cloud-independent LAN authentication reviewed separately. | PB09–PB11: Keystore/Room/logout/process loss and account/environment changes; real upgrade/collision/partial-schema safety; actual cloud and separate two-device LAN path. | S1/lead recorded above; independent Server/security PENDING |

The PB identifiers describe **future required tests**, not passing tests supplied
by this documentation update. Existing local transport tests are separate proof.
ADR0010 records S1/lead acceptance with modifications but remains short of final joint acceptance until independent approval is recorded.

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
| Supabase project vckbuhcsheghuggssaxi restored/current status and private database/Storage access | User Resume and subsequent read-only recovery verification were recorded in the [approved #16 summary](https://github.com/BRMRs/group33/issues/16#issuecomment-6850967623); phone-enabled deployment, exact Server revision/routes and final managed adoption remain separate unverified gates |

After affected decisions and service access exist, S2 can implement explicit
forward migrations/current-identity/actor transaction slices; C1 adapts the
already-shared S1 session, S1 supplies the checked package, then the team records
real supplier/SMS/one-tap and consent-based business/device results separately.

## 中文评审回复格式

请分别回复：`D编号；受审head；接受/修改/暂缓；角色/评审人；理由/具体改动；剩余依赖`。
服务接线另给实际HTTPS服务、版本/路由、配置owner及安全入口、供应商proof有效期来源、
团队APK版本/摘要。不要在评论中提交密钥或真实个人数据。本表没有替任何人作决定或签署。

## C1 follow-up — 2026-11-16

The2026-11-14 C1 review requested D1 refresh/attempt mapping and supplied D2–D4 consumer recommendations without full approval. [PHONE_SDK_REFRESH_PROPOSAL.md](PHONE_SDK_REFRESH_PROPOSAL.md) now specifies the proposed sequence and future RF01–RF09 cases. This was the historical2026-11-16 status. The2026-11-17 S1/lead decision above supersedes its pending S1 rows; independent Server/security acceptance is still needed.
