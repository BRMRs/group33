# ADR-0010: Separate verified phone accounts from installation/session scope

## Status

**Proposed, 2026-10-31. Not Accepted; no implementation or human sign-off.** Refs #49 / #10 / #12 / #16. D1–D4 in the [binding proposal](../contracts/PHONE_IDENTITY_BINDING_PROPOSAL.md) need C1/S1/Server confirmation. On acceptance this would amend identity assumptions in ADR0001/0006 while preserving consent, active relationships and independent fallback. Until then those ADRs retain their accepted status; this does not silently supersede them.

## Context

The lead asks S2 for exact verification/ticket/JWT/current-installation/P0 actor semantics. C1 #88 / S2 #94 validate phone accounts, but P0 still resolves anonymous installation accounts. S1 confirms independent installation, stable Server account, original attempts/leases and no anonymous-history migration. Relabelling a phone account as an anonymous installation does not satisfy that boundary.

## Proposed decision

- Stable Server userId/fixed role identify account; S1 installationId scopes installation, not userId/hardware identity/proof of possession.
- Bind Server attempt capability and single-use registration ticket to original installation/profile; typed phone JWT binds account, installation and persisted session id. Exact fields/errors remain recommendations in the linked proposal.
- Check current account/install/session; protected effects+receipts recheck authority on their same-primary transaction. Preserve original operation identity/install binding and active care relationship; sign-in alone grants no cross-user permission.
- C1 owns SDK/login and consumes S1's one private session/attempt/lease. Trial store is not a parallel production truth. Local logout retains installation/history and fences late responses, but does not revoke Server JWT.
- No automatic role/account/anonymous-history merging. New installation restores authorized account data through reads, not another device's journal.
- LAN demonstration remains independent local authentication/PG/private files with separate key/audience/data; no automatic provider-outage bypass or fake verified phone login.

## Alternatives

Accept any signed phone JWT in anonymous resolver: rejected; current installation/session and downstream directory remain unresolved. Use userId as installationId or overwrite global installation ownership: rejected; destroys account/device scope. Claim UUID/JWT is hardware attestation: rejected. Freeze Shared wire or upgrade old trial JWT/tickets before review: rejected.

## Consequences and acceptance

Requires coordinated DTO/adapter changes, reviewed forward migrations preserving accounts, common current-principal directory and transactional checks. PB01–PB11 define PG/HTTP/device/race/fallback evidence still to implement/run. Cloud/manual-schema conversion stays separate. No new password/reset, refresh token, device attestation, global logout, care relationship or notification protocol scope. Preserve private signing/PNG fixes in integration/rollback.

Source: [S1 binding request](https://github.com/BRMRs/group33/issues/49#issuecomment-6526229077), [core order](https://github.com/BRMRs/group33/issues/16#issuecomment-6526076873), [ADR0001](0001-explicit-pairing-authorization.md), [ADR0006](0006-cloud-independent-demonstration-fallback.md).
