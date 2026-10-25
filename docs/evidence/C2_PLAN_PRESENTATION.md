# C2 plan presentation preparation — #51

Scope: local presentation mapping and English/Simplified Chinese resources only.
This does not complete #51 or establish that its full UI scope is Ready.
The lead permits this preparation in [issue #51](https://github.com/BRMRs/group33/issues/51#issuecomment-6397652361).
The baseline is main `64ddd4e`; no unmerged editor or photo implementation is copied.
Follow [UI design decision](../UI_DESIGN_DECISION.md) for the actual page.

## Proposed binding contract

`PlanSavePresentations` consumes the existing `OnlineCommandState` and `CacheState`.
It returns resource IDs and display intent, not Repository calls or permission to save.

| Input | Visible meaning | Display action |
| --- | --- | --- |
| Editing (future editor IDLE) | Review plan before saving | Save |
| Submitting | Waiting; do not submit again | None |
| NOT_SUBMITTED | Not sent; input retained by the host | Retry unsent submission |
| AWAITING_CONFIRMATION | May already be saved | Check original request result |
| REJECTED | Request not accepted; inspect the problem | Review input |
| CONFIRMED + APPLIED | Saved; local data updated | None |
| CONFIRMED + REFRESH_REQUIRED | Saved; local data needs updating | Refresh local data |
| CONFIRMED + unknown cache | Saved; local update status unknown | None |

All confirmed descriptions distinguish service confirmation from delivery to the
Elder device. A cache problem must not enable another save. Unknown outcome must
not become a green success or a generic error with a retry-save button.

The future host must retain input before displaying the unsent message, enforce
session/access scope, validation, actual-photo-viewing evidence and time-zone
acknowledgement, and keep original operation identity during reconciliation.
Display actions do not bypass these guards. `REVIEW_INPUT` does not automatically
retry a rejected operation; recovery integration belongs to #54. `REFRESH_CACHE`
must not invoke another plan creation. Before binding the merged #43 editor,
review its actual state API and map it explicitly; no reflection or duplicate
domain state machine is introduced here.

Resources are isolated in `values/c2_plan_strings.xml` and
`values-zh/c2_plan_strings.xml`. They cover save outcomes, field labels, inline
validation and time-zone comparison. The host must supply all three real zone
values (or localized Unknown); the acknowledgement must follow a real comparison
and reminder example. No new locale setting, navigation or identity host is added.
Field validation resources are prepared, not yet bound to editor validation.

## Verification and accessibility boundary

The unit tests check safety properties across all cache states, distinct textual
outcomes, nonempty localized titles/descriptions/action labels, duplicate/missing
translation keys and positional formatting of three different zone values.
These are resource-level checks, not a TalkBack or rendered-layout pass.

Local result (2026-10-25): all 5 new tests passed. `gate1Check test` passed
(24 tasks executed, 2 from cache, 66 up-to-date). Reports contain Android debug
45 and release 45 tests, Shared 18 and Server 40, with zero failures/errors/skips;
Shared and Server test tasks were up-to-date, not freshly executed. Both locales
contain 38 resource keys. Repository policy and whitespace checks passed.
Lint has no errors; it reports unused-resource warnings (including prepared field
labels not yet bound to a page) and a missing application icon warning. No lint
warnings are suppressed by this change.

Run from repository root:

```powershell
.\gradlew.bat :android-app:testDebugUnitTest --tests '*PlanSavePresentationTest' --console=plain
.\gradlew.bat gate1Check test --console=plain
python scripts/check_repository_policy.py
```

## Required page/device checks (not run)

No form layout or production binding is part of this preparation. Each row below
remains pending until the design and C1 host are confirmed and the page exists.

| Check | Procedure and expected evidence |
| --- | --- |
| TalkBack field names | Traverse every field in both locales; persistent label, optional/required status and error are associated with the correct field. Hints do not replace labels. Record device/API/TalkBack version and observed speech. |
| Status announcement | Change save state; announce changed status politely once, without stealing focus. Unknown and cache-pending status are distinguishable without color. Re-rendering must not repeat announcements. |
| Actions | Focusable text buttons have meaningful names; avoid duplicate content descriptions. During submit, no repeat-save action is available. Check-result reconciles the original request. |
| Errors | Invalid time/date/duplicate time/end-before-start produces localized inline text; focus order reaches each error and input. Raw server messages are not spoken as instructions. |
| Text sizing | At 200% font size and 320dp/360dp widths, all Chinese/English text and long zone names remain readable and scrollable, without truncating actions or errors. Caregiver body >=16sp, supporting text >=14sp. |
| Touch targets | Measure actual bounds: every interactive target >=48 x 48dp; verify adjacent controls are independently usable. |
| Contrast and grayscale | Measure chosen final colors: ordinary text >=4.5:1, large text >=3:1. Repeat in grayscale; outcome remains understandable. Resource tests do not measure contrast. |
| Photo and zone evidence | Actual photo can be inspected accessibly; a content handle alone is not viewing evidence. Show actual zone comparison/example before acknowledgement; unavailable data cannot fabricate confirmation. |
| Hardware | Run the bound page on the supplied Mi Pad 4 Plus (reported Android 8.1.0 / MIUI 10.3.2); record actual OS and build at test time. Earlier demo-plan screenshots do not verify this page. |

Remaining dependencies: approved concrete form design, C1 navigation/session
entry contract, merged editor state integration (#43), actual photo display,
and real save/recovery adapters (#54). Human design/accessibility review and
device results must be recorded before claiming the complete #51 acceptance.
