CREATE TABLE caregiver_follow_up (
    action_id uuid PRIMARY KEY,
    elder_id text NOT NULL,
    group_id text NOT NULL,
    request_payload jsonb NOT NULL,
    receipt_payload jsonb NOT NULL,
    follow_up_payload jsonb NOT NULL,
    received_at bigint NOT NULL CHECK (received_at >= 0),
    CHECK ((request_payload->>'actionId' = action_id::text
        AND request_payload->>'groupId' = group_id
        AND request_payload->>'elderId' = elder_id
        AND jsonb_array_length(request_payload->'occurrenceIds') > 0
        AND receipt_payload->>'actionId' = action_id::text
        AND receipt_payload->>'groupId' = group_id
        AND receipt_payload->>'status' = 'ACCEPTED'
        AND (receipt_payload->>'receivedAtEpochMillis')::numeric = received_at
        AND follow_up_payload->>'actionId' = action_id::text
        AND follow_up_payload->>'groupId' = group_id
        AND follow_up_payload->>'elderId' = elder_id
        AND follow_up_payload->>'state' = 'NEEDS_CONTACT'
        AND (follow_up_payload->>'receivedAtEpochMillis')::numeric = received_at) IS TRUE)
);

CREATE INDEX caregiver_follow_up_elder_received
    ON caregiver_follow_up (elder_id, received_at DESC, action_id);

CREATE FUNCTION p0_help_immutable() RETURNS trigger LANGUAGE plpgsql AS $function$
BEGIN
    RAISE EXCEPTION 'Help intent and caregiver follow-up evidence are retained';
END
$function$;
CREATE TRIGGER caregiver_follow_up_immutable BEFORE UPDATE OR DELETE ON caregiver_follow_up
    FOR EACH ROW EXECUTE FUNCTION p0_help_immutable();
