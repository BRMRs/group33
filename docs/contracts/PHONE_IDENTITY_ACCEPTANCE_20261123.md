# Phone identity joint design acceptance

Recorded by S2/XinyanXue on 2026-11-23, Refs #118/#96/#49/#10/#12/#16.
This ledger records decisions actually submitted by the reviewers. It adds no
reviewer signature and makes no runtime, provider or device acceptance claim.

## Integration update — 2026-11-25

BRMRs records this integration update; the original S2 ledger and reviewer
attributions below are unchanged.

The lead squash-merged the original #96 design into main as
[`9e5621393c3d130bc65ec6754d0531c363c9cf9d`](https://github.com/BRMRs/group33/commit/9e5621393c3d130bc65ec6754d0531c363c9cf9d).
The accepted review input below remains `caecd8f`; the squash commit does not create
new reviewer signatures or implement bound authentication. This ledger now records
that completed design integration, while #143/#144/#145 and #147 remain separately
reviewed implementation inputs rather than a complete deployed service.

The dated November17 configuration-absence paragraphs in the design files are
historical snapshots. The [current #118 lead handoff](https://github.com/BRMRs/group33/issues/118#issuecomment-7152263731)
records the selected independent Render service plan and lead operator, plus the
user-confirmed policy subject/contact and review drafts in #146. Those owner inputs
no longer block S2 from preparing the reviewed composition. C1 has also
[reported supplier package/certificate registration](https://github.com/BRMRs/group33/issues/118#issuecomment-7130700068);
S1 still verifies the final team APK certificate and package. A policy draft or
reported registration is not an effective HTTPS policy, deployed phone service,
configuration/migration acceptance or real supplier/device result. The remaining
A–E implementation/fact/deployment deliverables stay with their recorded owners.

## Accepted input and attributable decisions

The accepted design input is #96 commit
`caecd8f8210cae6fc54adf847a65a4078c2bbaf2`, with main
`b5acc05224612bf25797ee71a5cdf1a879b67d91` as an ancestor. The complete
source tree is pinned by the accepted commit.

| Perspective | Reviewer and exact evidence | Decision and limits |
| --- | --- | --- |
| S1/lead, D1-D4 selection | [BRMRs #118 decision](https://github.com/BRMRs/group33/issues/118#issuecomment-6940293073), reviewed `d4afbdb62cd707d8063bf3336cfc03575c785b81` | D1/D4 accepted with modifications; D2/D3 accepted. The final design incorporates the modifications. |
| S1 current input | [BRMRs approval](https://github.com/BRMRs/group33/pull/96#pullrequestreview-6003380363), `caecd8f`, 2026-11-21 | Approved the design record and explicit implementation/configuration gates. BRMRs contributed integration work and is not counted as the independent security reviewer. |
| C1 and recorded temporary non-author Server/security backup | [tianiridescent technical review](https://github.com/BRMRs/group33/pull/96#pullrequestreview-6012322187), `caecd8f`, 2026-11-22 | Current D1-D4 design has no blocker in the named C1/security scopes. Not implementation, migration, supplier or device validation. |
| Formal independent approval at the same input | [tianiridescent approval](https://github.com/BRMRs/group33/pull/96#pullrequestreview-6012341899), `caecd8f`, 2026-11-22 | APPROVED. This replaces the need to rely on the dismissed earlier `1ee26cd` approval. The backup assignment remains the one recorded in #118; it does not expand to #94/#97 or make their implementation approved. |

The design review prerequisite is now **ACCEPTED for D1-D4 at the input above**.
D1/D4 retain S1's recorded modifications. This is a state transition backed by
submitted decisions, not inferred from silence or CI. Integration into main remains
the lead's responsibility; the accepted design does not claim that main already
contains bound phone authentication or that this ledger's new commit was reviewed.

## Accepted implementation boundaries

1. D1: strict Start/same-attempt Refresh/verify/register, original installation and
   private capability/profile, fixed original deadline, serialized short lease and
   no automatic resend of an uncertain proof. Preserve the delivered #97 15-second
   internal dependency budget. No consumed fingerprint cleanup until an authoritative
   provider retention/association bound is reviewed; retaining fingerprints does not
   require guessing such a bound.
2. D2: stable fixed-role account, independent composite account/installation rows,
   persistent session/jti and a typed, issuer/audience/version-bound phone profile.
   Validate crypto, exact profile and claim types/time before current-state reads.
   Installation UUID is a scope label, not proof of device possession.
3. D3: current account/install/session and relationship checks share the primary
   transaction with effects and original receipts. Re-login on the same installation
   preserves business operation identity; another installation cannot take over it.
   No anonymous identity/history merge, global installation ownership, role overwrite
   or authorization granted merely by login.
4. D4: matching-lease 401; operation-local role 403; privacy-preserving target 404;
   existing recovery-preserving 409/429/503; reviewed forward migrations; isolated
   LAN key/audience/data with no automatic fallback or cloud-pending migration.

The exact accepted design and examples remain pinned in [ADR0010](https://github.com/BRMRs/group33/blob/caecd8f8210cae6fc54adf847a65a4078c2bbaf2/docs/adr/0010-phone-accounts-and-installation-binding.md),
[binding design](https://github.com/BRMRs/group33/blob/caecd8f8210cae6fc54adf847a65a4078c2bbaf2/docs/contracts/PHONE_IDENTITY_BINDING_PROPOSAL.md)
and [SDK refresh specification](https://github.com/BRMRs/group33/blob/caecd8f8210cae6fc54adf847a65a4078c2bbaf2/docs/contracts/PHONE_SDK_REFRESH_PROPOSAL.md).
The old files' dated pending-review statements describe the prior review state;
this ledger records the subsequent review outcome. No wire recommendation is
changed by recording acceptance.

## Work that can now proceed

S2 can submit separately reviewable PS1 persistence/attempt/profile and PS2
same-transaction actor/authorization implementation against this design, without
waiting again for the already-completed design review. Each implementation still
needs its own tests, exact-head non-author security/Android review and lead adoption.
Preserve #88 -> #94 -> #97. Do not duplicate the existing common Android session or
dependency budget. C1 adapts only to concrete same-version delivered Server inputs.

Migration versions follow the actual integration order: main currently has dose
V10; index V11 is still on #102. This ledger assigns no phone migration number and
does not permit runtime DDL. A schema prototype or fake is not a deployed migration.

## Remaining rollout gates

- PB01-PB11 and RF01-RF09 remain required implementation/integration scenarios;
  neither the design reviews nor this ledger count as their execution.
- Actual controlled phone-enabled HTTPS service, owner/access, database/route/profile
  configuration, deployment commit, migration/rollback and safe startup evidence.
- Supplier proof validity/association evidence before choosing cleanup bounds or
  claiming a bounded retention/replay guarantee. No guessed TTL from SDK grant lifetime.
- Real policy URL, supplier-registered signing certificate and same-version S1
  `assemblePhoneTest` package, followed by C1 supplier/SMS/role/pairing/business and
  session/device acceptance. The anonymous preview is not a phone-enabled service.

S2 remains accountable for #118 implementation and configuration verification;
the lead controls actual service access. No secret value, cloud change, supplier
call, reviewer approval, main merge or issue closure is performed by this record.
