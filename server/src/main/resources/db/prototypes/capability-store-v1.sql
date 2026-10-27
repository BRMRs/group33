-- Independent capability prototype, explicitly installed in a trusted isolated schema/transaction.
-- No dependency on pairing/plan prototype versions and no automatic DDL at application startup.
CREATE TABLE IF NOT EXISTS capability_guard (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO capability_guard VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS capability_observation (
    elder_id text NOT NULL,
    installation_id text NOT NULL,
    observation_sequence bigint NOT NULL CHECK (observation_sequence > 0),
    report_id uuid NOT NULL,
    report_payload jsonb NOT NULL,
    PRIMARY KEY (elder_id, installation_id, observation_sequence),
    UNIQUE (elder_id, report_id),
    UNIQUE (elder_id, installation_id, observation_sequence, report_id),
    CHECK ((jsonb_typeof(report_payload) = 'object'
        AND report_payload->>'reportId' = report_id::text
        AND report_payload->'observationSequence' = to_jsonb(observation_sequence)
        AND (report_payload->>'observedAtEpochMillis')::bigint >= 0
        AND report_payload->>'deliveryCondition' IN ('AVAILABLE','DEGRADED','UNAVAILABLE')
        AND jsonb_typeof(report_payload->'notificationPermissionGranted') = 'boolean'
        AND jsonb_typeof(report_payload->'channelEnabled') = 'boolean'
        AND report_payload->>'exactAlarmAccess' IN ('NOT_REQUIRED','GRANTED','DENIED')
        AND jsonb_typeof(report_payload->'reasons') = 'array') IS TRUE)
);

CREATE TABLE IF NOT EXISTS capability_current (
    elder_id text NOT NULL,
    installation_id text NOT NULL,
    observation_sequence bigint NOT NULL CHECK (observation_sequence > 0),
    report_id uuid NOT NULL,
    view_payload jsonb NOT NULL,
    PRIMARY KEY (elder_id, installation_id),
    FOREIGN KEY (elder_id, installation_id, observation_sequence, report_id)
        REFERENCES capability_observation(elder_id, installation_id, observation_sequence, report_id),
    CHECK ((view_payload->>'reportId' = report_id::text
        AND view_payload->'observationSequence' = to_jsonb(observation_sequence)
        AND (view_payload->>'receivedAtEpochMillis')::bigint >= 0) IS TRUE)
);

CREATE TABLE IF NOT EXISTS capability_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    elder_id text NOT NULL CHECK (elder_id = actor_id),
    installation_id text NOT NULL,
    canonicalization_version text NOT NULL CHECK (canonicalization_version = 'P0_CANONICAL_V1'),
    report_payload jsonb NOT NULL,
    accepted_report_id uuid,
    acknowledged_sequence bigint,
    receipt_current_sequence bigint,
    receipt_payload jsonb,
    rejection_reason text,
    PRIMARY KEY (actor_id, operation_id),
    UNIQUE (actor_id, accepted_report_id),
    FOREIGN KEY (elder_id, installation_id, acknowledged_sequence)
        REFERENCES capability_observation(elder_id, installation_id, observation_sequence) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (elder_id, installation_id, receipt_current_sequence)
        REFERENCES capability_observation(elder_id, installation_id, observation_sequence) DEFERRABLE INITIALLY DEFERRED,
    CHECK (((receipt_payload IS NULL AND accepted_report_id IS NULL AND acknowledged_sequence IS NULL
            AND receipt_current_sequence IS NULL AND rejection_reason IN ('ROLE_NOT_ALLOWED','RESOURCE_NOT_ACCESSIBLE',
                'INVALID_REPORT','IDEMPOTENCY_KEY_REUSED','CAPABILITY_SEQUENCE_CONFLICT'))
        OR (receipt_payload IS NOT NULL AND accepted_report_id = operation_id AND acknowledged_sequence > 0
            AND receipt_current_sequence >= acknowledged_sequence AND rejection_reason IS NULL
            AND report_payload->>'reportId' = operation_id::text
            AND report_payload->'observationSequence' = to_jsonb(acknowledged_sequence)
            AND receipt_payload->>'reportId' = operation_id::text
            AND jsonb_typeof(receipt_payload->'applied') = 'boolean'
            AND (receipt_payload->>'receivedAtEpochMillis')::bigint >= 0
            AND receipt_payload->'current'->'observationSequence' = to_jsonb(receipt_current_sequence))) IS TRUE)
);

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='capability_observation'::regclass AND conname='capability_observation_receipt') THEN
        ALTER TABLE capability_observation ADD CONSTRAINT capability_observation_receipt
            FOREIGN KEY (elder_id, report_id) REFERENCES capability_operation(actor_id, accepted_report_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END
$constraints$;

CREATE OR REPLACE FUNCTION p0_capability_complete() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_TABLE_NAME = 'capability_current' THEN
        IF NOT EXISTS (
            SELECT 1 FROM capability_observation p JOIN capability_operation o
                ON o.actor_id=p.elder_id AND o.accepted_report_id=p.report_id
            WHERE p.elder_id=NEW.elder_id AND p.installation_id=NEW.installation_id
                AND p.observation_sequence=NEW.observation_sequence AND p.report_id=NEW.report_id
                AND NEW.view_payload - 'receivedAtEpochMillis' = p.report_payload
                AND o.receipt_payload->'current' = NEW.view_payload AND o.receipt_payload->>'applied' = 'true'
        ) THEN RAISE EXCEPTION 'Capability current requires observation and original receipt'; END IF;
    ELSIF NEW.receipt_payload IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM capability_observation acknowledged JOIN capability_observation observed
                ON observed.elder_id=acknowledged.elder_id AND observed.installation_id=acknowledged.installation_id
            JOIN capability_operation source ON source.actor_id=observed.elder_id AND source.accepted_report_id=observed.report_id
            WHERE acknowledged.elder_id=NEW.elder_id AND acknowledged.installation_id=NEW.installation_id
                AND acknowledged.observation_sequence=NEW.acknowledged_sequence AND acknowledged.report_id=NEW.accepted_report_id
                AND acknowledged.report_payload=NEW.report_payload AND observed.observation_sequence=NEW.receipt_current_sequence
                AND (NEW.receipt_payload->'current') - 'receivedAtEpochMillis' = observed.report_payload
                AND source.receipt_payload->'current'=NEW.receipt_payload->'current' AND source.receipt_payload->>'applied'='true'
                AND (NEW.receipt_payload->>'applied'='false' OR (NEW.acknowledged_sequence=NEW.receipt_current_sequence
                    AND NEW.receipt_payload->'receivedAtEpochMillis'=NEW.receipt_payload->'current'->'receivedAtEpochMillis'))
        ) THEN RAISE EXCEPTION 'Capability receipt requires original report evidence'; END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE OR REPLACE FUNCTION p0_capability_immutable() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    IF TG_TABLE_NAME='capability_observation' OR TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Capability evidence is retained';
    ELSIF TG_TABLE_NAME='capability_operation' THEN
        IF (OLD.actor_id,OLD.operation_id,OLD.elder_id,OLD.installation_id,OLD.canonicalization_version,OLD.report_payload)
            IS DISTINCT FROM (NEW.actor_id,NEW.operation_id,NEW.elder_id,NEW.installation_id,NEW.canonicalization_version,NEW.report_payload)
            OR (OLD.receipt_payload IS NOT NULL AND OLD IS DISTINCT FROM NEW) THEN
            RAISE EXCEPTION 'Capability binding and accepted receipt are immutable';
        END IF;
    ELSIF (OLD.elder_id,OLD.installation_id) IS DISTINCT FROM (NEW.elder_id,NEW.installation_id)
        OR NEW.observation_sequence<=OLD.observation_sequence THEN
        RAISE EXCEPTION 'Capability current must advance sequence';
    END IF;
    RETURN NEW;
END
$function$;

DO $triggers$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='capability_current'::regclass AND tgname='capability_current_complete') THEN
        CREATE CONSTRAINT TRIGGER capability_current_complete AFTER INSERT OR UPDATE ON capability_current
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_capability_complete();
        CREATE TRIGGER capability_current_monotonic BEFORE UPDATE OR DELETE ON capability_current
            FOR EACH ROW EXECUTE FUNCTION p0_capability_immutable();
        CREATE CONSTRAINT TRIGGER capability_receipt_complete AFTER INSERT OR UPDATE ON capability_operation
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION p0_capability_complete();
        CREATE TRIGGER capability_operation_immutable BEFORE UPDATE OR DELETE ON capability_operation
            FOR EACH ROW EXECUTE FUNCTION p0_capability_immutable();
        CREATE TRIGGER capability_observation_immutable BEFORE UPDATE OR DELETE ON capability_observation
            FOR EACH ROW EXECUTE FUNCTION p0_capability_immutable();
    END IF;
END
$triggers$;
