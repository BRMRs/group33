-- Local prototype extension after pairing-code-store-v1.sql, not a production migration.
-- No FK to the active-code row: replacement must retain consumed/terminal request evidence.
CREATE TABLE IF NOT EXISTS pairing_request (
    id text PRIMARY KEY,
    code_id text NOT NULL UNIQUE,
    elder_id text NOT NULL,
    caregiver_id text NOT NULL,
    caregiver_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'CANCELLED', 'EXPIRED')),
    expires_at bigint NOT NULL,
    closed_reason text,
    CHECK ((status = 'PENDING' AND closed_reason IS NULL)
        OR (status = 'CANCELLED' AND closed_reason = 'CODE_REPLACED')
        OR (status = 'EXPIRED' AND closed_reason = 'CODE_EXPIRED')),
    CHECK (status = 'PENDING' OR closed_reason IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS pairing_request_elder ON pairing_request(elder_id);
CREATE INDEX IF NOT EXISTS pairing_request_caregiver ON pairing_request(caregiver_id);

CREATE TABLE IF NOT EXISTS pairing_request_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    fingerprint text NOT NULL,
    receipt_request_id text REFERENCES pairing_request(id),
    rejection_reason text,
    retry_after bigint,
    PRIMARY KEY (actor_id, operation_id),
    CHECK ((receipt_request_id IS NOT NULL AND rejection_reason IS NULL)
        OR (receipt_request_id IS NULL AND rejection_reason IS NOT NULL)),
    CHECK (rejection_reason IS NULL OR rejection_reason IN (
        'ROLE_FORBIDDEN', 'IDEMPOTENCY_KEY_REUSED', 'PAIRING_CODE_UNAVAILABLE',
        'RESOURCE_NOT_ACCESSIBLE', 'RATE_LIMITED', 'OPERATION_IN_PROGRESS')),
    CHECK (retry_after IS NULL OR (rejection_reason IS NOT NULL AND retry_after > 0))
);

CREATE TABLE IF NOT EXISTS pairing_request_attempt (
    scope text NOT NULL CHECK (scope IN ('INSTALLATION', 'ADDRESS')),
    owner_key text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    attempted_at bigint NOT NULL,
    PRIMARY KEY(scope, owner_key, ordinal)
);
