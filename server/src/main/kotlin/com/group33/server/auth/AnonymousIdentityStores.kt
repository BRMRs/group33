package com.group33.server.auth

import com.group33.server.web.ApiException
import com.group33.shared.model.FieldError
import com.group33.shared.model.UserRole
import org.springframework.http.HttpStatus
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

internal fun roleConflict(): Nothing = throw ApiException(HttpStatus.CONFLICT, "INSTALLATION_ROLE_CONFLICT",
    "An installation cannot change roles", listOf(FieldError("role", "ROLE_FIXED")))

internal class InMemoryAnonymousIdentityStore : AnonymousIdentityStore {
    private val users = ConcurrentHashMap<String, AnonymousUser>()
    override fun findById(id: String) = users.values.firstOrNull { it.id == id }
    override fun getOrCreate(installationId: String, displayName: String, role: UserRole): AnonymousUser = users.compute(installationId) { _, old ->
        if (old != null && old.role != role) roleConflict()
        old?.copy(displayName = displayName) ?: AnonymousUser(UUID.randomUUID().toString(), installationId, displayName, role)
    }!!
}

/** No secrets in exceptions. Unique installation binding and role conflict are resolved inside PostgreSQL. */
internal class PostgresAnonymousIdentityStore(private val source: DataSource) : AnonymousIdentityStore {
    override fun findById(id: String): AnonymousUser? = try {
        source.connection.use { c -> c.prepareStatement("SELECT * FROM anonymous_identity WHERE id = ?").use {
            it.queryTimeout = 5
            it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
        } }
    } catch (_: SQLException) { throw IdentityStoreUnavailable() }

    override fun getOrCreate(installationId: String, displayName: String, role: UserRole): AnonymousUser = try {
        source.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use {
                    it.execute("SET LOCAL lock_timeout = '1000ms'")
                    it.execute("SET LOCAL statement_timeout = '5000ms'")
                }
                val result = c.prepareStatement("""
                    INSERT INTO anonymous_identity AS current (id, installation_id, display_name, role)
                    VALUES (?, ?, ?, ?) ON CONFLICT (installation_id) DO UPDATE SET display_name = EXCLUDED.display_name
                    WHERE current.role = EXCLUDED.role RETURNING id, installation_id, display_name, role
                """.trimIndent()).use {
                    it.setString(1, UUID.randomUUID().toString()); it.setString(2, installationId)
                    it.setString(3, displayName); it.setString(4, role.name)
                    it.executeQuery().use { rows -> if (rows.next()) read(rows) else roleConflict() }
                }
                c.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                c.commit(); result
            } catch (failure: Throwable) {
                try { c.rollback() } catch (_: SQLException) { /* retain only the private original failure */ }
                throw failure
            }
        }
    } catch (_: SQLException) { throw IdentityStoreUnavailable() }

    private fun read(row: ResultSet) = AnonymousUser(row.getString("id"), row.getString("installation_id"),
        row.getString("display_name"), UserRole.valueOf(row.getString("role")))
}
