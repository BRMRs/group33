# D1 clarification: SDK grant refresh within the original authentication attempt

## Recorded S1/lead decision — 2026-11-17

Authority: [BRMRs on #118](https://github.com/BRMRs/group33/issues/118#issuecomment-6940293073), reviewing #96 `d4afbdb62cd707d8063bf3336cfc03575c785b81`. D1 and D4 are **accepted with modifications by S1/lead**; D2 and D3 are **accepted by S1/lead**. C1's prior consumer recommendations are retained, not upgraded to full approval. Independent non-author Server/security approval is still pending; BRMRs contributed integration commits and cannot provide that independent approval. No agent signs for a reviewer. These recorded decisions remove the former wait for S1/lead selection, not the implementation/configuration gates.

- D1: Start→same-attempt Refresh→proof→verify→optional register retains installation/profile/private capability and fixed900-second deadline. Strict Start/Refresh forms; unknown/empty capability never falls back to Start. expectedPhone is checked only after PASS. Use #97's delivered **15-second internal total call budget**, not the former proposed5/20/30-second values. Work lease is shorter than remaining attempt time; an unknown supplier result closes the attempt and requires an explicit new scene, with no automatic resend. No consumed fingerprint deletion until supplier proof validity/association evidence supports a reviewed retention bound.
- D2: Stable Server account/fixed role, installation and persisted session/jti are separate. Validate alg/typ/iss/aud/authVersion/installationId/jti/time/role before current account/install/session reads. UUID is scope, not device proof; no automatic other-install logout.
- D3: Current actor/relationship, effects and original receipt are checked in one primary transaction. Same-install re-login can recover original command identity; another installation may read authorized current data but cannot take over the original journal/outbox and replay returns409. No anonymous-history merge or authority merely from login.
- D4: Matching-current-generation401 clears current authentication only, preserving command evidence.403 ROLE_NOT_ALLOWED preserves common session/account home, terminates only that restricted operation and never changes role.404 RESOURCE_NOT_ACCESSIBLE clears/locks that target's photo/plan/event presentation and returns to Elder selection while preserving session and operation evidence, without resource-existence disclosure or new keys.503/429 preserve recovery. Forward migration only; LAN has separate audience/key/database/environment and never imports cloud pending/account/proof.

ApplicationId is confirmed as com.group33.medicationreminder; reuse assemblePhoneTest and require same phone/business HTTPS origin, actual HTTPS policy URL and registered signing MD5. The anonymous Render preview is not a phone-enabled service. No verified phone-enabled HTTPS service/policy/registered MD5 currently exists in the recorded evidence. S2/XinyanXue is configuration accountable owner; lead controls access; secrets enter controlled Environment only. S1 builds the controlled package after configuration, C1 performs real one-tap/SMS/pairing/business acceptance. Provider proof evidence, actual configuration and independent security approval remain rollout gates.


**S1/lead D1 accepted with modifications; independent Server/security approval and implementation pending.** Refs #96 / #118, C1 review of
`9aac7ecbd884f765da748327c4dbe4dd7943b603` on2026-11-14. This answers the refresh
question; it does not record C1/S1/Server acceptance. Existing #88 currently calls
`client.grant()` initially and every four minutes, keeping only its token for the
SDK refresh callback. That unchanged trial adapter cannot consume the proposed
attempt binding merely by replacing a token string.

## One attempt, multiple SDK grants, one original deadline

Recommendation: SDK grant refresh **reuses** the original Server attempt,
installation and Server-selected profile. It never starts another S1 authentication
attempt, rotates its `authenticationAttemptToken`, extends its deadline, changes
account/role or creates a business operation. A fresh login after cancellation,
expiry or an uncertain outcome deliberately starts a new local and Server attempt
and a new SDK scene; the old scene cannot be attached to it.

Two mutually exclusive proposed bodies on the existing `POST /fusion/token`:

| Operation | Exact body | Result |
|---|---|---|
| Start | `{installationId}` | New private attempt capability and first SDK grant |
| Refresh | `{installationId, authenticationAttemptToken}` | New SDK grant associated with that same pending attempt; echo the same capability |

These are incompatible **review-only** trial changes. Unknown fields or a supplied
null/empty capability fail validation; they must not fall back to Start. A public
installation UUID cannot refresh or recover an attempt. The Server selects the
original profile/scheme from its persisted attempt, not request input. Both
responses retain `fusionAuthToken`, `schemeCode`, `expiresInSeconds` and
`authenticationAttemptToken`; the latter stays private to the adapter and is never
given to the SDK or logs. A refresh response with another capability/scheme is
rejected locally rather than replacing the attempt.

Define original creation `T0` and fixed deadline `D = T0 + 900s`, bounded by any
shorter verified provider limit. At Server time `now`, reported SDK grant lifetime
is at most `min(verified supplier grant lifetime remaining, D - now)`; no grant is
issued or returned as usable at/after D. Start may report900seconds only when the
actual supplier grant supports it. Refresh does not reset900seconds. S1/C1 keep a
conservative local monotonic deadline anchored to the original request, clamp it
with received remaining lifetime, and never increase it from a later response.
Server time/state remains authoritative even if the local clock estimate differs.
No new absolute timestamp or client-selected deadline field is proposed here.

The endpoint always resolves the supplied private capability to its original
installation/profile and current state. Persist only its hash. Never infer a
binding from a client claim or from the latest token held by a global singleton.
If supplier proof cannot be associated with a particular grant, this design does
not claim grant-level attestation: supplier PASS, original capability binding and
the separately reviewed cross-attempt proof-fingerprint claim are all required.
Supplier proof-retention/association evidence remains an explicit rollout gate.

## Refresh versus verify: serialized state, no external call under a DB lock

A short transaction checks/locks the attempt, claims one bounded work lease with
kind `GRANT` or `VERIFY`, commits, then performs the supplier call outside the
transaction. Completion locks/rechecks the same attempt, lease generation,
installation/profile, deadline and pending status before recording any result.
The logical attempt proposal therefore also needs work kind/generation and a
bounded grant-issuance state; no executable schema or migration number is added.

| Current state | Refresh | Verify |
|---|---|---|
| PENDING, before D, no live work | Claim GRANT lease | Claim VERIFY lease and original proof fingerprint |
| GRANT or VERIFY lease live |409 OPERATION_IN_PROGRESS |409 OPERATION_IN_PROGRESS; do not send proof upstream |
| Expired/closed/consumed or foreign installation/profile | Generic401 PHONE_VERIFICATION_FAILED | Same generic401; no new outcome |

Successful GRANT completion returns to PENDING, retaining the original capability,
profile and D. Previously issued unexpired grants still belong to this attempt;
a token-string refresh alone does not invalidate an already obtained proof or
retarget verification. Exactly one VERIFY may produce a session or bound
registration ticket and consume the attempt. A late GRANT after consumption,
lease loss or expiry cannot reopen it or return an authorized result. A verifier
cannot run concurrently with a claimed GRANT lease.

The grant's bounded upstream/caller/lease budgets need Server review and must fit
the original remaining attempt lifetime. No SDK resend/automatic upstream retry
is introduced. If grant issuance becomes uncertain, close the attempt and require
a fresh explicit authentication scene; do not reopen it after lease expiry or
silently retry an unknown supplier call. A definite pre-call409 can wait within D
using the original identity; once a proof call may have run, the existing uncertain
proof/fingerprint rule applies. Closing an old unknown attempt cannot revoke an
already committed result; captured local completion fencing must discard it.

## Client ownership and callback sequence

1. C1 obtains original S1 attempt A for environment/installation I, then calls
   Start. Capture `(A, I, profile, capability C, D, sceneGeneration)` in that scene.
   Verify the scene is still current before publishing the initial SDK token.
2. An SDK refresh callback reads only the last usable SDK token for that scene.
   A single-flight refresh calls Refresh with **C and I**, not bodyless `grant()`.
   Capture a local refresh generation; check A/scene/current lifecycle and D before
   sending, after receiving, and in the SDK callback before using the token. A
   stale response cannot update another scene's token reference or extend D.
3. On supplier proof, stop scheduling refresh. Capture the same C/I/profile for
   Verify and optional `expectedPhone`. Do not select a capability from whichever
   refresh or login happened most recently. A live grant lease may return the
   explicit pre-provider409 above; unknown verification is never blindly replayed.
4. An existing account completes only original A after the signed installation
   echo matches I. A new account stores the bound registration ticket with A/I;
   Register uses that ticket/I and the existing fixed-role rules, not a refreshed
   SDK token. A ticket issued before D retains its separate300-second expiry;
   expiry of the consumed attempt does not extend or shorten that ticket. After entering registration, the client stops SDK refresh and uses the ticket stage/deadline with the same captured A/I; it must not clear a still-valid ticket solely because the earlier SDK-attempt deadline passes.
5. Cancel, account/environment switch, logout, scene close and a new authentication
   fence old A, clear its SDK-token/ticket references and cancel its refresh job.
   Non-cancellable late network work is still discarded using the captured
   identity/generation. It cannot persist a session, restore the old UI, clear a
   new account, or give the new scene an old capability. There is no proposed
   Server cancel endpoint; local cancellation cannot retract committed sessions.

A transient refresh failure may retain the previous SDK token **only** while the
same scene/attempt is current and both token and original D remain valid, with no
unknown/closed Server attempt. It never extends validity. Expiry/unknown outcome during SDK grant/verification work
requires a deliberate new scene; it must not call Start behind the old SDK callback.
The existing four-minute cadence and ten-minute scene wait in #88 are historical
implementation settings, not accepted refresh/deadline or supplier guarantees.

## Keep the clocks distinct

| Clock | Meaning and remaining decision |
|---|---|
| Original attempt900s | Fixed original Server deadline, never renewed by SDK refresh; still proposed |
| SDK grant TTL | Supplier/configuration fact, capped by original remaining attempt time; not proof lifetime |
| Supplier proof validity/replay retention | Requires supplier evidence; do not derive from900s or a token refresh |
| Work lease and upstream bounds | Server concurrency/fencing only; proposed5/20/30 verification values are separate from #97's delivered internal15s bound |
| Registration ticket300s | Starts when that single-use bound ticket is committed, separate from SDK token and consumed attempt |
| Signed phone-session lifetime | Existing configured auth lifetime after completion; SDK grant refresh never refreshes this JWT |

## Required acceptance cases — future implementation, NOT RUN here

| Case | Observable result / owners |
|---|---|
| RF01 initial→refresh→proof→verify existing account | Same A/I/C/profile and D throughout; refreshed SDK token is not the capability; only original A completes / C1,S1,S2 |
| RF02 initial→refresh→proof→ticket→register | Ticket binds original A/I; consumed attempt cannot refresh; ticket300s and account fixed-role behavior remain independent / C1,S2 |
| RF03 repeated refresh near/exactly D | Remaining lifetime never grows; at D no grant/verify, no implicit new attempt; local monotonic estimate never renews / C1,S1,S2 |
| RF04 refresh races cancel/account or environment switch | Old response/callback discarded before SDK/session/UI mutation; new attempt's token/ticket untouched / C1,S1 |
| RF05 two refreshes/out-of-order completion | Single-flight locally, current work generation on Server; stale completion cannot overwrite or reopen / C1,S2 |
| RF06 refresh races proof/verify consumption | One current work lease, explicit409 before supplier call; no parallel issue/verify or duplicate session/ticket; late grant rejected / S2,C1 |
| RF07 foreign capability/install/profile or bodyless retry | No refresh, new attempt or information leak; wrong response capability/scheme rejected locally / S2,C1 |
| RF08 grant timeout/lease loss/process crash | Unknown work closes safely, late grant cannot return usable state; explicit fresh scene, no hidden resend / S2,C1,S1 |
| RF09 old grant proof and cross-attempt replay | A still-valid old grant remains within its original attempt; one retained fingerprint claim prevents a second outcome under another attempt, subject to sourced supplier validity/association limits / S2 |

RF01–RF09 refine PB01/PB02/PB09; they are not passing runtime tests in this
proposal-only PR. Preserve #88 shared-session/expectedPhone and #94/#97 guards.
No production endpoint, DTO, SQL, SDK adapter, provider call, SMS or deployment is
changed here. D1 remains PENDING until C1/S1 and independent Server/security review
record accept/modify/defer on the revised head.

中文：SDK刷新复用原认证请求及其私有凭证、安装和固定截止，不另开登录、不续期；
验证和注册始终绑定原请求。取消/切号/切环境后的旧刷新响应必须丢弃。此文给出待审
流程及未来反例，没有代替任何角色接受协议，也没有宣称供应商或真实手机验收通过。
