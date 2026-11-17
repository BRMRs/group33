# ADR-0010: Separate verified phone accounts from installation/session scope

## Recorded S1/lead decision — 2026-11-17

Authority: [BRMRs on #118](https://github.com/BRMRs/group33/issues/118#issuecomment-6940293073), reviewing #96 `d4afbdb62cd707d8063bf3336cfc03575c785b81`. D1 and D4 are **accepted with modifications by S1/lead**; D2 and D3 are **accepted by S1/lead**. C1's prior consumer recommendations are retained, not upgraded to full approval. Independent non-author Server/security approval is still pending; BRMRs contributed integration commits and cannot provide that independent approval. No agent signs for a reviewer. These recorded decisions remove the former wait for S1/lead selection, not the implementation/configuration gates.

- D1: Start→same-attempt Refresh→proof→verify→optional register retains installation/profile/private capability and fixed900-second deadline. Strict Start/Refresh forms; unknown/empty capability never falls back to Start. expectedPhone is checked only after PASS. Use #97's delivered **15-second internal total call budget**, not the former proposed5/20/30-second values. Work lease is shorter than remaining attempt time; an unknown supplier result closes the attempt and requires an explicit new scene, with no automatic resend. No consumed fingerprint deletion until supplier proof validity/association evidence supports a reviewed retention bound.
- D2: Stable Server account/fixed role, installation and persisted session/jti are separate. Validate alg/typ/iss/aud/authVersion/installationId/jti/time/role before current account/install/session reads. UUID is scope, not device proof; no automatic other-install logout.
- D3: Current actor/relationship, effects and original receipt are checked in one primary transaction. Same-install re-login can recover original command identity; another installation may read authorized current data but cannot take over the original journal/outbox and replay returns409. No anonymous-history merge or authority merely from login.
- D4: Matching-current-generation401 clears current authentication only, preserving command evidence.403 ROLE_NOT_ALLOWED preserves common session/account home, terminates only that restricted operation and never changes role.404 RESOURCE_NOT_ACCESSIBLE clears/locks that target's photo/plan/event presentation and returns to Elder selection while preserving session and operation evidence, without resource-existence disclosure or new keys.503/429 preserve recovery. Forward migration only; LAN has separate audience/key/database/environment and never imports cloud pending/account/proof.

ApplicationId is confirmed as com.group33.medicationreminder; reuse assemblePhoneTest and require same phone/business HTTPS origin, actual HTTPS policy URL and registered signing MD5. The anonymous Render preview is not a phone-enabled service. No verified phone-enabled HTTPS service/policy/registered MD5 currently exists in the recorded evidence. S2/XinyanXue is configuration accountable owner; lead controls access; secrets enter controlled Environment only. S1 builds the controlled package after configuration, C1 performs real one-tap/SMS/pairing/business acceptance. Provider proof evidence, actual configuration and independent security approval remain rollout gates.


## Status

**S1/lead decision recorded 2026-11-17; D1/D4 accepted with modifications, D2/D3 accepted. Overall ADR acceptance awaits independent non-author Server/security approval; not implemented.** Refs #118 / #49 / #10 / #12 / #16. See recorded role decisions and remaining independent approval in the [binding proposal](../contracts/PHONE_IDENTITY_BINDING_PROPOSAL.md). On acceptance this would amend identity assumptions in ADR0001/0006 while preserving consent, active relationships and independent fallback. Until then those ADRs retain their accepted status; this does not silently supersede them.

## Context

The lead asks S2 for exact verification/ticket/JWT/current-installation/P0 actor semantics. C1 #88 / S2 #94 validate phone accounts, but P0 still resolves anonymous installation accounts. S1 confirms independent installation, stable Server account, original attempts/leases and no anonymous-history migration. Relabelling a phone account as an anonymous installation does not satisfy that boundary.

## Design updated to recorded S1/lead decision

- Stable Server userId/fixed role identify account; S1 installationId scopes installation, not userId/hardware identity/proof of possession.
- Bind Server attempt capability and single-use registration ticket to original installation/profile; typed phone JWT binds account, installation and persisted session id. Exact fields/errors remain recommendations in the linked proposal.
- Check current account/install/session; protected effects+receipts recheck authority on their same-primary transaction. Preserve original operation identity/install binding and active care relationship; sign-in alone grants no cross-user permission.
- C1 owns SDK/login and consumes S1's one private session/attempt/lease. Current #88 has already removed its parallel trial store; preserve that completed integration. Server binding remains a separate dependency. Local logout retains installation/history and fences late responses, but does not revoke Server JWT.
- No automatic role/account/anonymous-history merging. New installation restores authorized account data through reads, not another device's journal.
- LAN demonstration remains independent local authentication/PG/private files with separate key/audience/data; no automatic provider-outage bypass or fake verified phone login.

## Alternatives

Accept any signed phone JWT in anonymous resolver: rejected; current installation/session and downstream directory remain unresolved. Use userId as installationId or overwrite global installation ownership: rejected; destroys account/device scope. Claim UUID/JWT is hardware attestation: rejected. Freeze Shared wire or upgrade old trial JWT/tickets before review: rejected.

## Consequences and acceptance

Requires coordinated DTO/adapter changes, reviewed forward migrations preserving accounts, common current-principal directory and transactional checks. PB01–PB11 define PG/HTTP/device/race/fallback evidence still to implement/run. Cloud/manual-schema conversion stays separate. No new password/reset, refresh token, device attestation, global logout, care relationship or notification protocol scope. Preserve private signing/PNG fixes in integration/rollback.

Source: [S1 binding request](https://github.com/BRMRs/group33/issues/49#issuecomment-6526229077), [core order](https://github.com/BRMRs/group33/issues/16#issuecomment-6526076873), [ADR0001](0001-explicit-pairing-authorization.md), [ADR0006](0006-cloud-independent-demonstration-fallback.md).

The previous request alone did not accept D1–D4; the attributable S1/lead decision above now records its role acceptance. Preserve existing SDK SMS `expectedPhone` matching after supplier PASS; the binding proposal now records delivery owners, pending configuration evidence and transport diagnosis separately from protocol acceptance.
