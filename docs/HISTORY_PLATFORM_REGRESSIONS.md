# History platform regression correction

2026-11-26. Refs #19 and the C1 blocking review of PR #89 (`6068229799`).
This correction is stacked on `532cb1cf451682f2cb462e6aa030ebcc51ab0f7b`, which
includes actual main `855364eb`. Cle-cre's original component retains attribution.

## Behavior and scope

- The Debug History host explicitly disables decor fitting and applies received
  system-bar/cutout insets once in its container. The existing test still compares
  actual root insets with actual container padding; it does not substitute mock
  insets or accept zero padding. This corrects the fixture contract, not a claim
  that C1 proved occlusion in the formal application page.
- The calendar receives the component's primary display locale and font scale.
  Its title, inflated controls, day buttons, weekday labels and accessibility
  descriptions use that configuration. Fragment arguments retain these display
  inputs while the current host supplies window configuration. The Group33
  Material theme is initialized after the display override, so older Android
  does not reuse a Theme attached to the Activity's different Resources.
- Tests no longer mutate Activity-wide resources with `updateConfiguration`.
  The existing 18 cases assert actual English/Chinese and 1x/2x configuration,
  widths 320/360/600/320dp, and an explicitly Chinese host with an English
  component. The per-app locale is restored in `finally`; API33+ uses the platform
  locale service and older systems use AppCompat. No date/range/query, revocation,
  Repository, authorization or wire behavior changes.

## Evidence and reproduction

The local owned read-only API35 emulator completed all 18 cases with zero failed,
errored or skipped completions. It was stopped and its persistent configuration
was unchanged. An earlier baseline probe exposed that a nominal 2x popup actually
used 1x; its font assertion failed while two control cases passed. Those local
exploratory results are not the final same-test before/after matrix.

The `History platform regressions` workflow checks out the exact PR head and runs
fresh job-owned API27/x86 and API35/x86_64 emulators. It keeps the final test source
identical across two phases:

1. Overlay only the three runtime files from the pinned parent. The three targeted
   cases must complete uniquely: locale and font regressions fail on both APIs;
   the inset regression additionally fails on API27 and passes as an API35 control.
2. Restore reviewed HEAD, rebuild, and require all original 18 unique cases to
   pass. A skipped, missing, duplicate or failed final case fails the job.

Artifacts contain separate build/instrumentation logs, full head, actual API/ABI,
source/APK hashes, individual completion codes and source/AVD cleanup results.
Expected baseline failures never waive a final failure. Emulator and source
cleanup run even if a phase fails; no existing device or physical device is used.
Read the current-head workflow artifacts and PR evidence for actual hosted results.
Human C1/E/C3 review and adoption into #89 remain separate; the original C1 blocking
review is not dismissed by this child or by green automation.

No real data, Server/cloud/provider, TalkBack, physical API27 device, final Host or
whole-#19 acceptance is claimed. The previously captured component images retain
their original dates and are not relabeled as new captures.

Platform references: [WindowCompat inset dispatch](https://developer.android.com/reference/androidx/core/view/WindowCompat),
[configuration-specific Context](https://developer.android.com/reference/android/content/Context#createConfigurationContext(android.content.res.Configuration)),
[Context-specific inflation](https://developer.android.com/reference/android/view/LayoutInflater#cloneInContext(android.content.Context)),
and [API33 per-app locales](https://developer.android.com/reference/android/app/LocaleManager).
