-- Additive local prototype after v1-v6. Install explicitly in a trusted isolated schema/transaction.
-- Typed dates stay ISO text to preserve the validated four-digit proleptic year 0000 wire range.
CREATE UNIQUE INDEX IF NOT EXISTS medication_photo_id_elder ON medication_photo(id, elder_id);

CREATE OR REPLACE FUNCTION p0_valid_plan_times(p_times text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE STRICT AS $function$
    SELECT COALESCE(array_ndims(p_times) = 1 AND array_lower(p_times, 1) = 1 AND
        (SELECT count(*) BETWEEN 1 AND 1440 AND count(*) = count(DISTINCT t) AND
            bool_and(t IS NOT NULL AND t ~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$')
         FROM unnest(p_times) AS values_table(t)), false)
$function$;

CREATE TABLE IF NOT EXISTS medication (
    id uuid PRIMARY KEY,
    elder_id text NOT NULL,
    display_name text NOT NULL CHECK (length(display_name) > 0),
    dosage_text text NOT NULL CHECK (length(dosage_text) > 0),
    instructions text,
    photo_id uuid NOT NULL,
    active boolean NOT NULL,
    initial_receipt_id uuid NOT NULL UNIQUE,
    FOREIGN KEY (photo_id, elder_id) REFERENCES medication_photo(id, elder_id),
    UNIQUE (id, elder_id),
    UNIQUE (id, elder_id, initial_receipt_id)
);

CREATE TABLE IF NOT EXISTS reminder_plan (
    id uuid PRIMARY KEY,
    elder_id text NOT NULL,
    medication_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    local_times text[] NOT NULL CHECK (p0_valid_plan_times(local_times)),
    start_date text NOT NULL CHECK (start_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    end_date text CHECK (end_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND end_date >= start_date),
    execution_time_zone_id text NOT NULL CHECK (length(execution_time_zone_id) > 0),
    active boolean NOT NULL,
    effective_from bigint NOT NULL CHECK (effective_from >= 0),
    confirmed_at bigint NOT NULL CHECK (confirmed_at >= 0),
    initial_receipt_id uuid NOT NULL UNIQUE,
    FOREIGN KEY (medication_id, elder_id, initial_receipt_id) REFERENCES medication(id, elder_id, initial_receipt_id),
    UNIQUE (id, elder_id, medication_id),
    UNIQUE (id, elder_id, medication_id, initial_receipt_id)
);

CREATE TABLE IF NOT EXISTS reminder_plan_revision (
    plan_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    elder_id text NOT NULL,
    medication_id uuid NOT NULL,
    local_times text[] NOT NULL CHECK (p0_valid_plan_times(local_times)),
    start_date text NOT NULL CHECK (start_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    end_date text CHECK (end_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND end_date >= start_date),
    execution_time_zone_id text NOT NULL CHECK (length(execution_time_zone_id) > 0),
    active boolean NOT NULL,
    effective_from bigint NOT NULL CHECK (effective_from >= 0),
    confirmed_at bigint NOT NULL CHECK (confirmed_at >= 0),
    display_name text NOT NULL CHECK (length(display_name) > 0),
    dosage_text text NOT NULL CHECK (length(dosage_text) > 0),
    instructions text,
    PRIMARY KEY (plan_id, revision),
    FOREIGN KEY (plan_id, elder_id, medication_id) REFERENCES reminder_plan(id, elder_id, medication_id),
    UNIQUE (plan_id, revision, elder_id, medication_id)
);

CREATE TABLE IF NOT EXISTS plan_confirmation_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    elder_id text NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    receipt_id uuid UNIQUE,
    medication_id uuid,
    plan_id uuid,
    receipt_revision bigint,
    receipt_payload jsonb,
    rejection_reason text,
    violations jsonb NOT NULL,
    PRIMARY KEY (actor_id, operation_id),
    UNIQUE (receipt_id, medication_id, elder_id),
    UNIQUE (receipt_id, plan_id, elder_id, medication_id),
    FOREIGN KEY (medication_id, elder_id, receipt_id) REFERENCES medication(id, elder_id, initial_receipt_id),
    FOREIGN KEY (plan_id, elder_id, medication_id, receipt_id) REFERENCES reminder_plan(id, elder_id, medication_id, initial_receipt_id),
    FOREIGN KEY (plan_id, receipt_revision, elder_id, medication_id) REFERENCES reminder_plan_revision(plan_id, revision, elder_id, medication_id),
    CHECK ((jsonb_typeof(violations) = 'array' AND (
        (receipt_id IS NULL AND medication_id IS NULL AND plan_id IS NULL AND receipt_revision IS NULL AND receipt_payload IS NULL
            AND rejection_reason IN ('RESOURCE_NOT_ACCESSIBLE','ROLE_NOT_ALLOWED','IDEMPOTENCY_KEY_REUSED',
                'VALIDATION_FAILED','OVERLAPPING_PLAN','OPERATION_IN_PROGRESS','SERVICE_UNAVAILABLE'))
        OR (receipt_id IS NOT NULL AND medication_id IS NOT NULL AND plan_id IS NOT NULL AND receipt_revision = 1
            AND rejection_reason IS NULL AND violations = '[]'::jsonb AND jsonb_typeof(receipt_payload) = 'object'
            AND receipt_payload #>> '{medication,id}' = medication_id::text
            AND receipt_payload #>> '{medication,elderId}' = elder_id
            AND receipt_payload #>> '{plan,id}' = plan_id::text
            AND receipt_payload #>> '{plan,elderId}' = elder_id
            AND receipt_payload #>> '{plan,medicationId}' = medication_id::text
            AND receipt_payload #> '{plan,revision}' = to_jsonb(receipt_revision))
    )) IS TRUE)
);

-- Deferred reverse links prevent committing a new orphan resource, even through a partial Store call.
-- DO blocks allow non-destructive reapplication without dropping an existing constraint.
DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'medication'::regclass AND conname = 'medication_initial_receipt') THEN
        ALTER TABLE medication ADD CONSTRAINT medication_initial_receipt
            FOREIGN KEY (initial_receipt_id, id, elder_id) REFERENCES plan_confirmation_operation(receipt_id, medication_id, elder_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'reminder_plan'::regclass AND conname = 'plan_initial_receipt') THEN
        ALTER TABLE reminder_plan ADD CONSTRAINT plan_initial_receipt
            FOREIGN KEY (initial_receipt_id, id, elder_id, medication_id) REFERENCES plan_confirmation_operation(receipt_id, plan_id, elder_id, medication_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'reminder_plan'::regclass AND conname = 'plan_current_revision') THEN
        ALTER TABLE reminder_plan ADD CONSTRAINT plan_current_revision
            FOREIGN KEY (id, revision, elder_id, medication_id) REFERENCES reminder_plan_revision(plan_id, revision, elder_id, medication_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END
$constraints$;
