package com.group33.server.auth

import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Internal persistence seam only. No Boot bean, route, provider call or runtime DDL. */
internal class BoundPhoneIdentityStore(private val source: DataSource) {
    fun <T> transaction(work: (BoundPhoneIdentityTransaction) -> T): T =
        primaryTransaction { work(BoundPhoneIdentityTransaction(it)) }

    fun <T> primaryTransaction(work: (Connection) -> T): T = try {
        source.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                connection.createStatement().use {
                    it.execute("SET LOCAL lock_timeout = '1000ms'")
                    it.execute("SET LOCAL statement_timeout = '5000ms'")
                }
                val result = work(connection)
                connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                connection.commit()
                result
            } catch (failure: Throwable) {
                try { connection.rollback() } catch (_: SQLException) { /* do not expose private diagnostics */ }
                throw failure
            }
        }
    } catch (_: SQLException) { throw BoundPhoneIdentityUnavailable() }
}

internal class BoundPhoneIdentityUnavailable : RuntimeException("Phone identity store is temporarily unavailable")
internal class BoundPhoneIdentityRevoked : RuntimeException("Phone identity is not current")

/** Claims must first pass the separately implemented cryptographic/profile validator. */
internal class BoundPhoneSessionClaims(
    val userId: UUID,
    val installationId: UUID,
    val sessionId: UUID,
    val role: UserRole,
    val profile: String,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val authVersion: Int = 1,
) {
    override fun toString() = "BoundPhoneSessionClaims(redacted)"
}

internal class BoundPhoneSession(val user: User, val claims: BoundPhoneSessionClaims) {
    override fun toString() = "BoundPhoneSession(redacted)"
}

/** The caller supplies the actual primary transaction, including future ticket/effect/receipt work.
 * A verified phone HMAC is an internal input, never an HTTP credential. Provider calls and token
 * signing must happen outside this transaction. Existing fixed accounts are never overwritten.
 */
internal class BoundPhoneIdentityTransaction(private val db: Connection) {
    init { require(!db.autoCommit) { "An explicit primary transaction is required" } }

    fun accountForVerifiedPhone(phoneHash: String): User? {
        requireHash(phoneHash)
        return query("SELECT user_id, role, display_name, revoked_at_ms FROM phone_accounts WHERE phone_hash = ? FOR UPDATE",
            listOf(phoneHash)) { row -> currentUser(row) ?: throw BoundPhoneIdentityRevoked() }.singleOrNull()
    }

    /** Used only after the surrounding auth transaction has locked/validated its bound ticket. */
    fun registerVerifiedPhone(phoneHash: String, role: UserRole, displayName: String?): User {
        requireHash(phoneHash)
        val name = if (role == UserRole.ELDER) "Elder" else displayName?.trim().orEmpty()
        require(name.length in 1..40) { "A valid registration name is required" }
        update("""INSERT INTO phone_accounts(phone_hash, user_id, role, display_name)
            VALUES (?, ?, ?, ?) ON CONFLICT (phone_hash) DO NOTHING""",
            listOf(phoneHash, UUID.randomUUID().toString(), role.name, name))
        // Concurrent tickets converge to the existing account, including its original role/name.
        return accountForVerifiedPhone(phoneHash) ?: throw BoundPhoneIdentityRevoked()
    }

    fun openSession(userId: UUID, installationId: UUID, profile: String,
                    issuedAtSeconds: Long, expiresAtSeconds: Long): BoundPhoneSession {
        requireProfile(profile)
        requireTimes(issuedAtSeconds, expiresAtSeconds)
        val user = lockAccount(userId) ?: throw BoundPhoneIdentityRevoked()
        update("""INSERT INTO phone_account_installations(user_id, installation_id, created_at_ms)
            VALUES (?, ?, ?) ON CONFLICT (user_id, installation_id) DO NOTHING""",
            listOf(userId.toString(), installationId.toString(), issuedAtSeconds * 1000))
        if (!lockInstallation(userId, installationId)) throw BoundPhoneIdentityRevoked()
        val sessionId = UUID.randomUUID()
        update("""INSERT INTO phone_auth_sessions(session_id, user_id, installation_id,
            profile, auth_version, issued_at_ms, expires_at_ms)
            VALUES (?, ?, ?, ?, 1, ?, ?)""",
            listOf(sessionId.toString(), userId.toString(), installationId.toString(), profile,
                issuedAtSeconds * 1000, expiresAtSeconds * 1000))
        return BoundPhoneSession(user, BoundPhoneSessionClaims(userId, installationId, sessionId,
            user.role, profile, issuedAtSeconds, expiresAtSeconds))
    }

    /** Current-state check for a protected effect on this same connection. Not a JWT validator.
     * Lock order is account -> account-installation -> session. The supplied allowance must be
     * the same reviewed value used by the caller's pre-DB JWT validator, and at most 60 seconds.
     */
    fun lockCurrentSession(claims: BoundPhoneSessionClaims, nowSeconds: Long,
                           clockAllowanceSeconds: Long = 60): User? {
        require(clockAllowanceSeconds in 0..60)
        require(nowSeconds in 0..MAX_SECONDS)
        if (claims.authVersion != 1 || claims.profile.isBlank() || claims.profile.length > 128 ||
            claims.issuedAtSeconds !in 0..MAX_SECONDS || claims.expiresAtSeconds !in 1..MAX_SECONDS ||
            claims.expiresAtSeconds <= claims.issuedAtSeconds ||
            claims.issuedAtSeconds > nowSeconds + clockAllowanceSeconds ||
            claims.expiresAtSeconds <= nowSeconds - clockAllowanceSeconds) return null
        val user = lockAccount(claims.userId) ?: return null
        if (user.role != claims.role || !lockInstallation(claims.userId, claims.installationId)) return null
        return query("""SELECT profile, auth_version, issued_at_ms, expires_at_ms, revoked_at_ms
            FROM phone_auth_sessions WHERE session_id = ? AND user_id = ? AND installation_id = ? FOR UPDATE""",
            listOf(claims.sessionId.toString(), claims.userId.toString(), claims.installationId.toString())) { row ->
            user.takeIf { row.getObject("revoked_at_ms") == null && row.getString("profile") == claims.profile &&
                row.getInt("auth_version") == claims.authVersion &&
                row.getLong("issued_at_ms") == claims.issuedAtSeconds * 1000 &&
                row.getLong("expires_at_ms") == claims.expiresAtSeconds * 1000 }
        }.singleOrNull()
    }

    /** Current-state check inside a read-only REPEATABLE READ snapshot transaction (no FOR UPDATE). */
    fun readCurrentSession(claims: BoundPhoneSessionClaims, nowSeconds: Long,
                           clockAllowanceSeconds: Long = 60): User? {
        require(clockAllowanceSeconds in 0..60)
        require(nowSeconds in 0..MAX_SECONDS)
        if (claims.authVersion != 1 || claims.profile.isBlank() || claims.profile.length > 128 ||
            claims.issuedAtSeconds !in 0..MAX_SECONDS || claims.expiresAtSeconds !in 1..MAX_SECONDS ||
            claims.expiresAtSeconds <= claims.issuedAtSeconds ||
            claims.issuedAtSeconds > nowSeconds + clockAllowanceSeconds ||
            claims.expiresAtSeconds <= nowSeconds - clockAllowanceSeconds) return null
        val user = query("SELECT user_id, role, display_name, revoked_at_ms FROM phone_accounts WHERE user_id = ?",
            listOf(claims.userId.toString())) { currentUser(it) }.singleOrNull() ?: return null
        val installationCurrent = query(
            "SELECT revoked_at_ms FROM phone_account_installations WHERE user_id = ? AND installation_id = ?",
            listOf(claims.userId.toString(), claims.installationId.toString())) { it.getObject(1) == null }.singleOrNull() == true
        if (user.role != claims.role || !installationCurrent) return null
        return query("""SELECT profile, auth_version, issued_at_ms, expires_at_ms, revoked_at_ms
            FROM phone_auth_sessions WHERE session_id = ? AND user_id = ? AND installation_id = ?""",
            listOf(claims.sessionId.toString(), claims.userId.toString(), claims.installationId.toString())) { row ->
            user.takeIf { row.getObject("revoked_at_ms") == null && row.getString("profile") == claims.profile &&
                row.getInt("auth_version") == claims.authVersion &&
                row.getLong("issued_at_ms") == claims.issuedAtSeconds * 1000 &&
                row.getLong("expires_at_ms") == claims.expiresAtSeconds * 1000 }
        }.singleOrNull()
    }

    /** Internal administration seam; no logout/revocation HTTP endpoint is introduced. */
    fun revokeSession(userId: UUID, installationId: UUID, sessionId: UUID, atMillis: Long) {
        require(atMillis >= 0)
        if (lockAccount(userId) == null || !lockInstallation(userId, installationId)) return
        query("SELECT session_id FROM phone_auth_sessions WHERE session_id = ? AND user_id = ? AND installation_id = ? FOR UPDATE",
            listOf(sessionId.toString(), userId.toString(), installationId.toString())) { it.getString(1) }
        update("""UPDATE phone_auth_sessions SET revoked_at_ms = COALESCE(revoked_at_ms, ?)
            WHERE session_id = ? AND user_id = ? AND installation_id = ?""",
            listOf(atMillis, sessionId.toString(), userId.toString(), installationId.toString()))
    }

    fun revokeInstallation(userId: UUID, installationId: UUID, atMillis: Long) {
        require(atMillis >= 0)
        if (lockAccount(userId) == null || !lockInstallation(userId, installationId)) return
        update("""UPDATE phone_account_installations SET revoked_at_ms = COALESCE(revoked_at_ms, ?)
            WHERE user_id = ? AND installation_id = ?""", listOf(atMillis, userId.toString(), installationId.toString()))
    }

    fun revokeAccount(userId: UUID, atMillis: Long) {
        require(atMillis >= 0)
        if (lockAccount(userId) == null) return
        update("UPDATE phone_accounts SET revoked_at_ms = COALESCE(revoked_at_ms, ?) WHERE user_id = ?",
            listOf(atMillis, userId.toString()))
    }

    private fun lockAccount(id: UUID): User? = query(
        "SELECT user_id, role, display_name, revoked_at_ms FROM phone_accounts WHERE user_id = ? FOR UPDATE",
        listOf(id.toString())) { currentUser(it) }.singleOrNull()

    private fun lockInstallation(userId: UUID, installationId: UUID) = query(
        "SELECT revoked_at_ms FROM phone_account_installations WHERE user_id = ? AND installation_id = ? FOR UPDATE",
        listOf(userId.toString(), installationId.toString())) { it.getObject(1) == null }.singleOrNull() == true

    private fun currentUser(row: ResultSet) = if (row.getObject("revoked_at_ms") != null) null else
        User(row.getString("user_id"), row.getString("display_name"), UserRole.valueOf(row.getString("role")))

    private fun update(sql: String, values: List<Any>) = statement(sql, values) { it.executeUpdate() }
    private fun <T> query(sql: String, values: List<Any>, read: (ResultSet) -> T): List<T> = statement(sql, values) {
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }
    private fun <T> statement(sql: String, values: List<Any>, work: (PreparedStatement) -> T): T = db.prepareStatement(sql).use {
        it.queryTimeout = 5
        values.forEachIndexed { index, value -> it.setObject(index + 1, value) }
        work(it)
    }
    private fun requireHash(hash: String) { require(hash.matches(Regex("[0-9a-f]{64}"))) { "A phone HMAC is required" } }
    private fun requireProfile(profile: String) { require(profile.isNotBlank() && profile.length <= 128 && profile.none { it.isISOControl() }) }
    private fun requireTimes(issued: Long, expires: Long) { require(issued in 0..MAX_SECONDS && expires in 1..MAX_SECONDS && expires > issued) }
    private companion object { const val MAX_SECONDS = 253402300799L }
}
