# ADR-0001: Require explicit elder-approved pairing for caregiver access

## Status

Accepted for P0.

## Context

Anonymous installation authentication identifies a device and its fixed role, but it must not authorize a caregiver to access another person's data. The product needs a simple pairing flow for an elder and caregiver while preventing silent linking, accidental cross-elder access, and stale access after revocation.

## Decision

Pairing is a two-step consent flow: the elder exposes a temporary, single-use code; the caregiver enters it; the server creates a pending request; and the elder sees the caregiver identity and authorization consequences before explicitly accepting. Only an active care relationship authorizes cross-user access. In P0, one caregiver may relate to multiple elders, while an elder has at most one active caregiver. Either participant may revoke the relationship, after which subsequent caregiver access and changes are rejected.

## Consequences

- Every cross-user operation must check both the authenticated installation subject and an active care relationship.
- Pairing mutations require server confirmation and cannot complete offline.
- Existing elder data becomes visible to the caregiver only after acceptance.
- The implementation needs pending-request, expiry, cancellation, replacement, and revocation states.
- Multiple caregivers per elder, identity merging, and institutional accounts remain outside P0.
