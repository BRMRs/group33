package com.group33.server.auth

import java.sql.Connection
import javax.sql.DataSource

/** Read-only structural/privilege gate, after the exact managed release-history check. */
internal object BoundPhoneSchemaGate {
    private val columns = linkedMapOf(
        "phone_accounts" to "phone_hash,user_id,role,display_name,revoked_at_ms",
        "phone_account_installations" to "user_id,installation_id,created_at_ms,revoked_at_ms",
        "phone_auth_sessions" to "session_id,user_id,installation_id,profile,auth_version,issued_at_ms,expires_at_ms,revoked_at_ms",
        "phone_auth_attempts" to "id,capability_hash,installation_id,profile,scheme_code,deadline_ms,state,work,lease,lease_until_ms",
        "phone_auth_proofs" to "profile,fingerprint",
        "phone_bound_registration_tickets" to "hash,attempt_id,installation_id,profile,scheme_code,phone_hash,expires_ms",
    )
    private val keys = listOf("phone_accounts" to "phone_hash", "phone_accounts" to "user_id",
        "phone_account_installations" to "user_id,installation_id", "phone_auth_sessions" to "session_id",
        "phone_auth_attempts" to "id", "phone_auth_attempts" to "capability_hash",
        "phone_auth_proofs" to "profile,fingerprint", "phone_bound_registration_tickets" to "hash",
        "phone_bound_registration_tickets" to "attempt_id")

    fun validate(source: DataSource) {
        try {
            source.connection.use { c ->
                c.isReadOnly = true
                c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                c.autoCommit = false
                try {
                    check(count(c, """SELECT count(*) FROM pg_roles WHERE rolname=current_user AND
                        NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)""") == 1L)
                    for ((table, names) in columns) {
                        check(count(c, """SELECT count(*) FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
                            WHERE n.nspname=current_schema() AND t.relname=? AND t.relkind='r'
                            AND NOT t.relrowsecurity AND NOT t.relforcerowsecurity
                            AND NOT pg_has_role(current_user,t.relowner,'MEMBER')
                            AND NOT pg_has_role(current_user,n.nspowner,'MEMBER')
                            AND has_table_privilege(t.oid,'SELECT') AND has_table_privilege(t.oid,'INSERT')
                            AND has_table_privilege(t.oid,'UPDATE')
                            AND NOT EXISTS (
                                SELECT 1 FROM (
                                    SELECT a.grantee FROM aclexplode(COALESCE(t.relacl,acldefault('r',t.relowner))) a
                                    UNION ALL
                                    SELECT a.grantee FROM pg_attribute col CROSS JOIN LATERAL aclexplode(col.attacl) a
                                        WHERE col.attrelid=t.oid AND col.attnum>0 AND NOT col.attisdropped
                                ) granted WHERE CASE WHEN granted.grantee=0 THEN true ELSE EXISTS (
                                    SELECT 1 FROM pg_roles client WHERE client.rolname IN ('anon','authenticated')
                                        AND pg_has_role(client.oid,granted.grantee,'MEMBER')) END)""", table) == 1L)
                        if (table == "phone_bound_registration_tickets")
                            check(count(c, "SELECT CASE WHEN has_table_privilege(?, 'DELETE') THEN 1 ELSE 0 END", table) == 1L)
                        c.createStatement().use { s -> s.queryTimeout = 5; s.executeQuery("SELECT $names FROM $table LIMIT 0").close() }
                    }
                    for ((table, names) in keys) {
                        check(count(c, """SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid
                            JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_index i ON i.indexrelid=k.conindid
                            WHERE n.nspname=current_schema() AND t.relname=? AND k.contype IN ('p','u')
                            AND k.convalidated AND NOT k.condeferrable AND i.indisvalid AND i.indisready
                            AND i.indpred IS NULL AND i.indexprs IS NULL AND
                            (SELECT string_agg(a.attname,',' ORDER BY v.ord) FROM unnest(k.conkey) WITH ORDINALITY v(num,ord)
                                JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=v.num)=?""", table, names) > 0)
                    }
                    for (key in listOf(
                        Reference("phone_account_installations", "user_id", "phone_accounts", "user_id"),
                        Reference("phone_auth_sessions", "user_id,installation_id", "phone_account_installations", "user_id,installation_id"),
                        Reference("phone_bound_registration_tickets", "attempt_id", "phone_auth_attempts", "id"))) {
                        check(count(c, """SELECT count(*) FROM pg_constraint k JOIN pg_class t ON t.oid=k.conrelid
                            JOIN pg_class p ON p.oid=k.confrelid JOIN pg_namespace n ON n.oid=t.relnamespace
                            WHERE n.nspname=current_schema() AND p.relnamespace=t.relnamespace
                            AND t.relname=? AND p.relname=? AND k.contype='f' AND k.convalidated
                            AND NOT k.condeferrable AND
                            (SELECT string_agg(a.attname,',' ORDER BY v.ord) FROM unnest(k.conkey) WITH ORDINALITY v(num,ord)
                                JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=v.num)=? AND
                            (SELECT string_agg(a.attname,',' ORDER BY v.ord) FROM unnest(k.confkey) WITH ORDINALITY v(num,ord)
                                JOIN pg_attribute a ON a.attrelid=p.oid AND a.attnum=v.num)=?""",
                            key.table, key.parent, key.columns, key.parentColumns) == 1L)
                    }
                } finally { c.rollback() }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Bound phone schema or runtime privileges do not match this release")
        }
    }

    private data class Reference(val table: String, val columns: String, val parent: String, val parentColumns: String)

    private fun count(c: Connection, sql: String, vararg values: String): Long = c.prepareStatement(sql).use { s ->
        s.queryTimeout = 5
        values.forEachIndexed { i, value -> s.setString(i + 1, value) }
        s.executeQuery().use { r -> check(r.next()); r.getLong(1) }
    }
}
