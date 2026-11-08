package com.group33.server.auth

import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Separate phone accounts; anonymous installation identities are never silently linked. */
@Repository
class PhoneAccountStore(
    @Value("\${app.fusion.database-url:}") private val url: String,
    @Value("\${app.fusion.database-user:}") private val username: String,
    @Value("\${app.fusion.database-password:}") private val password: String,
    @Value("\${app.fusion.phone-hash-key:}") private val hashKey: String,
) {
    private val random = SecureRandom()
    @Volatile private var initialized = false

    fun findByPhone(phone: String): User? = connect().use { connection ->
        lookup(connection, hashPhone(phone))
    }

    fun newRegistrationTicket(phone: String): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        connect().use { connection ->
            connection.prepareStatement("DELETE FROM phone_registration_tickets WHERE expires_at_ms < ?").use {
                it.setLong(1, System.currentTimeMillis())
                it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO phone_registration_tickets(ticket_hash, phone_hash, expires_at_ms) VALUES (?, ?, ?)",
            ).use {
                it.setString(1, digest(ticket))
                it.setString(2, hashPhone(phone))
                it.setLong(3, System.currentTimeMillis() + 5 * 60_000L)
                it.executeUpdate()
            }
        }
        return ticket
    }

    /** Ticket consumption and first-role creation are atomic across server instances. */
    fun register(ticket: String, role: UserRole, displayName: String?): User? {
        if (ticket.length !in 32..128) return null
        val name = if (role == UserRole.ELDER) "Elder" else displayName?.trim().orEmpty()
        if (name.length !in 1..40) return null
        return connect().use { connection ->
            connection.autoCommit = false
            try {
                val phoneHash = connection.prepareStatement(
                    "SELECT phone_hash, expires_at_ms FROM phone_registration_tickets WHERE ticket_hash = ? FOR UPDATE",
                ).use { statement ->
                    statement.setString(1, digest(ticket))
                    statement.executeQuery().use { rows ->
                        if (rows.next() && rows.getLong(2) > System.currentTimeMillis()) rows.getString(1) else null
                    }
                }
                if (phoneHash == null) {
                    connection.rollback()
                    return@use null
                }
                connection.prepareStatement("DELETE FROM phone_registration_tickets WHERE ticket_hash = ?").use {
                    it.setString(1, digest(ticket))
                    it.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO phone_accounts(phone_hash, user_id, role, display_name) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT DO NOTHING",
                ).use {
                    it.setString(1, phoneHash)
                    it.setString(2, UUID.randomUUID().toString())
                    it.setString(3, role.name)
                    it.setString(4, name)
                    it.executeUpdate()
                }
                val account = requireNotNull(lookup(connection, phoneHash))
                connection.commit()
                account
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun lookup(connection: Connection, phoneHash: String): User? = connection.prepareStatement(
        "SELECT user_id, display_name, role FROM phone_accounts WHERE phone_hash = ?",
    ).use { statement ->
        statement.setString(1, phoneHash)
        statement.executeQuery().use { rows ->
            if (rows.next()) User(rows.getString(1), rows.getString(2), UserRole.valueOf(rows.getString(3))) else null
        }
    }

    private fun connect(): Connection {
        if (url.isBlank() || hashKey.toByteArray(StandardCharsets.UTF_8).size < 32) {
            throw FusionProviderException()
        }
        val connection = DriverManager.getConnection(url, username, password)
        try {
            if (!initialized) synchronized(this) {
                if (!initialized) {
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS phone_accounts (phone_hash VARCHAR(64) PRIMARY KEY, " +
                                "user_id VARCHAR(36) NOT NULL UNIQUE, role VARCHAR(16) NOT NULL, display_name VARCHAR(40) NOT NULL)",
                        )
                        statement.executeUpdate(
                            "CREATE TABLE IF NOT EXISTS phone_registration_tickets (ticket_hash VARCHAR(64) PRIMARY KEY, " +
                                "phone_hash VARCHAR(64) NOT NULL, expires_at_ms BIGINT NOT NULL)",
                        )
                    }
                    initialized = true
                }
            }
            return connection
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    private fun hashPhone(phone: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(hashKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        doFinal(phone.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
