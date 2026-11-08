-- Local anonymous-installation persistence prototype. Not phone authentication or an automatic migration.
CREATE TABLE IF NOT EXISTS anonymous_identity (
    id text PRIMARY KEY,
    installation_id text NOT NULL UNIQUE CHECK (length(installation_id) BETWEEN 8 AND 128),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 40),
    role text NOT NULL CHECK (role IN ('ELDER', 'CAREGIVER'))
);
