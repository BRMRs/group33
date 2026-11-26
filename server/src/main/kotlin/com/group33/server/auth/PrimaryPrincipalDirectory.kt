package com.group33.server.auth

import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.sql.Connection

internal enum class PrincipalKind { ANONYMOUS, PHONE }

internal class CurrentPrincipal(val user: User, val kind: PrincipalKind) {
    override fun toString() = "CurrentPrincipal(redacted)"
}

/** This lookup distinguishes the two stores and fails closed for any ambiguous ID. */
internal class PrimaryPrincipalDirectory(private val db: Connection) {
    init { require(!db.autoCommit) }

    fun find(id: String): CurrentPrincipal? = db.prepareStatement("""
        SELECT display_name, role, 'ANONYMOUS' AS identity_kind, FALSE AS revoked
        FROM anonymous_identity WHERE id = ?
        UNION ALL
        SELECT display_name, role, 'PHONE' AS identity_kind, revoked_at_ms IS NOT NULL AS revoked
        FROM phone_accounts WHERE user_id = ?
    """.trimIndent()).use { statement ->
        statement.queryTimeout = 5
        statement.setString(1, id)
        statement.setString(2, id)
        statement.executeQuery().use { rows ->
            if (!rows.next() || rows.getBoolean("revoked")) return null
            val user = User(id, rows.getString("display_name"), UserRole.valueOf(rows.getString("role")))
            val kind = PrincipalKind.valueOf(rows.getString("identity_kind"))
            if (rows.next()) null else CurrentPrincipal(user, kind)
        }
    }
}
