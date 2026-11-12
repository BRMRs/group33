package com.group33.server.photo.reconciliation

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Does not acquire the application's write guard, create tables or install migration history. */
class JdbcPhotoSnapshotSource(private val source: DataSource, private val schema: String) : PhotoSnapshotSource {
    init {
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
        require(schema !in setOf("public", "information_schema") && !schema.startsWith("pg_"))
    }
    override fun toString() = "JdbcPhotoSnapshotSource(REDACTED)"

    override fun <T> read(work: (PhotoMetadataSnapshot) -> T): T = source.connection.use { connection ->
        connection.isReadOnly = true
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.queryTimeout = 5
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
                statement.executeQuery().use { row ->
                    check(row.next() && (1..6).none { row.getBoolean(it) })
                }
            }
            connection.prepareStatement("SELECT has_table_privilege(?, 'SELECT'), has_table_privilege(?, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')").use { statement ->
                statement.queryTimeout = 5
                for (table in listOf("medication_photo", "photo_upload_operation")) {
                    val selected = "\"$schema\".$table"
                    statement.setString(1, selected); statement.setString(2, selected)
                    statement.executeQuery().use { row -> check(row.next() && row.getBoolean(1) && !row.getBoolean(2)) }
                }
            }
            work(Snapshot(connection))
        } finally { connection.rollback() }
    }

    private inner class Snapshot(private val connection: Connection) : PhotoMetadataSnapshot {
        override fun protectedOperations(): ProtectedPhotoOperations = connection.createStatement().use { statement ->
            statement.queryTimeout = 5
            statement.executeQuery("""SELECT count(*) FILTER (WHERE state = 'STAGING'),
                count(*) FILTER (WHERE state = 'REJECTED'),
                count(*) FILTER (WHERE state IS NULL OR state NOT IN ('STAGING', 'REJECTED', 'READY'))
                FROM "$schema".photo_upload_operation""").use { row ->
                check(row.next() && row.getLong(3) == 0L)
                ProtectedPhotoOperations(row.getLong(1), row.getLong(2))
            }
        }

        override fun readyAfter(after: UUID?, limit: Int): List<PhotoInspectionKey> {
            require(limit in 1..101)
            val predicate = if (after == null) "" else "WHERE id > ?"
            return connection.prepareStatement("""SELECT id, object_id, byte_count, sha256
                FROM "$schema".medication_photo $predicate ORDER BY id LIMIT ?""").use { statement ->
                statement.queryTimeout = 5
                if (after != null) statement.setObject(1, after)
                statement.setInt(if (after == null) 1 else 2, limit)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) {
                        val id = rows.getObject(1, UUID::class.java)
                        check(id == rows.getObject(2, UUID::class.java))
                        add(PhotoInspectionKey(id, rows.getLong(3), rows.getString(4)))
                    }
                } }
            }
        }
    }
}
