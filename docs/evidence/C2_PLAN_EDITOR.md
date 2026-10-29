# C2 editor component — local development

Refs #51. Role: C2 / Iris-wly. Branch: feature/51-c2-plan-editor.

The lead approved a scrollable local form component in issue #51 (comment
6494008780). This branch explicitly includes PR #43 at 724e8fe and PR #58 at
1cb156f, with main 999e182. These are multiple unmerged dependencies, not a
main-ready isolated PR. Publish only as a dependency-explicit Draft, not an independent completed delivery;
rebase/reconcile the dependency scope after upstream integration or record an
explicit publishing arrangement. No production fake or navigation is registered.

PlanEditorView binds the existing form/save ViewModels. The trusted caller supplies
scope-bound models, selected Elder, photo metadata, matching decoded bitmap and
observed time zones. Invalidating access or changing context requires clear/new
models. Lifecycle destruction cancels bindings/operations. Input and image are
cleared on context removal. Actual decoded content plus explicit user inspection
is required before submitting; metadata or a content handle cannot satisfy this.
The host still owns authorization, photo decoding and secure content disposal.

The form provides persistent labels, field errors, multiline HH:mm input,
inclusive date fields, time-zone comparison and an example from the first time
and start date. Different/unknown zones require acknowledgement. Inputs remain
for validation/rejection. Rejected state first focuses review; a subsequent Save
uses the ViewModel guard against replaying the unchanged rejected request.
Unknown saves only reconcile; confirmed/cache-pending only refreshes the original
operation. Input fields lock during unresolved or confirmed saves.

Debug host (2026-10-29): run Debug, choose Caregiver, then Open C2 form test
(Debug). Select a response scenario and press Reset to apply. Inspect the
synthetic image and check its box, edit fields, then scroll down to Save.
Scenarios: saved, cache pending, unknown, not submitted, rejected. Query/refresh
resolves the original operation. Responses are in memory; no login, server save
or Elder delivery. Leaving/rotation resets the fixture. Activity is non-exported.
Release contains only a no-op entry hook, no test Activity, fixture or resources.
Existing debug cleartext-development configuration is preserved.

Verification on 2026-10-29:
- gate1Check test :android-app:assembleRelease :android-app:assembleDebugAndroidTest passed.
- PlanEditorViewTest: 3 passed on emulator-5554 and Xiaomi Mi Pad 4 Plus
  (Android 8.1): entry opening/binding and simulated save, unbound submission,
  sensitive-input/image cleanup, persistent labels and minimum heights.
- Release APK DEX/resources and merged manifest exclude the debug Activity,
  fixture and c2_debug_open; Debug contains them.
- English emulator screenshot inspected; system-bar/keyboard insets added.

Remaining: interactive coverage for all response scenarios and context switches,
both locales at 320/360dp and 200% font, TalkBack and actual-photo tests, and real
C1/S1 host integration. Synthetic fixtures do not establish actual-photo or
end-to-end acceptance. See C2_PLAN_PRESENTATION.md for the pending matrix.

User feedback (2026-10-29): the C2 owner reported that the Debug page looked
okay and authorized the next delivery step. This is a basic trial report, not
a claim that every scenario, locale, TalkBack or production flow was accepted.
