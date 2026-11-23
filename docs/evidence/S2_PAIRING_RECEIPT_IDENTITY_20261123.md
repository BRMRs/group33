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
