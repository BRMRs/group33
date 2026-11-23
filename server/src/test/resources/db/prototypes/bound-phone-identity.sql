-- Isolated PS1-A integration fixture, NOT a numbered/production migration.
-- Runtime must not execute this DDL. Integration/versioned conversion is separate.
CREATE TABLE phone_accounts (
    phone_hash VARCHAR(64) PRIMARY KEY CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    user_id VARCHAR(36) NOT NULL UNIQUE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('ELDER', 'CAREGIVER')),
    display_name VARCHAR(40) NOT NULL CHECK (length(display_name) BETWEEN 1 AND 40),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0)
);
CREATE TABLE phone_account_installations (
    user_id VARCHAR(36) NOT NULL REFERENCES phone_accounts(user_id),
    installation_id VARCHAR(36) NOT NULL,
    created_at_ms BIGINT NOT NULL CHECK (created_at_ms >= 0),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0),
    PRIMARY KEY (user_id, installation_id)
);
CREATE TABLE phone_auth_sessions (
    session_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    installation_id VARCHAR(36) NOT NULL,
    profile VARCHAR(128) NOT NULL CHECK (length(profile) > 0),
    auth_version INTEGER NOT NULL CHECK (auth_version = 1),
    issued_at_ms BIGINT NOT NULL CHECK (issued_at_ms >= 0),
    expires_at_ms BIGINT NOT NULL CHECK (expires_at_ms > issued_at_ms),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0),
    FOREIGN KEY (user_id, installation_id)
        REFERENCES phone_account_installations(user_id, installation_id)
);
