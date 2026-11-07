-- Local forward prototype after v1 and v2. Execute the complete script in one transaction.
-- Not a production migration selection. Existing request/code/receipt rows are retained.
CREATE TABLE IF NOT EXISTS pairing_relationship (
    id text PRIMARY KEY,
    elder_id text NOT NULL,
    caregiver_id text NOT NULL,
    elder_name text NOT NULL,
    caregiver_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at bigint NOT NULL,
    revoked_at bigint,
    UNIQUE (id, elder_id, caregiver_id),
    CHECK (((status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)) IS TRUE)
);
CREATE UNIQUE INDEX IF NOT EXISTS pairing_one_active_elder ON pairing_relationship(elder_id) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS pairing_relationship_caregiver ON pairing_relationship(caregiver_id);

ALTER TABLE pairing_request ADD COLUMN IF NOT EXISTS relationship_id text;
ALTER TABLE pairing_request DROP CONSTRAINT IF EXISTS pairing_request_status_check;
ALTER TABLE pairing_request DROP CONSTRAINT IF EXISTS pairing_request_check;
ALTER TABLE pairing_request DROP CONSTRAINT IF EXISTS pairing_request_check1;
ALTER TABLE pairing_request DROP CONSTRAINT IF EXISTS pairing_request_v3_state;
ALTER TABLE pairing_request ADD CONSTRAINT pairing_request_v3_state CHECK ((
    (status = 'PENDING' AND closed_reason IS NULL AND relationship_id IS NULL)
    OR (status = 'ACCEPTED' AND closed_reason IS NULL AND relationship_id IS NOT NULL)
    OR (status = 'REJECTED' AND closed_reason = 'ELDER_REJECTED' AND relationship_id IS NULL)
    OR (status = 'CANCELLED' AND closed_reason IN ('CODE_REPLACED', 'ELDER_CANCELLED', 'CAREGIVER_CANCELLED') AND relationship_id IS NULL)
    OR (status = 'EXPIRED' AND closed_reason = 'CODE_EXPIRED' AND relationship_id IS NULL)
) IS TRUE);
ALTER TABLE pairing_request DROP CONSTRAINT IF EXISTS pairing_request_relationship_fk;
ALTER TABLE pairing_request ADD CONSTRAINT pairing_request_relationship_fk
    FOREIGN KEY (relationship_id, elder_id, caregiver_id) REFERENCES pairing_relationship(id, elder_id, caregiver_id);
CREATE UNIQUE INDEX IF NOT EXISTS pairing_request_relationship_unique ON pairing_request(relationship_id) WHERE relationship_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS pairing_decision_operation (
    actor_id text NOT NULL,
    action text NOT NULL CHECK (action IN ('ACCEPT', 'REJECT', 'CANCEL', 'REVOKE')),
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    fingerprint text NOT NULL,
    result_kind text NOT NULL,
    result_payload jsonb,
    relationship_id text REFERENCES pairing_relationship(id),
    request_id text REFERENCES pairing_request(id),
    rejection_reason text,
    retry_after bigint,
    PRIMARY KEY (actor_id, action, operation_id),
    CHECK ((
        (result_kind = 'RELATIONSHIP' AND action IN ('ACCEPT', 'REVOKE') AND relationship_id IS NOT NULL
            AND request_id IS NULL AND jsonb_typeof(result_payload) = 'object' AND result_payload->>'id' = relationship_id
            AND rejection_reason IS NULL AND retry_after IS NULL)
        OR (result_kind = 'REQUEST' AND action = 'REJECT' AND request_id IS NOT NULL AND relationship_id IS NULL
            AND jsonb_typeof(result_payload) = 'object' AND result_payload->>'id' = request_id
            AND rejection_reason IS NULL AND retry_after IS NULL)
        OR (result_kind = 'CANCELLED' AND action = 'CANCEL' AND result_payload IS NULL AND relationship_id IS NULL
            AND request_id IS NULL AND rejection_reason IS NULL AND retry_after IS NULL)
        OR (result_kind = 'REJECTED' AND result_payload IS NULL AND relationship_id IS NULL AND request_id IS NULL
            AND rejection_reason IN ('RESOURCE_NOT_ACCESSIBLE', 'ROLE_NOT_ALLOWED', 'IDEMPOTENCY_KEY_REUSED',
                'PAIRING_REQUEST_CLOSED', 'ELDER_ALREADY_PAIRED', 'OPERATION_IN_PROGRESS')
            AND (retry_after IS NULL OR retry_after > 0))
    ) IS TRUE)
);
