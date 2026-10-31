-- Explicit maintenance migration: preserve every row and existing integrity index.
-- Ordinary transactional index builds block writers; a busy schema must fail and retry later.
SET LOCAL lock_timeout = '1s';
SET LOCAL statement_timeout = '30s';

-- Do not silently accept an unrelated object with one of these names.
CREATE INDEX medication_elder_collection ON medication (elder_id, id);
CREATE INDEX reminder_plan_elder_collection ON reminder_plan (elder_id, id);
CREATE INDEX reminder_plan_revision_elder_collection ON reminder_plan_revision (elder_id, plan_id, revision);
