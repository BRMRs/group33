package com.group33.server.photo.inventory

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Bounded ID batches in one primary read-only snapshot; no application guards, DDL or migrations. */
class JdbcPhotoInventorySource(private val source: DataSource, private val schema: String) : PhotoInventorySource {
    init {
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
        require(schema !in setOf("public", "information_schema") && !schema.startsWith("pg_"))
    }
    override fun toString() = "JdbcPhotoInventorySource(REDACTED)"
    override fun <T> read(work: (PhotoInventorySnapshot) -> T): T = source.connection.use { connection ->
        connection.isReadOnly = true
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
                statement.execute("SET LOCAL search_path = pg_catalog")
                statement.execute("SET LOCAL statement_timeout = '3000ms'")
                statement.execute("SET LOCAL lock_timeout = '1000ms'")
                statement.execute("SET LOCAL idle_in_transaction_session_timeout = '10000ms'")
                statement.executeQuery("SELECT pg_is_in_recovery(), current_setting('transaction_read_only'), current_setting('transaction_isolation')").use { row ->
                    check(row.next() && !row.getBoolean(1) && row.getString(2) == "on" && row.getString(3) == "repeatable read")
                }
            }
            connection.prepareStatement("""SELECT r.rolsuper, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole,
                n.nspowner = r.oid, has_schema_privilege(current_user, n.oid, 'CREATE')
                FROM pg_catalog.pg_roles r JOIN pg_catalog.pg_namespace n ON n.nspname = ?
                WHERE r.rolname = current_user""").use { statement ->
                statement.queryTimeout = 5; statement.setString(1, schema)
                statement.executeQuery().use { row -> check(row.next() && (1..6).none { row.getBoolean(it) }) }
            }
            connection.prepareStatement("""SELECT has_table_privilege(?, 'SELECT'),
                has_table_privilege(?, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),
                has_any_column_privilege(?, 'INSERT,UPDATE,REFERENCES'), row_security_active(?::regclass)""").use { statement ->
                statement.queryTimeout = 5
                for (table in listOf("medication_photo", "photo_upload_operation", "medication")) {
                    val relation = "\"$schema\".$table"
                    for (index in 1..4) statement.setString(index, relation)
                    statement.executeQuery().use { row -> check(row.next() && row.getBoolean(1) && !row.getBoolean(2) && !row.getBoolean(3) && !row.getBoolean(4)) }
                }
            }
            work(Snapshot(connection))
        } finally { connection.rollback() }
    }

    private inner class Snapshot(private val connection: Connection) : PhotoInventorySnapshot {
        override fun references(ids: Set<UUID>): Map<UUID, PhotoReference> {
            require(ids.isNotEmpty() && ids.size <= 100)
            val values = connection.createArrayOf("uuid", ids.toTypedArray())
            try {
                return connection.prepareStatement("""SELECT wanted.id,
                    EXISTS (SELECT 1 FROM "$schema".photo_upload_operation o WHERE o.photo_id = wanted.id
                        AND (o.state IS NULL OR o.state NOT IN ('STAGING','REJECTED','READY'))),
                    EXISTS (SELECT 1 FROM "$schema".photo_upload_operation o WHERE o.photo_id = wanted.id AND o.state = 'STAGING'),
                    EXISTS (SELECT 1 FROM "$schema".photo_upload_operation o WHERE o.photo_id = wanted.id AND o.state = 'REJECTED'),
                    (EXISTS (SELECT 1 FROM "$schema".medication_photo p WHERE p.object_id = wanted.id OR p.id = wanted.id)
                     OR EXISTS (SELECT 1 FROM "$schema".photo_upload_operation o WHERE o.receipt_photo_id = wanted.id)
                     OR EXISTS (SELECT 1 FROM "$schema".medication m WHERE m.photo_id = wanted.id))
                    FROM unnest(?::uuid[]) AS wanted(id)""").use { statement ->
                    statement.queryTimeout = 5; statement.setArray(1, values)
                    statement.executeQuery().use { rows -> buildMap {
                        while (rows.next()) {
                            val id = rows.getObject(1, UUID::class.java)
                            check(id in ids && !containsKey(id) && !rows.getBoolean(2))
                            put(id, when {
                                rows.getBoolean(3) -> PhotoReference.STAGING_PROTECTED
                                rows.getBoolean(4) -> PhotoReference.REJECTED_PROTECTED
                                rows.getBoolean(5) -> PhotoReference.REFERENCED
                                else -> PhotoReference.UNMATCHED
                            })
                        }
                        check(keys == ids)
                    } }
                }
            } finally { values.free() }
        }
    }
}
