-- Action receipts retain the original response. Current events inherit immutable occurrence evidence.
CREATE TABLE dose_group_operation (
    elder_id text NOT NULL REFERENCES anonymous_identity(id),
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    request_payload jsonb NOT NULL,
    response_payload jsonb NOT NULL,
    PRIMARY KEY (elder_id, operation_id),
    CHECK ((request_payload->>'actionId' = operation_id::text
        AND response_payload->>'actionId' = operation_id::text
        AND jsonb_typeof(request_payload->'occurrences') = 'array'
        AND jsonb_typeof(response_payload->'events') = 'array'
        AND jsonb_array_length(request_payload->'occurrences') BETWEEN 1 AND 100
        AND jsonb_array_length(request_payload->'occurrences') = jsonb_array_length(response_payload->'events')) IS TRUE)
);

CREATE TABLE recorded_dose_event (
    occurrence_id uuid PRIMARY KEY REFERENCES registered_occurrence(id),
    last_recorded_at bigint NOT NULL CHECK (last_recorded_at >= 0),
    status text NOT NULL CHECK (status IN ('TAKEN', 'SNOOZED', 'DUE', 'MISSED')),
    taken_recorded_at bigint,
    snoozed_until bigint,
    server_updated_at bigint NOT NULL CHECK (server_updated_at >= last_recorded_at),
    CHECK ((status = 'TAKEN' AND taken_recorded_at IS NOT NULL
        AND taken_recorded_at <= last_recorded_at AND snoozed_until IS NULL)
        OR (status <> 'TAKEN' AND taken_recorded_at IS NULL AND snoozed_until IS NOT NULL
        AND snoozed_until > last_recorded_at))
);

CREATE INDEX registered_occurrence_elder_date_idx ON registered_occurrence (elder_id, local_date);
