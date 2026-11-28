-- Forward-only phone account/installation/session and original-attempt persistence.
-- Applied V1-V10 and reserved index V11 remain immutable. No old trial/prototype adoption.
DO $phone_adoption_guard$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname=current_schema() AND c.relname ~ '^(phone_|fusion_)') THEN
        RAISE EXCEPTION 'Unmanaged phone objects require a reviewed adoption or restore plan';
    END IF;
END $phone_adoption_guard$;

CREATE TABLE phone_accounts (
    phone_hash VARCHAR(64) PRIMARY KEY CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    user_id VARCHAR(36) NOT NULL UNIQUE CHECK (user_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    role VARCHAR(16) NOT NULL CHECK (role IN ('ELDER', 'CAREGIVER')),
    display_name VARCHAR(40) NOT NULL CHECK (length(display_name) BETWEEN 1 AND 40),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0)
);
CREATE TABLE phone_account_installations (
    user_id VARCHAR(36) NOT NULL REFERENCES phone_accounts(user_id),
    installation_id VARCHAR(36) NOT NULL CHECK (installation_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    created_at_ms BIGINT NOT NULL CHECK (created_at_ms >= 0),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0),
    PRIMARY KEY (user_id, installation_id)
);
CREATE TABLE phone_auth_sessions (
    session_id VARCHAR(36) PRIMARY KEY CHECK (session_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    user_id VARCHAR(36) NOT NULL,
    installation_id VARCHAR(36) NOT NULL,
    profile VARCHAR(128) NOT NULL CHECK (length(profile) > 0),
    auth_version INTEGER NOT NULL CHECK (auth_version=1),
    issued_at_ms BIGINT NOT NULL CHECK (issued_at_ms >= 0),
    expires_at_ms BIGINT NOT NULL CHECK (expires_at_ms > issued_at_ms),
    revoked_at_ms BIGINT CHECK (revoked_at_ms >= 0),
    FOREIGN KEY (user_id, installation_id) REFERENCES phone_account_installations(user_id, installation_id)
);
CREATE TABLE phone_auth_attempts (
    id UUID PRIMARY KEY,
    capability_hash CHAR(64) NOT NULL UNIQUE CHECK (capability_hash ~ '^[0-9a-f]{64}$'),
    installation_id UUID NOT NULL,
    profile VARCHAR(128) NOT NULL CHECK (length(profile) > 0),
    scheme_code TEXT NOT NULL CHECK (length(scheme_code) BETWEEN 1 AND 512),
    deadline_ms BIGINT NOT NULL CHECK (deadline_ms >= 0),
    state VARCHAR(12) NOT NULL CHECK (state IN ('PENDING','CLOSED','CONSUMED')),
    work VARCHAR(6) CHECK (work IN ('GRANT','VERIFY')),
    lease UUID,
    lease_until_ms BIGINT CHECK (lease_until_ms >= 0),
    CHECK ((lease IS NULL)=(work IS NULL) AND (lease IS NULL)=(lease_until_ms IS NULL)),
    CHECK (state='PENDING' OR lease IS NULL)
);
CREATE TABLE phone_auth_proofs (
    profile VARCHAR(128) NOT NULL CHECK (length(profile) > 0),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (profile,fingerprint)
);
CREATE TABLE phone_bound_registration_tickets (
    hash CHAR(64) PRIMARY KEY CHECK (hash ~ '^[0-9a-f]{64}$'),
    attempt_id UUID NOT NULL UNIQUE REFERENCES phone_auth_attempts(id),
    installation_id UUID NOT NULL,
    profile VARCHAR(128) NOT NULL CHECK (length(profile) > 0),
    scheme_code TEXT NOT NULL CHECK (length(scheme_code) BETWEEN 1 AND 512),
    phone_hash CHAR(64) NOT NULL CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    expires_ms BIGINT NOT NULL CHECK (expires_ms >= 0)
);
CREATE INDEX phone_auth_sessions_account_installation ON phone_auth_sessions(user_id,installation_id);

-- Private backend schema only; no PostgREST/client-role access. Runtime grants are separate
-- administrator provisioning, never guessed from current_user or granted by application startup.
REVOKE ALL ON TABLE phone_accounts,phone_account_installations,phone_auth_sessions,
    phone_auth_attempts,phone_auth_proofs,phone_bound_registration_tickets FROM PUBLIC;
DO $phone_client_grants$
DECLARE client_role TEXT; object_name TEXT;
BEGIN
    FOREACH client_role IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=client_role) THEN
            FOREACH object_name IN ARRAY ARRAY['phone_accounts','phone_account_installations','phone_auth_sessions',
                'phone_auth_attempts','phone_auth_proofs','phone_bound_registration_tickets'] LOOP
                EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM %I',current_schema(),object_name,client_role);
            END LOOP;
        END IF;
    END LOOP;
END $phone_client_grants$;
