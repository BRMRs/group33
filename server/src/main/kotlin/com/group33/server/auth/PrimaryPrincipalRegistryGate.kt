package com.group33.server.auth

import java.sql.Connection
import javax.sql.DataSource

/** The registry is a private integrity target, not a replacement for current account/session checks. */
internal object PrimaryPrincipalRegistryGate {
    fun validate(source: DataSource) {
        try {
            source.connection.use { c ->
                c.isReadOnly = true
                c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                c.autoCommit = false
                try {
                    check(count(c, """SELECT count(*) FROM pg_roles implicit_role WHERE implicit_role.rolname IN
                        ('pg_read_all_data','pg_write_all_data','pg_maintain')
                        AND pg_has_role(current_user,implicit_role.oid,'MEMBER')""") == 0L)
                    check(count(c, """SELECT count(*) FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
                        WHERE n.nspname=current_schema() AND t.relname='server_principal' AND t.relkind='r'
                        AND NOT t.relrowsecurity AND NOT t.relforcerowsecurity
                        AND NOT pg_has_role(current_user,t.relowner,'MEMBER')
                        AND NOT pg_has_role(current_user,n.nspowner,'MEMBER')
                        AND has_table_privilege(t.oid,'SELECT')
                        AND NOT has_table_privilege(t.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                        AND NOT has_any_column_privilege(t.oid,'INSERT,UPDATE,REFERENCES')
                        AND NOT EXISTS (SELECT 1 FROM aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a
                            WHERE a.grantee=0 OR EXISTS (SELECT 1 FROM pg_roles client WHERE client.rolname IN ('anon','authenticated')
                                AND pg_has_role(client.oid,a.grantee,'MEMBER')))
                        AND NOT EXISTS (SELECT 1 FROM pg_attribute col CROSS JOIN LATERAL aclexplode(col.attacl) a
                            WHERE col.attrelid=t.oid AND (a.grantee=0 OR EXISTS (SELECT 1 FROM pg_roles client
                                WHERE client.rolname IN ('anon','authenticated') AND pg_has_role(client.oid,a.grantee,'MEMBER'))))""") == 1L)
                    if (count(c, "SELECT current_setting('server_version_num')::bigint") >= 170000)
                        check(count(c, "SELECT CASE WHEN has_table_privilege('server_principal','MAINTAIN') THEN 1 ELSE 0 END") == 0L)
                    check(count(c, """SELECT count(*) FROM pg_proc f JOIN pg_namespace n ON n.oid=f.pronamespace
                        JOIN pg_class t ON t.relnamespace=n.oid AND t.relname='server_principal'
                        WHERE n.nspname=current_schema() AND f.proname='server_principal_sync' AND f.prorettype='trigger'::regtype
                        AND f.pronargs=0 AND f.prosecdef AND f.proowner=t.relowner
                        AND f.proconfig=ARRAY['search_path=pg_catalog, pg_temp']::text[]
                        AND NOT has_function_privilege(f.oid,'EXECUTE')
                        AND NOT EXISTS (SELECT 1 FROM aclexplode(COALESCE(f.proacl,acldefault('f',f.proowner))) a
                            WHERE a.grantee<>f.proowner)""") == 1L)
                    for ((root, id) in listOf("anonymous_identity" to "id", "phone_accounts" to "user_id")) {
                        check(count(c, """SELECT count(*) FROM pg_trigger g JOIN pg_class t ON t.oid=g.tgrelid
                            JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_proc f ON f.oid=g.tgfoid
                            WHERE n.nspname=current_schema() AND t.relname=? AND g.tgname='server_principal_sync'
                            AND NOT g.tgisinternal AND g.tgenabled='O' AND g.tgtype=29 AND g.tgnargs=0
                            AND f.pronamespace=n.oid AND f.proname='server_principal_sync'
                            AND (SELECT string_agg(a.attname,',' ORDER BY v.ord) FROM unnest(g.tgattr::smallint[]) WITH ORDINALITY v(num,ord)
                                JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=v.num)=?""", root, "$id,role") == 1L)
                    }
                    check(count(c, """SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid
                        JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_class p ON p.oid=k.confrelid
                        WHERE n.nspname=current_schema() AND t.relname='dose_group_operation' AND k.contype='f'
                        AND p.relnamespace=n.oid AND p.relname='server_principal' AND k.convalidated AND NOT k.condeferrable
                        AND k.confdeltype='a' AND k.confupdtype='a'
                        AND (SELECT array_agg(a.attname::text ORDER BY v.ord) FROM unnest(k.conkey) WITH ORDINALITY v(num,ord)
                            JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=v.num)=ARRAY['elder_id']::text[]
                        AND (SELECT array_agg(a.attname::text ORDER BY v.ord) FROM unnest(k.confkey) WITH ORDINALITY v(num,ord)
                            JOIN pg_attribute a ON a.attrelid=p.oid AND a.attnum=v.num)=ARRAY['id']::text[]""") == 1L)
                    check(count(c, """WITH roots AS (SELECT id,'ANONYMOUS'::text AS kind,role FROM anonymous_identity
                        UNION ALL SELECT user_id,'PHONE'::text,role FROM phone_accounts), differences AS (
                        (SELECT id,kind,role FROM server_principal EXCEPT ALL SELECT * FROM roots)
                        UNION ALL (SELECT * FROM roots EXCEPT ALL SELECT id,kind,role FROM server_principal))
                        SELECT count(*) FROM differences""") == 0L)
                } finally { c.rollback() }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Primary principal registry or private privileges do not match this release")
        }
    }
    private fun count(c: Connection, sql: String, vararg values: String): Long = c.prepareStatement(sql).use { s ->
        s.queryTimeout = 5
        values.forEachIndexed { i, value -> s.setString(i + 1, value) }
        s.executeQuery().use { r -> check(r.next()); r.getLong(1) }
    }
}
