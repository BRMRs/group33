-- Local additive prototype after v1/v2/v3. No FK to deleted active-code rows.
CREATE TABLE IF NOT EXISTS pairing_code_deletion (
    actor_id text NOT NULL,
    operation_id uuid NOT NULL,
    installation_id text NOT NULL,
    fingerprint text NOT NULL,
    code_id text NOT NULL,
    PRIMARY KEY (actor_id, operation_id)
);
