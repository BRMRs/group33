package com.group33.server.config

import java.sql.Connection
import javax.sql.DataSource

/** Read-only release gate. Migration execution and its credentials remain in the separate tool. */
internal object RuntimeSchemaHistory {
    internal data class Entry(val version: String, val script: String, val checksum: Int)

    internal fun parse(text: String): List<Entry> {
        val rows = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3)
            val version = fields[0].toInt()
            require(version > 0 && fields[0] == version.toString())
            require(fields[1].matches(Regex("V${version}__[a-z][a-z0-9_]*\\.sql")))
            Entry(fields[0], fields[1], fields[2].toInt())
        }.toList()
        require(rows.isNotEmpty() && rows.size <= 128)
        require(rows.map { it.version } == (1..rows.size).map(Int::toString))
        return rows
    }

    fun validate(source: DataSource, schema: String) {
        try {
            require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
            require(schema != "public" && schema != "information_schema" && !schema.startsWith("pg_"))
            val expected = parse(checkNotNull(javaClass.getResource("/db/runtime-schema-history.tsv")).readText())
            source.connection.use { connection ->
                connection.isReadOnly = true
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = 5
                        statement.maxRows = expected.size + 1
                        val actual = mutableListOf<Entry>()
                        statement.executeQuery("SELECT version, type, script, checksum, success FROM \"$schema\".group33_schema_history ORDER BY installed_rank").use { rows ->
                            while (rows.next()) {
                                check(rows.getString("type") == "SQL" && rows.getBoolean("success") && !rows.wasNull())
                                val checksum = rows.getInt("checksum")
                                check(!rows.wasNull())
                                actual += Entry(rows.getString("version"), rows.getString("script"), checksum)
                            }
                        }
                        check(actual == expected)
                    }
                } finally {
                    connection.rollback()
                }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Versioned schema history does not match this application release")
        }
    }
}
