-- Local prototype only: apply explicitly in an isolated, trusted schema.
CREATE TABLE IF NOT EXISTS pairing_issuance_guard (
    id integer PRIMARY KEY CHECK (id = 1)
);
INSERT INTO pairing_issuance_guard VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS pairing_active_code (
    elder_id text PRIMARY KEY,
    code_id text NOT NULL UNIQUE,
    code_hash text NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    expires_at bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS pairing_effective_hash ON pairing_active_code(code_hash, expires_at);

CREATE TABLE IF NOT EXISTS pairing_issue_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    target_elder_id text NOT NULL,
    fingerprint text NOT NULL,
    receipt_id text,
    receipt_cipher bytea,
    receipt_expiry bigint,
    rejection_reason text,
    retry_after bigint,
    PRIMARY KEY(actor_id, operation_id),
    CHECK ((receipt_id IS NULL AND receipt_cipher IS NULL AND receipt_expiry IS NULL)
        OR (receipt_id IS NOT NULL AND receipt_cipher IS NOT NULL AND receipt_expiry IS NOT NULL)),
    CHECK (receipt_cipher IS NULL OR octet_length(receipt_cipher) = 37),
    CHECK (rejection_reason IS NULL OR (receipt_id IS NULL AND rejection_reason IN (
        'ROLE_FORBIDDEN', 'IDEMPOTENCY_KEY_REUSED', 'RATE_LIMITED',
        'OPERATION_IN_PROGRESS', 'CODE_GENERATION_UNAVAILABLE'))),
    CHECK (retry_after IS NULL OR (rejection_reason IS NOT NULL AND retry_after > 0))
);

CREATE TABLE IF NOT EXISTS pairing_issuance_time (
    elder_id text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    issued_at bigint NOT NULL,
    PRIMARY KEY(elder_id, ordinal)
);
