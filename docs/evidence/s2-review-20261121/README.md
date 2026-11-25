# S2 review evidence preserved on 2026-11-21

This packet preserves the evidence behind the user's [PR119 comment](https://github.com/BRMRs/group33/pull/119#issuecomment-7062234749) and [PR37 comment](https://github.com/BRMRs/group33/pull/37#issuecomment-7062245511). It is a review record, not a formal approval or whole-project acceptance.

## PR37 local execution

Tested head: `c45c856a1bacdcf5e55ef5e0c98713d3a7a3da6e`. Four original JUnit XML files preserve Debug/Release S1PolicyTest33 + CommandRecoveryPolicyTest5 each:76 variant report instances, zero failures/errors/skips. Both tasks executed. The retained transcript is path-redacted with trailing whitespace normalized; it reports44 seconds,49 executed/14 cached build tasks. Sources match the named head and the six policy/test files match the prior reviewed version. See [CI44874221395](https://github.com/BRMRs/group33/actions/runs/44874221395).

## PR119 CI evidence

Tested head: `1308bc59f18305dd6fe2083679ea001c858e0bf9`. Selected files are unchanged copies from [API35/PG CI44874395869](https://github.com/BRMRs/group33/actions/runs/44874395869), artifact14695880893; the ZIP digest is in packet.json. Ordinary [CI44874395867](https://github.com/BRMRs/group33/actions/runs/44874395867) also succeeded. The packet keeps13 original boundaries,8 Room/Worker cases,1 deterministic two-process case and1 automatic recovery case; killed preparation is not counted as passing.

Automatic recovery retains the work UUID, original action/payload/deadline, real clock and native attempt1→2/3→4. ACK occurs after105,038ms with zero manual recovery calls. Android transport is injected; the separate real packaged Server/PostgreSQL flow passed62 assertions, maintained2/2/2 occurrence/event/operation rows and recovered original receipts across a changed JVM. These are two scoped flows, not a continuous Android-to-real-Server trace. PostgreSQL used isolated existing V1–V10 fixture SQL, not managed cloud rollout.

## Preservation and scope

The user explicitly requested uploading the local review evidence. This narrow packet therefore retains original generated XML and test transcripts for review, outside normal build directories. No APK, build cache, private fixture, database backup, key, token, real phone or medical data is included. AVD/bootstrap diagnostic logs add no acceptance evidence and are omitted. PR119 originals already existed as expiring Actions artifacts; these selected files provide durable review access. Local checkout snapshots, publication scripts and connector failures are local operational material and are not project implementation.

`SHA256SUMS` covers the retained files. Run `python3 scripts/verify_s2_review_packet.py` from the repository root. The verifier checks both named inputs, raw completion counts, native retry/identity/payload evidence, real backend assertions and cleanup. It never connects to a service or invokes a supplier.

The branch audit covers48 local feature/fix/test refs:8 identical,14 contained by newer remote tips,26 mapped to closed previously published PRs whose branch refs no longer exist. Closed does not mean merged; no branch is revived automatically. This audit found no unpublished feature tip on those refs.
