-- Additive, explicitly installed local prototype after v1-v8. No startup DDL or public route.
CREATE TABLE IF NOT EXISTS occurrence_registration_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    elder_id text NOT NULL CHECK (elder_id = actor_id),
    canonicalization_version text NOT NULL CHECK (canonicalization_version = 'P0_CANONICAL_V1'),
    request_payload jsonb NOT NULL,
    response_payload jsonb,
    rejection_payload jsonb,
    PRIMARY KEY (actor_id, operation_id),
    CHECK ((jsonb_typeof(request_payload->'items') = 'array') IS TRUE),
    CHECK (((response_payload IS NOT NULL AND rejection_payload IS NULL AND jsonb_typeof(response_payload->'items') = 'array')
        OR (response_payload IS NULL AND rejection_payload->>'reason' = 'VALIDATION_FAILED')) IS TRUE)
);

CREATE TABLE IF NOT EXISTS registered_occurrence (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL,
    local_date text NOT NULL CHECK (local_date ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
    local_time text NOT NULL CHECK (local_time ~ '^(?:[01][0-9]|2[0-3]):[0-5][0-9]$'),
    plan_revision bigint NOT NULL CHECK (plan_revision > 0),
    elder_id text NOT NULL,
    medication_id uuid NOT NULL,
    installation_id text NOT NULL,
    generation_id uuid NOT NULL,
    origin_operation_id uuid NOT NULL,
    receipt_payload jsonb NOT NULL,
    UNIQUE (plan_id, local_date, local_time), -- revision and UTC offset deliberately excluded
    UNIQUE (elder_id, generation_id),
    FOREIGN KEY (plan_id, plan_revision, elder_id, medication_id)
        REFERENCES reminder_plan_revision(plan_id, revision, elder_id, medication_id),
    FOREIGN KEY (elder_id, origin_operation_id) REFERENCES occurrence_registration_operation(actor_id, operation_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK ((receipt_payload->>'generationId' = generation_id::text
        AND receipt_payload #>> '{event,id}' = id::text
        AND receipt_payload #>> '{event,elderId}' = elder_id
        AND receipt_payload #>> '{event,medicationId}' = medication_id::text
        AND receipt_payload #> '{event,planRevision}' = to_jsonb(plan_revision)
        AND receipt_payload #>> '{event,occurrence,planId}' = plan_id::text
        AND receipt_payload #>> '{event,occurrence,localDate}' = local_date
        AND receipt_payload #>> '{event,occurrence,localTime}' = local_time
        AND receipt_payload #> '{event,generationEvidence,occurrence}' = receipt_payload #> '{event,occurrence}'
        AND receipt_payload #>> '{event,generationEvidence,generationId}' = generation_id::text
        AND receipt_payload #> '{event,generationEvidence,planRevision}' = to_jsonb(plan_revision)
        AND receipt_payload #>> '{event,generationEvidence,resolutionPolicyVersion}' = 'P0_TIME_V1'
        AND receipt_payload #> '{event,generationEvidence,plannedAtEpochMillis}' = receipt_payload #> '{event,plannedAtEpochMillis}'
        AND receipt_payload #> '{event,generationEvidence,executionTimeZoneId}' = receipt_payload #> '{event,executionTimeZoneId}'
        AND (receipt_payload #>> '{event,responseDeadlineEpochMillis}')::numeric =
            (receipt_payload #>> '{event,plannedAtEpochMillis}')::numeric + 7200000
        AND receipt_payload #>> '{event,status}' IN ('SCHEDULED','DUE','MISSED')
        AND receipt_payload #> '{event,lateTakenReports}' = '[]'::jsonb) IS TRUE)
);

CREATE OR REPLACE FUNCTION p0_registration_complete(p_actor text, p_operation uuid) RETURNS void
LANGUAGE plpgsql AS $function$
DECLARE op occurrence_registration_operation%ROWTYPE;
BEGIN
    SELECT * INTO STRICT op FROM occurrence_registration_operation WHERE actor_id=p_actor AND operation_id=p_operation;
    IF op.response_payload IS NULL THEN
        IF EXISTS (SELECT 1 FROM registered_occurrence WHERE elder_id=p_actor AND origin_operation_id=p_operation) THEN
            RAISE EXCEPTION 'Rejected registration cannot own an event';
        END IF;
    ELSE
        IF jsonb_array_length(op.request_payload->'items') = 0 OR
            jsonb_array_length(op.request_payload->'items') <> jsonb_array_length(op.response_payload->'items') OR
            EXISTS (
                SELECT 1 FROM jsonb_array_elements(op.request_payload->'items') WITH ORDINALITY q(evidence, n)
                JOIN jsonb_array_elements(op.response_payload->'items') WITH ORDINALITY r(receipt, n) USING (n)
                WHERE (r.receipt->'event'->'generationEvidence' = q.evidence AND
                    r.receipt->>'generationId' = q.evidence->>'generationId') IS NOT TRUE OR NOT EXISTS (
                    SELECT 1 FROM registered_occurrence e WHERE e.elder_id=p_actor AND e.installation_id=op.installation_id
                        AND e.receipt_payload=r.receipt)) THEN
            RAISE EXCEPTION 'Registration batch and durable receipts must agree';
        END IF;
        IF EXISTS (SELECT 1 FROM registered_occurrence e WHERE e.elder_id=p_actor AND e.origin_operation_id=p_operation
            AND NOT (op.response_payload->'items' @> jsonb_build_array(e.receipt_payload))) THEN
            RAISE EXCEPTION 'Registration batch omitted an event';
        END IF;
    END IF;
END
$function$;

CREATE OR REPLACE FUNCTION p0_registration_consistent() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE retained reminder_plan_revision%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME = 'registered_occurrence' THEN
        SELECT * INTO STRICT retained FROM reminder_plan_revision r WHERE r.plan_id=NEW.plan_id AND r.revision=NEW.plan_revision;
        IF (NEW.receipt_payload #>> '{event,medicationSnapshot,displayName}' = retained.display_name AND
            NEW.receipt_payload #>> '{event,medicationSnapshot,dosageText}' = retained.dosage_text AND
            (NEW.receipt_payload #>> '{event,medicationSnapshot,instructions}') IS NOT DISTINCT FROM retained.instructions AND
            NEW.receipt_payload #>> '{event,executionTimeZoneId}' = retained.execution_time_zone_id) IS NOT TRUE THEN
            RAISE EXCEPTION 'Occurrence must retain its confirmed revision text and zone';
        END IF;
        PERFORM p0_registration_complete(NEW.elder_id, NEW.origin_operation_id);
    ELSE
        PERFORM p0_registration_complete(NEW.actor_id, NEW.operation_id);
    END IF;
    RETURN NULL;
END
$function$;
DROP TRIGGER IF EXISTS registration_complete ON registered_occurrence;
CREATE CONSTRAINT TRIGGER registration_complete AFTER INSERT ON registered_occurrence
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_registration_consistent();
DROP TRIGGER IF EXISTS registration_complete ON occurrence_registration_operation;
CREATE CONSTRAINT TRIGGER registration_complete AFTER INSERT OR UPDATE ON occurrence_registration_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_registration_consistent();

CREATE OR REPLACE FUNCTION p0_registration_immutable() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_OP = 'DELETE' OR TG_TABLE_NAME = 'registered_occurrence' THEN
        RAISE EXCEPTION 'Registration evidence is retained';
    END IF;
    IF (NEW.actor_id, NEW.operation_id, NEW.elder_id, NEW.installation_id, NEW.canonicalization_version, NEW.request_payload)
        IS DISTINCT FROM (OLD.actor_id, OLD.operation_id, OLD.elder_id, OLD.installation_id, OLD.canonicalization_version, OLD.request_payload)
        OR (OLD.response_payload IS NOT NULL AND (NEW.response_payload, NEW.rejection_payload) IS DISTINCT FROM
            (OLD.response_payload, OLD.rejection_payload)) THEN
        RAISE EXCEPTION 'Registration binding or accepted receipt cannot change';
    END IF;
    RETURN NEW;
END
$function$;
DROP TRIGGER IF EXISTS registration_immutable ON registered_occurrence;
CREATE TRIGGER registration_immutable BEFORE UPDATE OR DELETE ON registered_occurrence
    FOR EACH ROW EXECUTE FUNCTION p0_registration_immutable();
DROP TRIGGER IF EXISTS registration_immutable ON occurrence_registration_operation;
CREATE TRIGGER registration_immutable BEFORE UPDATE OR DELETE ON occurrence_registration_operation
    FOR EACH ROW EXECUTE FUNCTION p0_registration_immutable();
