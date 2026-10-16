# Scope and roadmap

## Product boundary

The app helps a caregiver configure medication reminders remotely while keeping the elder's interaction simple. It records reminder status; it does not provide medical advice.

## P0 core flow

1. An elder and caregiver establish a relationship using a temporary pairing code and explicit elder acceptance.
2. In the P0 walking-skeleton path, the elder uploads one fictional Medication photo.
3. The caregiver views that photo through the active relationship, then enters and confirms the medication name, dosage text, and Reminder plan.
4. The plan synchronizes to the elder's device.
5. The elder receives a local reminder and chooses taken or remind later.
6. The result synchronizes back to the caregiver.

P0 also includes Chinese and English resources, offline operation for already synchronized reminders, post-reboot reminder restoration after first unlock, the agreed Elder accessibility profile, and reproducible local deployment.

The approved deployment is Spring Boot on Render with Supabase PostgreSQL and Supabase Storage. The formal demonstration retains a tested local deployment configuration as a fallback; Android uses Room and explicit Room ↔ Server synchronization without a real-time push requirement. See [ADR-0004](adr/0004-hosting-storage-and-explicit-sync.md).

## P1 enhancements

- OCR suggestions that always require caregiver confirmation
- remote missed-dose notification
- refill estimate based on a caregiver-entered quantity
- broader accessibility and usability testing
- additional medication entries that do not use the P0 photo-to-plan path

## Out of scope

- diagnosis or treatment advice
- drug interaction or dosage decisions
- automatic scheduling from OCR output
- hospital, insurance, or national health database integration
- medicine purchasing or pharmacy links
- mandatory photographic proof of taking medication
- continuous location monitoring

## Delivery sequence

### Milestone 1 Foundation

- Every member can build the repository.
- Android role screens and server health check run.
- Shared models and API contract are understood.
- Anonymous login returns a JWT and protected endpoints reject unauthenticated requests.
- Feature owners apply the confirmed shared UI direction and Elder accessibility profile before styling screens.

### Milestone 2 Walking skeleton

- The Elder submits a fictional Medication photo after pairing.
- The Caregiver confirms a Reminder plan using that photo as reference.
- The server stores it.
- The elder device synchronizes it.
- A local test reminder appears.
- The elder confirms it.
- The caregiver sees the result.

### Milestone 3 Core completion

- Replace development-only data with Room and server persistence.
- Add pairing, photos, Reminder plan editing, and history.
- Cover offline, permission, restart, and failure paths.

### Milestone 4 Quality and evidence

- Conduct usability tests with fictional medication data.
- Fix accessibility and reliability issues.
- Record technical decisions, alternatives, tests, and individual contributions.
