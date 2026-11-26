-- Stable account IDs from separate namespaces; never copy phone users into anonymous_identity.
-- Stop writes / use the coordinated application release during administrative adoption.
SET LOCAL lock_timeout='5s';
LOCK TABLE anonymous_identity, phone_accounts IN SHARE ROW EXCLUSIVE MODE;
DO $principal_collision$
BEGIN
    IF EXISTS (SELECT 1 FROM anonymous_identity a JOIN phone_accounts p ON p.user_id=a.id) THEN
        RAISE EXCEPTION 'Ambiguous principal IDs require a reviewed recovery decision';
    END IF;
END $principal_collision$;

CREATE TABLE server_principal (
    id text PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('ANONYMOUS','PHONE')),
    role text NOT NULL CHECK (role IN ('ELDER','CAREGIVER'))
);
INSERT INTO server_principal(id,kind,role) SELECT id,'ANONYMOUS',role FROM anonymous_identity;
INSERT INTO server_principal(id,kind,role) SELECT user_id,'PHONE',role FROM phone_accounts;

-- The app can read this registry, never populate/repair it directly. The migration owner
-- maintains it through fixed root-table triggers; all names are schema-qualified and values bound.
CREATE FUNCTION server_principal_sync() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,pg_temp AS $principal_sync$
DECLARE
    principal_kind text;
    principal_id text;
    previous_id text;
BEGIN
    IF TG_TABLE_NAME='anonymous_identity' THEN
        principal_kind := 'ANONYMOUS';
        IF TG_OP<>'DELETE' THEN principal_id := NEW.id; END IF;
        IF TG_OP<>'INSERT' THEN previous_id := OLD.id; END IF;
    ELSIF TG_TABLE_NAME='phone_accounts' THEN
        principal_kind := 'PHONE';
        IF TG_OP<>'DELETE' THEN principal_id := NEW.user_id; END IF;
        IF TG_OP<>'INSERT' THEN previous_id := OLD.user_id; END IF;
    ELSE
        RAISE EXCEPTION 'Unexpected principal source';
    END IF;
    IF TG_OP='INSERT' THEN
        EXECUTE format('INSERT INTO %I.server_principal(id,kind,role) VALUES ($1,$2,$3)',TG_TABLE_SCHEMA)
            USING principal_id,principal_kind,NEW.role;
        RETURN NEW;
    ELSIF TG_OP='UPDATE' THEN
        IF principal_id IS DISTINCT FROM previous_id OR NEW.role IS DISTINCT FROM OLD.role THEN
            RAISE EXCEPTION 'Principal identity and role are immutable';
        END IF;
        RETURN NEW;
    ELSE
        -- The dose FK below preserves the old refusal to delete a principal with medical receipts.
        EXECUTE format('DELETE FROM %I.server_principal WHERE id=$1 AND kind=$2',TG_TABLE_SCHEMA)
            USING previous_id,principal_kind;
        RETURN OLD;
    END IF;
END $principal_sync$;
REVOKE ALL ON FUNCTION server_principal_sync() FROM PUBLIC;

CREATE TRIGGER server_principal_sync AFTER INSERT OR DELETE OR UPDATE OF id,role ON anonymous_identity
    FOR EACH ROW EXECUTE FUNCTION server_principal_sync();
CREATE TRIGGER server_principal_sync AFTER INSERT OR DELETE OR UPDATE OF user_id,role ON phone_accounts
    FOR EACH ROW EXECUTE FUNCTION server_principal_sync();

-- Preserve old receipt rows and IDs; only replace the anonymous-only reference target.
ALTER TABLE dose_group_operation DROP CONSTRAINT dose_group_operation_elder_id_fkey;
ALTER TABLE dose_group_operation ADD CONSTRAINT dose_group_operation_principal
    FOREIGN KEY (elder_id) REFERENCES server_principal(id);

REVOKE ALL ON TABLE server_principal FROM PUBLIC;
DO $principal_clients$
DECLARE client_role text;
BEGIN
    FOREACH client_role IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=client_role) THEN
            EXECUTE format('REVOKE ALL ON TABLE %I.server_principal FROM %I',current_schema(),client_role);
            EXECUTE format('REVOKE ALL ON FUNCTION %I.server_principal_sync() FROM %I',current_schema(),client_role);
        END IF;
    END LOOP;
END $principal_clients$;
