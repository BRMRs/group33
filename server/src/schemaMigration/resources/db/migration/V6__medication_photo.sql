-- Local additive prototype after v1-v5. Apply explicitly in an isolated, trusted schema.
-- Only READY metadata is published here. Object IO precedes the atomic metadata and receipt transaction.
CREATE TABLE IF NOT EXISTS medication_photo (
    id uuid PRIMARY KEY,
    elder_id text NOT NULL,
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    created_at bigint NOT NULL,
    object_id uuid NOT NULL UNIQUE CHECK (object_id = id),
    byte_count bigint NOT NULL CHECK (byte_count BETWEEN 1 AND 5242880),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    UNIQUE (id, elder_id, content_type, created_at)
);
CREATE INDEX IF NOT EXISTS medication_photo_elder ON medication_photo(elder_id, created_at, id);

-- STAGING reservations and rejected attempts retain one stable identity across recovery and retry.
-- Four immutable receipt columns preserve the original DTO without reading current resource state.
CREATE TABLE IF NOT EXISTS photo_upload_operation (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    elder_id text NOT NULL,
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    photo_id uuid NOT NULL UNIQUE,
    state text NOT NULL,
    receipt_photo_id uuid,
    receipt_elder_id text,
    receipt_content_type text,
    receipt_created_at bigint,
    rejection_reason text,
    PRIMARY KEY (actor_id, operation_id),
    FOREIGN KEY (receipt_photo_id, receipt_elder_id, receipt_content_type, receipt_created_at)
        REFERENCES medication_photo(id, elder_id, content_type, created_at),
    CHECK ((
        (state = 'STAGING' AND receipt_photo_id IS NULL AND receipt_elder_id IS NULL
            AND receipt_content_type IS NULL AND receipt_created_at IS NULL AND rejection_reason IS NULL)
        OR (state = 'REJECTED' AND receipt_photo_id IS NULL AND receipt_elder_id IS NULL
            AND receipt_content_type IS NULL AND receipt_created_at IS NULL
            AND rejection_reason IN ('TOO_MANY_BYTES', 'UNSUPPORTED_TYPE', 'INVALID_IMAGE', 'TOO_MANY_PIXELS'))
        OR (state = 'READY' AND receipt_photo_id = photo_id AND receipt_elder_id = elder_id
            AND receipt_content_type IS NOT NULL AND receipt_created_at IS NOT NULL AND rejection_reason IS NULL)
    ) IS TRUE)
);
