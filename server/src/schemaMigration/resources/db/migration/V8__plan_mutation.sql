-- Additive local prototype after v1-v7. Explicit trusted installation only; no startup DDL.
CREATE UNIQUE INDEX IF NOT EXISTS reminder_plan_id_elder ON reminder_plan(id, elder_id);
CREATE TABLE IF NOT EXISTS plan_mutation_operation (
    actor_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('UPDATE', 'DEACTIVATE')),
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    elder_id text NOT NULL,
    plan_id uuid NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_revision bigint,
    receipt_payload jsonb,
    rejection_reason text,
    violations jsonb NOT NULL,
    PRIMARY KEY (actor_id, action, operation_id),
    FOREIGN KEY (plan_id, elder_id) REFERENCES reminder_plan(id, elder_id),
    FOREIGN KEY (plan_id, receipt_revision) REFERENCES reminder_plan_revision(plan_id, revision) DEFERRABLE INITIALLY DEFERRED,
    CHECK ((receipt_payload IS NULL AND receipt_revision IS NULL AND rejection_reason IS NOT NULL)
        OR (receipt_payload IS NOT NULL AND receipt_revision > 1 AND rejection_reason IS NULL AND violations = '[]'::jsonb
            AND receipt_payload->'plan'->>'id' = plan_id::text
            AND receipt_payload->'plan'->>'elderId' = elder_id
            AND receipt_payload->'medication'->>'elderId' = elder_id
            AND receipt_payload->'plan'->>'medicationId' = receipt_payload->'medication'->>'id'
            AND receipt_payload->'plan'->>'revision' = receipt_revision::text
            AND (action <> 'DEACTIVATE' OR receipt_payload->'plan'->>'active' = 'false')) IS TRUE)
);

CREATE OR REPLACE FUNCTION p0_plan_mutation_complete() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF NEW.revision <> OLD.revision + 1 OR NEW.elder_id <> OLD.elder_id OR NEW.medication_id <> OLD.medication_id
        OR NEW.initial_receipt_id <> OLD.initial_receipt_id OR (NEW.active AND NOT OLD.active) THEN
        RAISE EXCEPTION 'Invalid prospective plan transition';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM plan_mutation_operation o JOIN reminder_plan_revision r
            ON r.plan_id = o.plan_id AND r.revision = o.receipt_revision
        WHERE o.plan_id = NEW.id AND o.receipt_revision = NEW.revision AND o.receipt_payload IS NOT NULL
            AND r.elder_id = NEW.elder_id AND r.medication_id = NEW.medication_id
            AND r.local_times = NEW.local_times AND r.start_date = NEW.start_date
            AND r.end_date IS NOT DISTINCT FROM NEW.end_date AND r.execution_time_zone_id = NEW.execution_time_zone_id
            AND r.active = NEW.active AND r.effective_from = NEW.effective_from AND r.confirmed_at = NEW.confirmed_at
            AND o.receipt_payload->'plan'->'localTimes' = to_jsonb(r.local_times)
            AND o.receipt_payload->'plan'->>'startDate' = r.start_date
            AND o.receipt_payload->'plan'->>'endDate' IS NOT DISTINCT FROM r.end_date
            AND o.receipt_payload->'plan'->>'executionTimeZoneId' = r.execution_time_zone_id
            AND o.receipt_payload->'plan'->>'active' = r.active::text
            AND o.receipt_payload->'plan'->>'effectiveFromEpochMillis' = r.effective_from::text
            AND o.receipt_payload->'plan'->>'confirmedAtEpochMillis' = r.confirmed_at::text
            AND o.receipt_payload->'medication'->>'displayName' = r.display_name
            AND o.receipt_payload->'medication'->>'dosageText' = r.dosage_text
            AND o.receipt_payload->'medication'->>'instructions' IS NOT DISTINCT FROM r.instructions
    ) THEN RAISE EXCEPTION 'Plan mutation requires retained revision and original receipt'; END IF;
    RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION p0_medication_edit_complete() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF NEW.id <> OLD.id OR NEW.elder_id <> OLD.elder_id OR NEW.photo_id <> OLD.photo_id
        OR NEW.active <> OLD.active OR NEW.initial_receipt_id <> OLD.initial_receipt_id THEN
        RAISE EXCEPTION 'Medication identity is not editable';
    END IF;
    IF (NEW.display_name, NEW.dosage_text, NEW.instructions) IS DISTINCT FROM
        (OLD.display_name, OLD.dosage_text, OLD.instructions) AND NOT EXISTS (
        SELECT 1 FROM plan_mutation_operation o JOIN reminder_plan p ON p.id = o.plan_id AND p.revision = o.receipt_revision
            JOIN reminder_plan_revision r ON r.plan_id = p.id AND r.revision = p.revision
        WHERE p.medication_id = NEW.id AND o.action = 'UPDATE' AND o.receipt_payload IS NOT NULL
            AND r.display_name = NEW.display_name AND r.dosage_text = NEW.dosage_text
            AND r.instructions IS NOT DISTINCT FROM NEW.instructions
            AND o.receipt_payload->'medication'->>'id' = NEW.id::text
            AND o.receipt_payload->'medication'->>'displayName' = NEW.display_name
            AND o.receipt_payload->'medication'->>'dosageText' = NEW.dosage_text
            AND o.receipt_payload->'medication'->>'instructions' IS NOT DISTINCT FROM NEW.instructions
    ) THEN RAISE EXCEPTION 'Medication edit requires matching plan version and receipt'; END IF;
    RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION p0_plan_revision_immutable() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    RAISE EXCEPTION 'Retained plan revisions are immutable';
END
$function$;

DO $block$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'reminder_plan'::regclass AND tgname = 'plan_mutation_complete') THEN
        CREATE CONSTRAINT TRIGGER plan_mutation_complete AFTER UPDATE ON reminder_plan
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_plan_mutation_complete();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'medication'::regclass AND tgname = 'medication_edit_complete') THEN
        CREATE CONSTRAINT TRIGGER medication_edit_complete AFTER UPDATE ON medication
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_medication_edit_complete();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'reminder_plan_revision'::regclass AND tgname = 'plan_revision_immutable') THEN
        CREATE TRIGGER plan_revision_immutable BEFORE UPDATE OR DELETE ON reminder_plan_revision
            FOR EACH ROW EXECUTE FUNCTION p0_plan_revision_immutable();
    END IF;
END
$block$;
