# Preserve original pairing command identity when a receipt is invalid

Refs #10/#112. S2-assisted review on 2026-11-23, original input
`3b38777ae5340170bbc80f56dca9be761a89e6a9`.

## Reproduced problem

The pairing form checked only scope on `resume` and did not check the immediate
response operation ID. A same-scope receipt for another operation, command kind or
cancellation target could clear the original unresolved state. An unrelated immediate
confirmed result could also clear the entered code and display success. The next user
action could then allocate a new command while the original result remained unknown.

Five external deterministic JVM probes ran on the unmodified original input:
four identity counterexamples failed and the valid original-recovery control passed.
The combined Debug run had 47 cases and four failures; Release was not run in that
failing invocation. This is a component/Repository-boundary counterexample, not a
claim that a real Server returned a foreign receipt or an unauthorized write occurred.

## Repair

- Match an immediate result to the exact journal-issued command before treating it
  as confirmed, rejected or not submitted. Invalid responses keep the original ID
  unknown; they do not adopt the foreign response ID or allocate a new command.
- Retain the unresolved issued command's scope/kind/target until its exact recovery,
  or compare a restored journal record against its captured original metadata.
- Match recovery operation ID/scope/kind/target and optional pairing-result reference;
  confirmed recovery requires cache state and cannot downgrade an already confirmed
  cache-pending record. Invalid recovery leaves the original command blocked.
- Retain the captured session-generation check after suspension. Context changes
  clear pending metadata and late results cannot restore the old form.

`S2PairingReceiptReviewTest` retains the five original probes and adds four regression
cases for malformed references/cache state, confirmed downgrade, valid cancellation
and late recovery across a generation change. Original author tests are unchanged.

## Validation and remaining boundaries

Run `gate1Check test :android-app:lintDebug :android-app:assembleDebug
:android-app:assembleRelease :server:schemaMigrationDist` with JDK17/SDK35, plus
the focused `PairingWorkflowViewModelTest`, `ChainViewModelTest`,
`CaregiverEditorModelsTest` and `S2PairingReceiptReviewTest` in both variants.
Exact completed counts and task modes are recorded in the fixing PR and its CI.

This repair is stacked on #112. No Server/Shared/API/schema/provider/permission
change, new retry key, automatic write, relationship grant or event/history change
is introduced. Original live HTTP/Room evidence belongs to its original tested
commit; this fix's JVM tests do not replace current device, TalkBack, phone-service
or complete #10 acceptance. C1/S1 review and lead integration remain required.


## S1 observation-path correction — 2026-11-25

A two-axis incremental review on `4bf6b5ee8f59875c5271144bd0ff02b3f6d9f659`
found a remaining consumer path: `observeCommands` replaced the captured records
and cleared the unresolved operation using only operation ID and terminal state.
It bypassed the identity/reference/cache validation used by explicit resume.

An external deterministic Debug probe on that unmodified head executed15 methods:
the original nine plus the valid terminal observation control passed; five new
counterexamples failed (wrong kind, cancellation target, result reference, missing
confirmed cache, and confirmed-cache-pending downgrade). Each failed at the
needs-recovery assertion. This does not claim an actual Server foreign receipt,
unauthorized write, or that later duplicate-write assertions were reached.

The observation path now validates against the captured issued command or prior
record with the same `matchesRecovery` policy before replacing state. Invalid or
missing observations retain original records still requiring recovery; only an
accepted exact terminal observation releases the unresolved command. Session and
generation changes still clear the captured state. The six probe methods are
preserved in `S2PairingReceiptReviewTest`, with one additional empty-journal then
valid-terminal control. The original nine methods remain unchanged.

### Standards

Zero blocking documented-standard violations. The correction keeps Repository
boundaries, reuses the existing validation, adds no dispatch/retry or unrelated
abstraction, and uses controlled clocks/dispatchers and fictional data.

### Spec

One P2 before correction: observation could bypass original identity and
confirmed-state protection. Incremental source review after correction finds that
path closed; the original counterexamples and valid completion remain covered.
These agent-assisted axes are not independent teammate approval or Gate1 signatures.

Validation on the corrected source: `gate1Check test :android-app:lintDebug
:android-app:assembleDebug :android-app:assembleRelease --console=plain --no-daemon`
passed. Actual executed full reports: Server628, Shared18, Android Debug178 and
Release177, all zero failures/errors/skips. The final review test class has16 cases;
PairingWorkflowViewModelTest has19. Full and focused/variant counts overlap and are
not distinct new tests. Local policy, artifact checksums and whitespace are checked
again with the final evidence/manifest revision. No new real HTTP/Room/device or
supplier acceptance is claimed. The lead contributed this correction, so C1/another
qualified non-author must review the final pushed head before integration.
