# C v2 — photo overview and caregiver handoff

Current entry: http://localhost:4173/?variant=C

This is a throwaway browser prototype under `android-app/prototypes/`. Only `c-v2.js`, `c-v2.css` and the prototype entry loader implement this revision. Kotlin, XML, server endpoints and shared models are unchanged. All clocks, records, photos and notifications live in page memory; reloading resets the demo.

## Confirmed design direction in this conversation

- Elder Home has a short current-state message and **Photograph a box**. It has no generic help button. An active reminder may have a reminder entry.
- Six original-box photographs use **two columns and three rows**. The photo region scrolls; its cards do not shrink to fit. Three text-and-icon action buttons occupy a separate stationary area beneath it and do not cover the last photo.
- The alarm actions are **Medicines taken / Remind in 10 minutes / Need help**. No per-medicine selection or quantity reporting is requested from the elder.
- The first reminder is 18:00. At **18:30**, an unconfirmed local reminder is withdrawn and handed off. Delaying does not reset the cutoff. If a new ten-minute delay would reach the cutoff, the prototype does not promise another alarm. Old photographs and intake controls do not reappear after handoff or a simulated power cycle.
- Help is specific to the active medicine reminder. It stops repeated medicine prompts and creates a caregiver follow-up when received.
- Caregiver follow-up is **contact needed → contact made, outcome needed → recorded**. Contact alone does not complete the case.
- Caregiver outcomes are **taken / not taken / unable to confirm**. Taken can include actual time or an explicit unknown-time flag. Actual time, caregiver entry time, elder action time and receipt time remain distinct. Unable to confirm is recorded but remains visibly needing attention.
- Every updated caregiver entry is retained. An arriving elder report that contradicts a caregiver entry is shown as a conflict; neither record is silently overwritten.

## Device-contact monitoring is separate

The evaluator can simulate a phone that is connected, on but offline, or powered off. The caregiver is not told which failure was selected; only lack of device contact is visible to them.

**Two minutes without a device message** generates a separate connection alert in this demo. This is an illustrative detection interval, not a production service guarantee. Time advances minute by minute, so jumping +30 still records the device alert at 18:02 and medication handoff at 18:30 separately. Repeated time advancement does not duplicate alerts within one connection-loss episode.

- A powered-on offline phone still runs its cached reminder and local 18:30 cutoff.
- A powered-off phone shows no active elder UI. On reconnecting after the cutoff it does not replay the old alarm.
- Caregiver entries can be recorded while the phone is offline. The caregiver sees that device acknowledgement is pending; the prototype never claims the offline phone was remotely stopped.
- Reconnection transfers pending local records with original action times and acknowledges caregiver takeover. It does not replay old reminders.
- Contact before the planned dose time does not create a future intake outcome. An outcome-entry action becomes available once the reminder time has arrived.

## Photographing a box

Home → photo screen → preview → use / retake → photo saved. The shutter invokes a browser `image/*` file input with `capture="environment"`: mobile browsers may offer the camera, and desktop browsers offer image selection. No camera is activated automatically. Real mobile camera behavior still needs device testing.

For desktop reviews, **Simulate a box photo** supplies the existing sample photograph and goes through the same preview/save flow. It is an evaluator control, not an extra elder action.

Photos are saved locally in memory first. The caregiver inbox receives them only when the simulated connection accepts them, preserving capture and receipt times. Reviewing a photo does not automatically replace current medicine images, add a medication, perform OCR or change a reminder plan.

## How to run

From `group33`:

```sh
python3 -m http.server 4173 --directory android-app/prototypes/due-reminder-prototype
```

Open the C URL and use the **Demo controls** below/beside the caregiver panel. Buttons trigger the alarm, advance the clock, reset, simulate device states and supply a sample camera image. Chinese and English switches are above the simulated phone. Font compatibility options remain evaluator-only.

## Suggested review paths

1. Home: no help button; open photo screen, preview/retake/save, then review the received photo as caregiver.
2. Normal: trigger 18:00 alarm, browse all six photos, choose Medicines taken. The caregiver sees the received elder report; no follow-up is generated.
3. Delay: choose ten minutes later, advance ten minutes, see the same group again. At 18:20 another ten-minute delay is not promised; at 18:30 the alarm and dose controls disappear.
4. No response: advance to 18:30, mark contact made, then save one of the three outcomes. A late actual intake time cannot be later than simulated current time; unknown time must be explicit.
5. Device loss: disconnect at 18:00, advance two minutes. The connection alert is independent of the 18:30 handoff. Contact and record an outcome; observe pending device acknowledgement until reconnecting.
6. Power loss: turn the phone off during the reminder, advance past 18:30, reconnect. No old alarm is replayed.
7. Delayed conflict: record Taken on the offline elder phone, record Not taken as caregiver after contact, reconnect. Both sources remain and the discrepancy is flagged for review.
8. Partial/uncertain information: caregiver records Unable to confirm; the interface keeps attention visible without asking the elder for detailed selection.

## Relationship to existing project decisions

This revision follows the user's requested prototype rules. The existing ADR/API documents use a two-hour response window and elder-owned reports; they do not yet define this 30-minute UI handoff or caregiver-authored outcome entries. These are explicit design changes for validation, not an implicit production migration. Stopping a reminder is not a claim that thirty minutes is a universal medically safe/unsafe time to take a medicine. The prototype does not prescribe replacement doses.

Existing product research and photo licences remain in [C_RESEARCH.md](C_RESEARCH.md) and [README.md](README.md). The previous C rules described there are historical where they differ from this file. Photos and quantities remain fictional interaction fixtures using real publicly licensed packaging photographs.

## Browser verification recorded this round

- Home has a camera action and no Help action; all three reminder actions remain available during the active window.
- Camera preview, save, received-photo review, and offline photo queuing/reconnection were exercised using the evaluator sample. The real phone camera was not activated during automated review.
- Ten-minute re-presentation and exact 18:30 withdrawal were walked through; triggering the old alarm again after cutoff leaves Home unchanged.
- Caregiver Taken, Not taken and Unable to confirm entries were submitted. Actual 18:05 and entry 18:30 remained distinct; future time 19:00 at clock 18:30 was rejected. Updates retained prior entries.
- Offline alert at 18:02, acknowledgement pending during caregiver registration, local cutoff at 18:30 and receipt on reconnect were verified.
- Simulated shutdown through 18:35 did not replay the old alarm on recovery.
- Contradictory late elder and caregiver reports were both retained with a conflict indicator.
- At 360px wide, the photo grid measured two equal columns; each card retained its full height. Scrolling to the last row did not change the three action-button positions, and the last caption remained above the action area.
- Chinese and English at 100% and 200% were checked at 320px/360px. A large-English header overflow was corrected; the final 200% English checks measured elder content width equal to available width (298px at 320px, 338px at 360px). Large fonts may use normal page scrolling; the default font uses the dedicated photo region.
- The English Unable to confirm → update to Taken with explicitly unknown actual time path retained both entries and kept the unknown timestamp null, rather than substituting registration time.

These checks establish runnable prototype behavior, not older-adult usability, real push delivery, durable storage, Android alarm restoration or clinical validation.
