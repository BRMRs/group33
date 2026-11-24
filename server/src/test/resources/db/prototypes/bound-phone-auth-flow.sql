-- Isolated integration fixture. Production prerequisites require reviewed forward migration.
CREATE TABLE phone_auth_attempts (
    id UUID PRIMARY KEY,
    capability_hash CHAR(64) NOT NULL UNIQUE,
    installation_id UUID NOT NULL,
    profile VARCHAR(128) NOT NULL,
    deadline_ms BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL CHECK (state IN ('PENDING', 'CLOSED', 'CONSUMED')),
    work VARCHAR(6) CHECK (work IN ('GRANT', 'VERIFY')),
    lease UUID,
    lease_until_ms BIGINT,
    CHECK ((lease IS NULL) = (work IS NULL) AND (lease IS NULL) = (lease_until_ms IS NULL)),
    CHECK (state = 'PENDING' OR lease IS NULL)
);
CREATE TABLE phone_auth_proofs (
    profile VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY (profile, fingerprint)
);
CREATE TABLE phone_bound_registration_tickets (
    hash CHAR(64) PRIMARY KEY,
    attempt_id UUID NOT NULL UNIQUE REFERENCES phone_auth_attempts(id),
    installation_id UUID NOT NULL,
    profile VARCHAR(128) NOT NULL,
    phone_hash CHAR(64) NOT NULL,
    expires_ms BIGINT NOT NULL
);
