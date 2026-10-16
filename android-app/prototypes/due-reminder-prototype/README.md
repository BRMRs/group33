# Elder photo reminder — throwaway UI comparison

## Current C revision

Open http://localhost:4173/?variant=C for **C v2**. Six photos now use two columns and three rows in a scrolling region with three stationary reminder actions. Home has **Photograph a box** and no generic Help button. At 30 minutes the old reminder stops and care transfers to the caregiver. A separate two-minute device-contact-loss alert is simulated; caregiver contact must be followed by a Taken / Not taken / Unable to confirm entry. Actual intake time, caregiver entry time and delayed receipt remain separate. See [current C v2 rules and verification](C_V2_NOTES.md). [Previous research and image credits](C_RESEARCH.md) remain available; earlier C behavior is superseded where it differs from v2.

A/B below remain historical prototypes. The earlier description of C's moving groups is superseded by this revision.

Question: which presentation of actual medicine-box photos and partial-intake selection best supports gathering several boxes before swallowing?

Run from `group33`:

```sh
python3 -m http.server 4173 --directory android-app/prototypes/due-reminder-prototype
```

Open http://localhost:4173/?variant=A (B and C use the same route).

| Version | Photo presentation | Partial reporting |
| --- | --- | --- |
| A | Full-width photos in a long page | Check taken medicines below photos |
| B | One large photo at a time, explicit Previous/Next | Taken / not yet for each box, then shared review |
| C | Photo overview (one column on small screens / 200% font) | Tap to move boxes into a taken group, tap to move back |

All versions support a quiet home, single alarm, multiple alarm, missing boxes, partial intake, wrong quantity / uncertainty, leave and resume, correction, ten-minute snooze, and independent Pending sync / Synced / Sync needs attention. Normal intake needs one report action. Partial selection needs explicit review and commit. A draft is never a Taken report. Taken boxes are excluded from remaining medicine. Controls above/beside the simulated phone are for reviewers and never cover elder actions. Photos enlarge without changing selection. No drag gesture is required.

Use Review controls for scenes, English/Chinese, 100%/200% font, sync simulation and local photo replacement. Start with the multi-medicine Home, or select a scene to fire its alarm. The Home button simulates interruption within this page: reports and draft selections survive; reopening asks what actually happened. Reload clears everything. There is no Android implementation, backend, network sync, notification scheduler, storage or real caregiver contact.

## Photo sources

These are real packaging photographs, not generated mock-ups. They are locally bundled so the prototype works without image-host connectivity. Displayed quantities and the grouping are fictional interaction fixtures, not a treatment plan. Replace photos with the user's actual boxes for recognition testing. Packaging text remains in its original language. No image has been modified.

- Box 1: [Agen-5.jpg](https://commons.wikimedia.org/wiki/File:Agen-5.jpg), by [Wesalius](https://commons.wikimedia.org/wiki/User:Wesalius), [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Unmodified.
- Box 2: [Aldactone.JPG](https://commons.wikimedia.org/wiki/File:Aldactone.JPG), by [Miansari66](https://commons.wikimedia.org/wiki/User:Miansari66), [CC0](https://creativecommons.org/publicdomain/zero/1.0/). Unmodified.
- Box 3: [Acenocumarole.jpg](https://commons.wikimedia.org/wiki/File:Acenocumarole.jpg), by BQmUB2011115, [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). Unmodified.

## Open design decisions, not production changes

- The user requested actual box photos; the current glossary describes fictional-medication photos. This prototype uses public real packaging solely as recognition fixtures. It does not change the glossary or medication-photo contract.
- Same-time grouping is presentation only. Outcomes remain per medicine; sync state is separate. Wrong quantity / uncertainty is not mapped to Taken or Missed.
- Correction is an exploratory UI interaction. ADR-0003 treats timely Taken as final, so production correction would require a reviewed domain/API decision before implementation. This prototype restores only its in-memory last-action snapshot.
- Pending sync / Synced / Sync needs attention remain available to reviewers; elder copy prioritizes local recording and only expands sync detail on request.
- No layout winner has been validated. Keep this prototype as a primary source on a throwaway branch when the team captures its decision; do not promote this code directly into Android.

## Suggested walkthrough (repeat for A/B/C)

1. Open the default Home → see this reminder → inspect all three boxes → record all. Correct a mistaken report.
2. Fire a single-medicine scene; enlarge the photo, return, snooze, simulate the reminder, then record.
3. Fire a multiple scene; report box 2 missing; return and record only box 1. Box 1 must disappear from remaining tasks; boxes 2 and 3 must stay unconfirmed.
4. Start a partial selection, leave for Home, resume, check the selection is preserved but not submitted. Report only what was swallowed.
5. Mark wrong quantity / unsure; contact the caregiver (stub). Confirm it stays unresolved.
6. Try both languages at 200%, 360px and 320px; scroll to every action. Change sync state and verify no dose outcomes change.

## Verification performed

Browser walkthroughs covered all three partial-selection methods, draft retention across Home/resume, review and commit, remaining-medicine exclusion, missing-box help, correction, single-medicine completion in A/B/C, wrong-quantity handling in A/B/C, quiet Home in A/B/C, snooze and re-presentation, and attention → retry → synced without changing Taken outcomes. All three real photograph files loaded. DOM layout checks found no horizontal overflow on the three English partial-report screens at 200% font size in both 320px and 360px viewports. Chinese/English 200% alarm layouts were also inspected. This is browser font simulation; real-device Android/TalkBack and recognition testing with elders are still outside this prototype validation.
