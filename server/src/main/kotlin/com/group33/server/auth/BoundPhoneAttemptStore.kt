package com.group33.server.auth

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

internal enum class PhoneWork { GRANT, VERIFY }
internal enum class AttemptState { PENDING, CLOSED, CONSUMED }

internal class PhoneAttempt(
    val id: UUID,
    val deadline: Long,
    val state: AttemptState,
    val lease: UUID?,
    val leaseUntil: Long?,
)

internal class BoundPhoneAttemptStore(private val db: Connection) {
    init { require(!db.autoCommit) }

    fun start(id: UUID, capabilityHash: String, installation: UUID, profile: String, deadline: Long) {
        update("""INSERT INTO phone_auth_attempts(id, capability_hash, installation_id, profile, deadline_ms, state)
            VALUES (?, ?, ?, ?, ?, 'PENDING')""", id, capabilityHash, installation, profile, deadline)
    }

    fun current(hash: String, installation: UUID, profile: String): PhoneAttempt? = query(
        """SELECT id, deadline_ms, state, lease, lease_until_ms FROM phone_auth_attempts
            WHERE capability_hash = ? AND installation_id = ? AND profile = ? FOR UPDATE""",
        hash, installation, profile,
    ) { PhoneAttempt(it.getObject(1, UUID::class.java), it.getLong(2), AttemptState.valueOf(it.getString(3)),
        it.getObject(4, UUID::class.java), it.getObject(5, java.lang.Long::class.java)?.toLong()) }

    fun claim(id: UUID, lease: UUID, until: Long, work: PhoneWork) {
        check(update("UPDATE phone_auth_attempts SET lease = ?, lease_until_ms = ?, work = ? WHERE id = ?",
            lease, until, work.name, id) == 1)
    }

    fun rememberProof(profile: String, fingerprint: String): Boolean = update(
        "INSERT INTO phone_auth_proofs(profile, fingerprint) VALUES (?, ?) ON CONFLICT DO NOTHING", profile, fingerprint,
    ) == 1

    fun finish(id: UUID, state: AttemptState, deadline: Long) {
        check(update("""UPDATE phone_auth_attempts SET lease = NULL, lease_until_ms = NULL, work = NULL,
            state = ?, deadline_ms = ? WHERE id = ?""", state.name, deadline, id) == 1)
    }

    fun issueTicket(hash: String, attempt: UUID, installation: UUID, profile: String, phoneHash: String, expiry: Long) {
        update("""INSERT INTO phone_bound_registration_tickets(hash, attempt_id, installation_id, profile, phone_hash, expires_ms)
            VALUES (?, ?, ?, ?, ?, ?)""", hash, attempt, installation, profile, phoneHash, expiry)
    }

    fun consumeTicket(hash: String, installation: UUID, profile: String, now: () -> Long): String? {
        val row = query("""SELECT phone_hash, expires_ms FROM phone_bound_registration_tickets
            WHERE hash = ? AND installation_id = ? AND profile = ? FOR UPDATE""", hash, installation, profile) {
            it.getString(1) to it.getLong(2)
        } ?: return null
        if (row.second <= now()) return null
        check(update("DELETE FROM phone_bound_registration_tickets WHERE hash = ?", hash) == 1)
        return row.first
    }

    private fun update(sql: String, vararg values: Any) = statement(sql, values) { it.executeUpdate() }
    private fun <T> query(sql: String, vararg values: Any, read: (ResultSet) -> T): T? = statement(sql, values) {
        it.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
    }
    private fun <T> statement(sql: String, values: Array<out Any>, work: (PreparedStatement) -> T): T =
        db.prepareStatement(sql).use { statement ->
            statement.queryTimeout = 5
            values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            work(statement)
        }
}
