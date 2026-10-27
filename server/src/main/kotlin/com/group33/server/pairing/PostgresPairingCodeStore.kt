package com.group33.server.pairing

import com.group33.shared.contract.PairingClosedReason
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

/** Explicitly constructed adapter; no HTTP/security/Boot configuration is registered here. */
internal class PostgresPairingCodeStore(
    private val dataSource: DataSource,
    private val receiptCipher: PairingReceiptCipher,
    private val lockWaitMillis: Long = 1_000,
) : PairingRequestStore {
    init { require(lockWaitMillis in 0..30_000) }

    override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = requestTransaction { work(it) }

    override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = dataSource.connection.use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)").use {
                it.setString(1, "${lockWaitMillis}ms")
                it.execute()
            }
            // Shared database row, not a JVM mutex: includes cross-Elder hash checks and quota/receipt writes.
            val suffix = if (lockWaitMillis == 0L) " NOWAIT" else ""
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM pairing_issuance_guard WHERE id = 1 FOR UPDATE$suffix").use {
                    check(it.next()) { "Pairing Store schema is not initialized" }
                }
            }
            val result = work(Transaction(connection, receiptCipher))
            connection.commit()
            result
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: SQLException) { failure.addSuppressed(rollback) }
            if (failure is SQLException && failure.sqlState == "55P03") throw CodeStoreBusy()
            throw failure
        }
    }

    private class Transaction(private val db: Connection, private val cipher: PairingReceiptCipher) : PairingRequestTransaction {
        override fun operation(key: CodeIssueKey): CodeIssueOperation? = query(
            "SELECT * FROM pairing_issue_operation WHERE actor_id = ? AND operation_id = ?",
            listOf(key.actorId, key.operationId),
        ) { row ->
            val binding = CodeIssueBinding(row.getString("installation_id"), row.getString("target_elder_id"),
                row.getString("fingerprint"))
            val id = row.getString("receipt_id")
            val receipt = id?.let { CodeIssueReceipt(cipher.open(key, binding, id,
                row.getLong("receipt_expiry"), row.getBytes("receipt_cipher"))) }
            val rejection = row.getString("rejection_reason")?.let {
                CodeIssuanceResult.Rejected(CodeIssueRejection.valueOf(it),
                    row.getObject("retry_after", java.lang.Long::class.java)?.toLong())
            }
            CodeIssueOperation(binding, receipt, rejection)
        }.singleOrNull()

        override fun putOperation(key: CodeIssueKey, value: CodeIssueOperation) {
            val receipt = value.receipt?.replay()
            val count = update("""
                INSERT INTO pairing_issue_operation AS current
                  (actor_id, operation_id, installation_id, target_elder_id, fingerprint,
                   receipt_id, receipt_cipher, receipt_expiry, rejection_reason, retry_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET
                  receipt_id = EXCLUDED.receipt_id, receipt_cipher = EXCLUDED.receipt_cipher,
                  receipt_expiry = EXCLUDED.receipt_expiry, rejection_reason = EXCLUDED.rejection_reason,
                  retry_after = EXCLUDED.retry_after
                WHERE current.installation_id = EXCLUDED.installation_id
                  AND current.target_elder_id = EXCLUDED.target_elder_id
                  AND current.fingerprint = EXCLUDED.fingerprint
            """.trimIndent(), listOf(key.actorId, key.operationId, value.binding.installationId,
                value.binding.targetElderId, value.binding.fingerprint, receipt?.id,
                receipt?.let { cipher.seal(key, value.binding, it) }, receipt?.expiresAtEpochMillis,
                value.rejection?.reason?.name, value.rejection?.retryAfterSeconds))
            check(count == 1) { "Operation binding cannot be replaced" }
        }

        override fun activeCode(elderId: String): StoredPairingCode? = query(
            "SELECT * FROM pairing_active_code WHERE elder_id = ?", listOf(elderId),
        ) { StoredPairingCode(it.getString("code_id"), it.getString("elder_id"),
            it.getString("code_hash"), it.getLong("expires_at")) }.singleOrNull()

        override fun replaceActiveCode(value: StoredPairingCode, now: Long) {
            // Expiry wins at the deadline. The shared guard serializes this with consumption.
            activeCode(value.elderId)?.let { previous ->
                update("""
                    UPDATE pairing_request SET status = CASE WHEN expires_at <= ? THEN 'EXPIRED' ELSE 'CANCELLED' END,
                      closed_reason = CASE WHEN expires_at <= ? THEN 'CODE_EXPIRED' ELSE 'CODE_REPLACED' END
                    WHERE code_id = ? AND status = 'PENDING'
                """.trimIndent(), listOf(now, now, previous.id))
            }
            update("""
                INSERT INTO pairing_active_code (elder_id, code_id, code_hash, expires_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (elder_id) DO UPDATE SET code_id = EXCLUDED.code_id,
                  code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at
            """.trimIndent(), listOf(value.elderId, value.id, value.hash, value.expiresAtEpochMillis))
        }

        override fun hasEffectiveHash(hash: String, now: Long): Boolean = query(
            "SELECT 1 FROM pairing_active_code WHERE code_hash = ? AND expires_at > ? LIMIT 1", listOf(hash, now),
        ) { true }.isNotEmpty()

        override fun issuanceTimes(elderId: String): List<Long> = query(
            "SELECT issued_at FROM pairing_issuance_time WHERE elder_id = ? ORDER BY ordinal", listOf(elderId),
        ) { it.getLong(1) }

        override fun putIssuanceTimes(elderId: String, times: List<Long>) {
            update("DELETE FROM pairing_issuance_time WHERE elder_id = ?", listOf(elderId))
            db.prepareStatement("INSERT INTO pairing_issuance_time VALUES (?, ?, ?)").use { statement ->
                times.forEachIndexed { index, time ->
                    statement.bind(listOf(elderId, index, time))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        override fun requestOperation(key: PairingRequestKey): PairingRequestOperation? = query(
            "SELECT * FROM pairing_request_operation WHERE actor_id = ? AND operation_id = ?",
            listOf(key.actorId, key.operationId),
        ) { row ->
            val receipt = row.getString("receipt_request_id")?.let { id ->
                // Immutable request identity/display/expiry columns preserve the original success.
                // Current terminal status is deliberately not replayed as the original POST result.
                checkNotNull(rawRequest(id)).copy(status = PairingRequestStatus.PENDING, closedReason = null)
            }
            val rejection = row.getString("rejection_reason")?.let {
                PairingRequestResult.Rejected(PairingRequestRejection.valueOf(it),
                    row.getObject("retry_after", java.lang.Long::class.java)?.toLong())
            }
            PairingRequestOperation(row.getString("installation_id"), row.getString("fingerprint"), receipt, rejection)
        }.singleOrNull()

        override fun putRequestOperation(key: PairingRequestKey, value: PairingRequestOperation) {
            value.receipt?.let { receipt ->
                check(receipt.caregiver.id == key.actorId && value.rejection == null)
                check(rawRequest(receipt.id)?.copy(status = PairingRequestStatus.PENDING, closedReason = null) == receipt)
            }
            val count = update("""
                INSERT INTO pairing_request_operation AS current
                  (actor_id, operation_id, installation_id, fingerprint, receipt_request_id, rejection_reason, retry_after)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET
                  receipt_request_id = EXCLUDED.receipt_request_id, rejection_reason = EXCLUDED.rejection_reason,
                  retry_after = EXCLUDED.retry_after
                WHERE current.installation_id = EXCLUDED.installation_id AND current.fingerprint = EXCLUDED.fingerprint
                  AND (current.receipt_request_id IS NULL OR
                    (current.receipt_request_id = EXCLUDED.receipt_request_id AND EXCLUDED.rejection_reason IS NULL))
            """.trimIndent(), listOf(key.actorId, key.operationId, value.installationId, value.fingerprint,
                value.receipt?.id, value.rejection?.reason?.name, value.rejection?.retryAfterSeconds))
            check(count == 1) { "Request operation binding or accepted receipt cannot be replaced" }
        }

        override fun unconsumedCode(hash: String, now: Long): StoredPairingCode? = query("""
            SELECT c.* FROM pairing_active_code c WHERE code_hash = ? AND expires_at > ?
              AND NOT EXISTS (SELECT 1 FROM pairing_request r WHERE r.code_id = c.code_id)
        """.trimIndent(), listOf(hash, now)) {
            StoredPairingCode(it.getString("code_id"), it.getString("elder_id"), it.getString("code_hash"), it.getLong("expires_at"))
        }.singleOrNull()

        override fun consume(code: StoredPairingCode, request: PairingRequestView) {
            check(request.elderId == code.elderId && request.expiresAtEpochMillis == code.expiresAtEpochMillis)
            check(request.status == PairingRequestStatus.PENDING && request.closedReason == null && request.relationshipId == null)
            check(request.caregiver.role == UserRole.CAREGIVER)
            val count = update("""
                INSERT INTO pairing_request (id, code_id, elder_id, caregiver_id, caregiver_name, status, expires_at)
                SELECT ?, c.code_id, c.elder_id, ?, ?, 'PENDING', c.expires_at FROM pairing_active_code c
                WHERE c.code_id = ? AND c.elder_id = ? AND c.code_hash = ? AND c.expires_at = ?
            """.trimIndent(), listOf(request.id, request.caregiver.id, request.caregiver.displayName,
                code.id, code.elderId, code.hash, code.expiresAtEpochMillis))
            check(count == 1) { "Code changed before consumption" }
        }

        override fun request(id: String, now: Long): PairingRequestView? {
            update("UPDATE pairing_request SET status = 'EXPIRED', closed_reason = 'CODE_EXPIRED' WHERE id = ? AND status = 'PENDING' AND expires_at <= ?", listOf(id, now))
            return rawRequest(id)
        }

        private fun rawRequest(id: String): PairingRequestView? = query(
            "SELECT * FROM pairing_request WHERE id = ?", listOf(id),
        ) {
            PairingRequestView(it.getString("id"), it.getString("elder_id"),
                User(it.getString("caregiver_id"), it.getString("caregiver_name"), UserRole.CAREGIVER),
                PairingRequestStatus.valueOf(it.getString("status")), it.getLong("expires_at"),
                it.getString("closed_reason")?.let(PairingClosedReason::valueOf))
        }.singleOrNull()

        override fun installationAttempts(id: String) = attempts("INSTALLATION", id)
        override fun addressAttempts(hash: String) = attempts("ADDRESS", hash)
        override fun putInstallationAttempts(id: String, times: List<Long>) = putAttempts("INSTALLATION", id, times)
        override fun putAddressAttempts(hash: String, times: List<Long>) = putAttempts("ADDRESS", hash, times)

        private fun attempts(scope: String, owner: String) = query(
            "SELECT attempted_at FROM pairing_request_attempt WHERE scope = ? AND owner_key = ? ORDER BY ordinal",
            listOf(scope, owner),
        ) { it.getLong(1) }

        private fun putAttempts(scope: String, owner: String, times: List<Long>) {
            update("DELETE FROM pairing_request_attempt WHERE scope = ? AND owner_key = ?", listOf(scope, owner))
            db.prepareStatement("INSERT INTO pairing_request_attempt VALUES (?, ?, ?, ?)").use { statement ->
                times.forEachIndexed { index, time -> statement.bind(listOf(scope, owner, index, time)); statement.addBatch() }
                statement.executeBatch()
            }
        }

        private fun update(sql: String, values: List<Any?>): Int = db.prepareStatement(sql).use {
            it.bind(values)
            it.executeUpdate()
        }

        private fun <T> query(sql: String, values: List<Any?>, map: (ResultSet) -> T): List<T> =
            db.prepareStatement(sql).use { statement ->
                statement.bind(values)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(map(rows)) } }
            }

        private fun PreparedStatement.bind(values: List<Any?>) {
            values.forEachIndexed { index, value ->
                if (value is ByteArray) setBytes(index + 1, value) else setObject(index + 1, value)
            }
        }
    }
}
