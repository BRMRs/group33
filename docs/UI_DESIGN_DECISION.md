# UI design decision — confirmed P0 direction

The layouts currently in `android-app` are functional placeholders. They define navigation entry points only and are not the approved visual design.

The team selected the direction and accessibility profile below. Exact hexadecimal tokens, reusable styles, and final screen specifications remain implementation deliverables.

## Confirmed direction

- **Structure:** Calm healthcare dashboard.
- **Expression:** High-contrast utility.
- **Mood:** Calm and reliable rather than strongly clinical or illustration-led.
- **Surfaces:** White and light cool-neutral surfaces with a deep-blue accent, moderate rounding, restrained elevation, and Material Rounded icon language.
- **Caregiver density:** Balanced cards with compact rows inside grouped sections.
- **Elder density:** Spacious single-task layout with the current Due event as the primary visual focus.
- **Product identity:** Caregiver and Elder screens share one product name, color meanings, typography, icon family, spacing grid, shape language, vocabulary, and common state patterns.
- **P0 scope:** Dark mode, dynamic color, custom fonts, themes, decorative avatars/illustrations, tablets, landscape optimization, Wear OS, iOS, Web, and complex motion are deferred.

## Shared product rules

Both roles belong to one product and must share:

- one colour system and status meanings;
- one typography family and naming scale;
- one icon family;
- one spacing grid and component shape language;
- the same words for the same medication and dose states;
- reusable loading, empty, offline, error, and confirmation patterns.

The elder experience is an accessibility profile within this system. It is not a separately branded app.

## Confirmed semantic state rules

- Scheduled: neutral gray; Due: blue; Snoozed: purple; Taken: green; Missed: dark gray; Warning: amber; Error: red.
- Dose outcome, synchronization state, and reminder-delivery capability are separate categories.
- Every state includes text plus an icon or other non-color cue; color alone never communicates meaning.
- A Late taken report remains visibly associated with the Missed event and report time; it does not repaint the event as green Taken.

## Confirmed language and interaction rules

- Default language follows the device: Simplified Chinese for Chinese device languages, English otherwise. A manual choice offers Follow system, English, and 简体中文 and persists until reset.
- Use `Reminder plan` / `用药提醒计划` rather than `Schedule` / `日程` in product copy.
- Caregiver-facing Taken copy says the Elder reported taking the medication; it never claims verified ingestion.
- The Elder Due screen prioritizes planned time, medication name, dosage, necessary instructions, Taken, and Remind later.
- Taken uses a short two-step confirmation; Remind later is single-step and shows the next reminder time when it falls inside the Response window. If the fixed ten-minute delay reaches or passes the deadline, explicitly show that the window will end before another reminder; keep Taken available until the deadline and do not promise another notification.
- Loading, unavailable controls, duplicate-tap prevention, offline, sync, validation, authorization, and delivery-capability feedback remain accessible and actionable.

## Candidate directions (historical)

### A — Calm healthcare cards

Soft neutral surfaces, restrained blue/green accents, rounded cards, and a dashboard hierarchy. It feels reassuring and is easy to scan, but the team must keep contrast high enough and avoid excessive cards.

### B — High-contrast utility

White surfaces, dark text, a strong primary colour, compact lists on caregiver screens, and very prominent state labels. It is the clearest and easiest to implement consistently, but can feel more clinical.

### C — Friendly family companion

Warm neutral surfaces, friendly accent colours, supportive language, and selective avatars or illustrations. It can feel more human, but requires tighter asset discipline and more design work.

A hybrid is allowed only when the team names exactly which structural direction and which visual traits are being combined.

## Elder accessibility constraints

These constraints apply regardless of the selected visual direction:

- critical actions include text; they are not icon-only;
- information is never communicated by colour alone;
- layouts tolerate Android font scaling without clipped text;
- controls meet at least Android's 48 dp touch-target guidance, with the team choosing a larger elder target after device testing;
- the home screen prioritizes the next medication, its time, and one clear primary action;
- confirmations state what happened and how to undo an accidental action where appropriate;
- secondary information does not compete visually with the current reminder;
- test on a real device using increased font size, display size, TalkBack labels, and both portrait orientations used in the demo.

## Confirmed Elder accessibility profile

- Chinese role labels are `长者` and `照护者`; `长辈端` and `小辈端` are not canonical.
- Elder body text is at least 18sp, supporting text at least 16sp, and the current medication name/key status at least 24sp.
- Caregiver targets are at least 48×48dp; Elder controls are at least 56dp high and the current reminder primary action at least 64dp high.
- Critical actions contain text and are never icon-only.
- Test at 200% font size, with TalkBack, grayscale, long user-entered text, 360dp portrait, and a 320dp compatibility check without horizontal scrolling or disappearing actions.
- Normal-text contrast is at least 4.5:1 and large-text contrast at least 3:1.

## Implementation deliverables

The project lead creates and the team reviews:

- a token table for colour, typography, spacing, shape, elevation, and icon sizes;
- shared XML resources and reusable view styles;
- one caregiver reference screen and one elder reference screen;
- a component-state sheet covering normal, pressed, disabled, loading, empty, error, offline, due, taken, and missed states;
- an accessibility check against the two reference screens;
- a short UI review checklist in the pull-request template.
