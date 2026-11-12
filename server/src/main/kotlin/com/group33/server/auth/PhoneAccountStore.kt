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
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.postgresql.ds.PGSimpleDataSource

/** Separate phone accounts; anonymous installation identities are never silently linked. */
@Repository
class PhoneAccountStore(
    @Value("\${app.fusion.database-url:}") private val url: String,
    @Value("\${app.fusion.database-user:}") private val username: String,
    @Value("\${app.fusion.database-password:}") private val password: String,
    @Value("\${app.fusion.phone-hash-key:}") private val hashKey: String,
) {
    private val random = SecureRandom()
    private val initializationLock = ReentrantLock()
    @Volatile private var initialized = false

    fun findByPhone(phone: String): User? = storageOperation { connect().use { connection ->
        lookup(connection, hashPhone(phone))
    } }

    /** Current-account reads never initialize tables or consume registration tickets. */
    fun findByUserId(userId: String): User? {
        if (runCatching { UUID.fromString(userId).toString() }.getOrNull() != userId) return null
        val connection = openConnection("group33-phone-account-read")
        return connection.use { c ->
            c.isReadOnly = true
            c.autoCommit = false
            try {
                c.accountStatement("SELECT user_id, display_name, role FROM phone_accounts WHERE user_id = ?").use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) User(rows.getString(1), rows.getString(2), UserRole.valueOf(rows.getString(3))) else null
                    }
                }
            } finally { c.rollback() }
        }
    }

    fun newRegistrationTicket(phone: String): String = storageOperation {
        val bytes = ByteArray(32).also(random::nextBytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        connect().use { connection ->
            connection.accountStatement("DELETE FROM phone_registration_tickets WHERE expires_at_ms < ?").use {
                it.setLong(1, System.currentTimeMillis())
                it.executeUpdate()
            }
            connection.accountStatement(
                "INSERT INTO phone_registration_tickets(ticket_hash, phone_hash, expires_at_ms) VALUES (?, ?, ?)",
            ).use {
                it.setString(1, digest(ticket))
                it.setString(2, hashPhone(phone))
                it.setLong(3, System.currentTimeMillis() + 5 * 60_000L)
                it.executeUpdate()
            }
        }
        ticket
    }

    /** Ticket consumption and first-role creation are atomic across server instances. */
    fun register(ticket: String, role: UserRole, displayName: String?): User? {
        if (ticket.length !in 32..128) return null
        val name = if (role == UserRole.ELDER) "Elder" else displayName?.trim().orEmpty()
        if (name.length !in 1..40) return null
        return storageOperation { connect().use { connection ->
            connection.autoCommit = false
            try {
                val phoneHash = connection.accountStatement(
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
                connection.accountStatement("DELETE FROM phone_registration_tickets WHERE ticket_hash = ?").use {
                    it.setString(1, digest(ticket))
                    it.executeUpdate()
                }
                connection.accountStatement(
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
        } }
    }

    private fun lookup(connection: Connection, phoneHash: String): User? = connection.accountStatement(
        "SELECT user_id, display_name, role FROM phone_accounts WHERE phone_hash = ?",
    ).use { statement ->
        statement.setString(1, phoneHash)
        statement.executeQuery().use { rows ->
            if (rows.next()) User(rows.getString(1), rows.getString(2), UserRole.valueOf(rows.getString(3))) else null
        }
    }

    private fun connect(): Connection {
        val connection = openConnection("group33-phone-account-operation")
        try {
            if (!initialized) {
                val locked = try { initializationLock.tryLock(5, TimeUnit.SECONDS) }
                catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw PhoneAccountStorageUnavailable()
                }
                if (!locked) throw PhoneAccountStorageUnavailable()
                try {
                    if (!initialized) {
                        // Retained trial-only initialization; formal migration adoption remains separate.
                        connection.createStatement().use { statement ->
                            statement.queryTimeout = 5
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
                } finally { initializationLock.unlock() }
            }
            return connection
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    private fun openConnection(application: String): Connection {
        if (url.isBlank() || hashKey.toByteArray(StandardCharsets.UTF_8).size < 32) {
            throw FusionProviderException()
        }
        return when {
            url.startsWith("jdbc:postgresql:") -> PGSimpleDataSource().apply {
                setURL(this@PhoneAccountStore.url); user = username; this.password = this@PhoneAccountStore.password
                connectTimeout = 5; socketTimeout = 10; applicationName = application
            }.connection
            url.startsWith("jdbc:h2:") -> DriverManager.getConnection(url, username, password)
            else -> throw FusionProviderException()
        }
    }

    private fun Connection.accountStatement(sql: String): PreparedStatement = prepareStatement(sql).apply { queryTimeout = 5 }

    private inline fun <T> storageOperation(block: () -> T): T = try { block() }
    catch (_: SQLException) { throw PhoneAccountStorageUnavailable() }
    catch (_: FusionProviderException) { throw PhoneAccountStorageUnavailable() }

    private fun hashPhone(phone: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(hashKey.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        doFinal(phone.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
