# Medication Reminder

Current product baseline: **ELDER_REMINDER_V2**, [ADR-0008](docs/adr/0008-photo-first-elder-reminders-and-caregiver-handoff.md). Retired behavior is not an alternative implementation contract.

This context describes how a caregiver and an elder coordinate medication reminders and record user-reported outcomes without making medical decisions.

## Language

**Caregiver**:
A person who is authorized through pairing to create reminder plans for an elder, view reported reminder outcomes, and record separate caregiver-authored follow-up outcomes after contact.
_Avoid_: Admin, doctor, account owner

**Elder**:
A person who receives reminder plans and explicitly reports an outcome for each reminder.
_Avoid_: Patient, dependent, recipient

**Elder accessibility profile**:
The default presentation profile for the elder role within the shared product identity. It increases legibility and interaction clarity without changing domain meanings or creating a separately branded elder product.
_Avoid_: Elder app, senior mode, separate elder brand

**Pairing**:
The mutually explicit act in which an elder exposes a pairing opportunity, a caregiver enters the elder's pairing code, and the elder accepts the resulting request, establishing a care relationship.
_Avoid_: Automatic linking, caregiver assignment

**Installation identity**:
The anonymous identity associated with one app installation after login. It identifies the installation and its chosen role, but does not by itself authorize access to another person's data.
_Avoid_: Account, caregiver authorization, elder authorization

**Pairing code**:
A temporary code exposed by an elder so that a caregiver can identify a proposed pairing request; entering the code does not establish authorization until the elder accepts.
_Avoid_: Password, permanent invitation, access token

**Pending pairing**:
A proposed caregiver-elder relationship after a caregiver has entered an elder's pairing code and before the elder has explicitly accepted it.
_Avoid_: Established relationship, authorization

**Care relationship**:
The active authorization relationship created when an elder accepts a caregiver's pairing request. In the P0 model, one caregiver may have relationships with multiple elders, while an elder has at most one caregiver.
_Avoid_: Account hierarchy, administrator relationship

**Revoked relationship**:
A care relationship that either participant has ended. It no longer authorizes new access to the other participant's data.
_Avoid_: Deleted user, deleted history

**Authorization**:
Permission derived from an active care relationship for a specific participant to perform an allowed operation on an elder's data. Authentication or knowledge of an identifier is not authorization.
_Avoid_: Login, identity, role alone

**Elder data**:
The elder's reminder plans and reported dose-event history, including data recorded before and after a care relationship is established. An active care relationship authorizes the caregiver to view the elder's existing data and manage reminder plans within the agreed P0 permissions.
_Avoid_: Caregiver-owned data, medical record, verified clinical record

**Medication**:
Caregiver-confirmed display information for one medication associated with an elder, including its name, dosage text, and optional instructions or photo; it is not a prescription or verified clinical record.
_Avoid_: Prescription, drug order, treatment decision

**Medication photo**:
An elder-provided original-box image used for recognition and caregiver confirmation; development/demonstrations use fictional regimens and approved fixtures; it is not proof of identity, dosage, prescription, or ingestion, and it does not make OCR output authoritative.
_Avoid_: Prescription image, proof of medication, verified medication record

**Elder execution time zone**:
The time zone of the place where an elder is intended to follow a reminder plan. It is explicitly confirmed by the caregiver and is distinct from the caregiver device's current time zone or the elder's continuously tracked physical location.
_Avoid_: Caregiver time zone, device time zone, GPS location, automatic travel location

**Reminder plan**:
A caregiver-confirmed daily reminder arrangement for one medication and one elder, defined by local reminder times in the elder execution time zone, an inclusive start date, and an optional inclusive end date.
_Avoid_: Medication, prescription, treatment plan, medical order, schedule

**Dose event**:
A single planned occurrence generated from a reminder plan, together with its user-reported status and the plan meaning in effect when it was generated. Later plan or medication edits do not rewrite it, and it is not proof that medication was ingested.
_Avoid_: Medication administration record, proof of intake

**Scheduled**:
The state of a dose event whose planned reminder time has not yet arrived.
_Avoid_: Due, delivered reminder

**Due**:
The state of a dose event whose reminder time has arrived and is awaiting an elder action.
_Avoid_: Overdue medication, medical alert

**Taken**:
The elder's terminal report, recorded before a dose event's response window ends, that they took the associated medication(s) represented by the frozen reminder group; it supersedes Remind later for the same dose event. It is not independently verified, and a report recorded offline remains timely even if it synchronizes later.
_Avoid_: Verified dose, confirmed ingestion

**Late taken report**:
A retained Elder report from legacy/delayed evidence, recorded at or after the thirty-minute deadline, stating that they took the associated medication. The current Elder UI does not offer post-cutoff reporting. The fact is shared with the caregiver but does not replace the event's Missed state or prove ingestion.
_Avoid_: Taken, corrected dose, verified dose

**Remind later**:
A request by the elder to present the same dose event again after a fixed ten-minute delay, provided the response window is still open then. It does not create a new dose event or extend the response window; if the window ends first, the event becomes Missed unless a timely Taken report is known, and no further reminder is presented for that event.
_Avoid_: Reschedule medication, change prescription

**Snoozed**:
The state of a dose event after the elder chooses Remind later, until it is presented again, a timely Taken is reported, or its response window ends.
_Avoid_: New reminder plan, changed prescription

**Missed**:
The system-derived state of a dose event for which no timely Taken report is currently known after its response window ended. It does not prove that medication was not taken; a Taken report recorded offline before the deadline may reconcile it after delayed synchronization.
_Avoid_: Verified missed dose, proof of non-adherence, notification failure

**Response window**:
The fixed thirty-minute interval [plannedAt, plannedAt + 1_800_000ms) beginning at a dose event's original planned time during which the elder may report Taken, request Remind later or ask for help. The exact cutoff withdraws this reminder and its old actions, and creates caregiver follow-up if no timely report is known. Help or acknowledged takeover stops this occurrence's repeated prompting earlier; there is no second active response window.
_Avoid_: Dosage window, medically safe interval, snooze extension

**Pending sync**:
An internal synchronization state, not an ordinary Elder Home/reminder label, meaning an elder action has been recorded on the elder's device but is not yet available to the caregiver.
_Avoid_: Failed, lost, caregiver notified

**Sync needs attention**:
An internal synchronization state, not an ordinary Elder Home/reminder label, meaning an elder action remains recorded on the elder's device but the shared service rejected or could not complete its synchronization. It is not a dose-event outcome and does not mean the caregiver received the action.
_Avoid_: Synced, lost action, missed dose

**Synced**:
A synchronization state available to appropriate caregiver/diagnostic presentation, not an ordinary Elder Home/reminder label, meaning a recorded action is available to the caregiver through the shared service.
_Avoid_: Medically verified, delivered notification

**Reminder delivery degraded**:
A user-visible condition meaning the elder device can present reminder notifications but exact timing or the expected interruption level is unavailable, so delivery may be delayed, silent, or less prominent. It is not a dose-event status and does not by itself imply Missed.
_Avoid_: Exact reminder, on-time guarantee, synchronization failure

**Reminder delivery unavailable**:
A user-visible condition meaning the elder device cannot currently present reminder notifications, such as when notification permission is denied or the relevant notification channel is disabled. It is not a dose-event status and does not by itself imply Missed.
_Avoid_: Missed, no medication taken, synchronization failure

**Reminder group**:
The frozen set of confirmed same-time occurrences presented together in stable photo order; one Elder action applies to this whole set without per-medicine selection. Protocol identity, membership/transaction and partial-receipt handling require V2-T02 review, not medication compatibility inference.

**Caregiver follow-up**:
An actionable case raised immediately by Help, by the thirty-minute nonresponse cutoff, or separately by device-contact loss. Contact made alone does not complete an outcome-entry case; medication uncertainty and device recovery are closed independently.

**Caregiver outcome entry**:
An authorized, auditable Caregiver report of Taken / Not taken / Unable to confirm after contact, retaining actor/source, actual time or explicit unknown, entry/receipt times and prior entries. It is not an Elder Taken action, verified ingestion, or permission to erase conflicting evidence.

**Device contact lost**:
No fresh authenticated device contact within a separately reviewed short detection budget. It triggers its own Caregiver alert without waiting thirty minutes, but does not identify the cause or prove non-intake. The demo's two minutes is not a production SLA.
