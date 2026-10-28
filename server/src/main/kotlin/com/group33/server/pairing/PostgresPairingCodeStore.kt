package com.group33.server.pairing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.plan.*
import com.group33.server.occurrence.*
import com.group33.shared.contract.*
import com.group33.shared.contract.ConfirmReminderPlanResponse
import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.MedicationText
import com.group33.shared.contract.ReminderPlanDraft
import com.group33.shared.contract.ReminderPlanView
import com.group33.shared.contract.PlanRevisionView
import com.group33.server.photo.PhotoInputRejection
import com.group33.server.photo.PhotoMetadataStore
import com.group33.server.photo.PhotoMetadataTransaction
import com.group33.server.photo.PhotoUploadKey
import com.group33.server.photo.PhotoUploadOperation
import com.group33.server.photo.PhotoUploadState
import com.group33.server.photo.ReadyMedicationPhoto
import com.group33.server.photo.storage.PhotoObjectKey
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.contract.PairingClosedReason
import com.group33.shared.contract.PairingRequestStatus
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Explicitly constructed adapter; no HTTP/security/Boot configuration is registered here. */
internal class PostgresPairingCodeStore(
    private val dataSource: DataSource,
    private val receiptCipher: PairingReceiptCipher,
    private val lockWaitMillis: Long = 1_000,
) : OccurrenceRegistrationStore {
    init { require(lockWaitMillis in 0..30_000) }

    override fun <T> transaction(work: (PairingCodeTransaction) -> T): T = requestTransaction { work(it) }

    override fun <T> requestTransaction(work: (PairingRequestTransaction) -> T): T = decisionTransaction { work(it) }

    override fun <T> decisionTransaction(work: (PairingDecisionTransaction) -> T): T = directoryTransaction { work(it) }

    override fun <T> directoryTransaction(work: (PairingDirectoryTransaction) -> T): T = photoTransaction { work(it) }

    override fun <T> photoTransaction(work: (PhotoMetadataTransaction) -> T): T = confirmationTransaction { work(it) }

    override fun <T> confirmationTransaction(work: (PlanConfirmationTransaction) -> T): T = mutationTransaction { work(it) }

    override fun <T> mutationTransaction(work: (PlanMutationTransaction) -> T): T = registrationTransaction { work(it) }

    override fun <T> registrationTransaction(work: (OccurrenceRegistrationTransaction) -> T): T = dataSource.connection.use { connection ->
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

    private class Transaction(private val db: Connection, private val cipher: PairingReceiptCipher) : OccurrenceRegistrationTransaction {
        override fun registrationOperation(key: RegistrationKey) = query(
            "SELECT * FROM occurrence_registration_operation WHERE actor_id = ? AND operation_id = ?", listOf(key.actorId, key.operationId),
        ) { row -> RegistrationOperation(
            RegistrationBinding(row.getString("installation_id"), row.getString("elder_id"),
                mapper.readValue(row.getString("request_payload"), OccurrenceRegistrationRequest::class.java)),
            row.getString("response_payload")?.let { mapper.readValue(it, OccurrenceRegistrationResponse::class.java) },
            row.getString("rejection_payload")?.let { mapper.readValue(it, RegistrationResult.Rejected::class.java) },
        ) }.singleOrNull()?.let(RegistrationCopies::operation)

        override fun putRegistrationOperation(key: RegistrationKey, value: RegistrationOperation) {
            check(key.actorId == value.binding.elderId)
            value.response?.let { response ->
                check(response.items.map { it.event.generationEvidence } == value.binding.request.items)
                response.items.forEach { receipt ->
                    val stored = checkNotNull(occurrence(receipt.event.occurrence))
                    check(stored.installationId == value.binding.installationId && stored.receipt == receipt && receipt.event.elderId == key.actorId)
                }
            }
            check(update("""
                INSERT INTO occurrence_registration_operation AS current
                    (actor_id, operation_id, installation_id, elder_id, canonicalization_version, request_payload, response_payload, rejection_payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET response_payload = EXCLUDED.response_payload, rejection_payload = EXCLUDED.rejection_payload
                WHERE current.installation_id = EXCLUDED.installation_id AND current.elder_id = EXCLUDED.elder_id
                  AND current.canonicalization_version = EXCLUDED.canonicalization_version AND current.request_payload = EXCLUDED.request_payload
                  AND (current.response_payload IS NULL OR current.response_payload = EXCLUDED.response_payload)
            """.trimIndent(), listOf(key.actorId, key.operationId, value.binding.installationId, value.binding.elderId,
                ContractProtocol.CANONICALIZATION_VERSION, mapper.writeValueAsString(value.binding.request),
                value.response?.let(mapper::writeValueAsString), value.rejection?.let(mapper::writeValueAsString))) == 1) {
                "Registration binding or accepted receipt cannot be replaced"
            }
        }

        override fun occurrence(key: OccurrenceKey) = query(
            "SELECT * FROM registered_occurrence WHERE plan_id = ? AND local_date = ? AND local_time = ?",
            listOf(UUID.fromString(key.planId), key.localDate, key.localTime), ::readOccurrence,
        ).singleOrNull()
        override fun generation(actorId: String, generationId: UUID) = query(
            "SELECT * FROM registered_occurrence WHERE elder_id = ? AND generation_id = ?", listOf(actorId, generationId), ::readOccurrence,
        ).singleOrNull()
        override fun insertOccurrence(value: RegisteredOccurrence) {
            val event = value.receipt.event
            check(value.origin.actorId == event.elderId)
            check(update("""
                INSERT INTO registered_occurrence
                    (id, plan_id, local_date, local_time, plan_revision, elder_id, medication_id, installation_id,
                     generation_id, origin_operation_id, receipt_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent(), listOf(UUID.fromString(event.id), UUID.fromString(event.occurrence.planId), event.occurrence.localDate,
                event.occurrence.localTime, event.planRevision, event.elderId, UUID.fromString(event.medicationId), value.installationId,
                UUID.fromString(value.receipt.generationId), value.origin.operationId, mapper.writeValueAsString(value.receipt))) == 1)
        }
        private fun readOccurrence(row: ResultSet) = RegistrationCopies.occurrence(RegisteredOccurrence(row.getString("installation_id"),
            RegistrationKey(row.getString("elder_id"), row.getObject("origin_operation_id", UUID::class.java)),
            mapper.readValue(row.getString("receipt_payload"), OccurrenceRegistrationReceipt::class.java)))

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
                checkNotNull(rawRequest(id)).copy(status = PairingRequestStatus.PENDING, closedReason = null, relationshipId = null)
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
                check(rawRequest(receipt.id)?.copy(status = PairingRequestStatus.PENDING, closedReason = null, relationshipId = null) == receipt)
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
            "SELECT * FROM pairing_request WHERE id = ?", listOf(id), ::readRequest,
        ).singleOrNull()
        private fun readRequest(row: ResultSet) = PairingRequestView(row.getString("id"), row.getString("elder_id"),
            User(row.getString("caregiver_id"), row.getString("caregiver_name"), UserRole.CAREGIVER),
            PairingRequestStatus.valueOf(row.getString("status")), row.getLong("expires_at"),
            row.getString("closed_reason")?.let(PairingClosedReason::valueOf), row.getString("relationship_id"))

        override fun participantRequests(actorId: String, role: UserRole, now: Long, status: PairingRequestStatus?): List<PairingRequestView> {
            val owner = if (role == UserRole.ELDER) "elder_id" else "caregiver_id"
            update("UPDATE pairing_request SET status = 'EXPIRED', closed_reason = 'CODE_EXPIRED' WHERE $owner = ? AND status = 'PENDING' AND expires_at <= ?", listOf(actorId, now))
            val filter = if (status == null) "" else " AND status = ?"
            return query("SELECT * FROM pairing_request WHERE $owner = ?$filter ORDER BY id",
                if (status == null) listOf(actorId) else listOf(actorId, status.name), ::readRequest)
        }
        override fun participantRelationships(actorId: String, role: UserRole): List<CareRelationshipView> {
            val owner = if (role == UserRole.ELDER) "elder_id" else "caregiver_id"
            return query("SELECT * FROM pairing_relationship WHERE $owner = ? ORDER BY id", listOf(actorId), ::readRelationship)
        }
        override fun codeDeletion(key: CodeDeletionKey) = query(
            "SELECT * FROM pairing_code_deletion WHERE actor_id = ? AND operation_id = ?", listOf(key.actorId, key.operationId),
        ) { CodeDeletionReceipt(it.getString("installation_id"), it.getString("fingerprint"), it.getString("code_id")) }.singleOrNull()
        override fun putCodeDeletion(key: CodeDeletionKey, value: CodeDeletionReceipt) {
            check(update("""
                INSERT INTO pairing_code_deletion AS current (actor_id, operation_id, installation_id, fingerprint, code_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (actor_id, operation_id) DO UPDATE SET code_id = EXCLUDED.code_id
                WHERE current.installation_id = EXCLUDED.installation_id AND current.fingerprint = EXCLUDED.fingerprint
                  AND current.code_id = EXCLUDED.code_id
            """.trimIndent(), listOf(key.actorId, key.operationId, value.installationId, value.fingerprint, value.codeId)) == 1) {
                "Code deletion receipt binding cannot be replaced"
            }
        }
        override fun invalidateCode(elderId: String, codeId: String, now: Long): Boolean {
            if (activeCode(elderId)?.id != codeId) return false
            update("""
                UPDATE pairing_request SET status = CASE WHEN expires_at <= ? THEN 'EXPIRED' ELSE 'CANCELLED' END,
                  closed_reason = CASE WHEN expires_at <= ? THEN 'CODE_EXPIRED' ELSE 'ELDER_CANCELLED' END
                WHERE code_id = ? AND elder_id = ? AND status = 'PENDING'
            """.trimIndent(), listOf(now, now, codeId, elderId))
            return update("DELETE FROM pairing_active_code WHERE elder_id = ? AND code_id = ?", listOf(elderId, codeId)) == 1
        }

        override fun relationship(id: String) = query("SELECT * FROM pairing_relationship WHERE id = ?", listOf(id), ::readRelationship).singleOrNull()
        override fun activeRelationship(elderId: String) = query(
            "SELECT * FROM pairing_relationship WHERE elder_id = ? AND status = 'ACTIVE'", listOf(elderId), ::readRelationship,
        ).singleOrNull()

        private fun readRelationship(row: ResultSet) = CareRelationshipView(
            row.getString("id"), row.getString("elder_id"), row.getString("caregiver_id"),
            User(row.getString("elder_id"), row.getString("elder_name"), UserRole.ELDER),
            User(row.getString("caregiver_id"), row.getString("caregiver_name"), UserRole.CAREGIVER),
            CareRelationshipStatus.valueOf(row.getString("status")), row.getLong("created_at"),
            row.getObject("revoked_at", java.lang.Long::class.java)?.toLong(),
        )

        override fun insertRelationship(value: CareRelationshipView) {
            check(value.status == CareRelationshipStatus.ACTIVE && value.revokedAtEpochMillis == null)
            check(value.elder.id == value.elderId && value.elder.role == UserRole.ELDER)
            check(value.caregiver.id == value.caregiverId && value.caregiver.role == UserRole.CAREGIVER)
            update("""
                INSERT INTO pairing_relationship (id, elder_id, caregiver_id, elder_name, caregiver_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
            """.trimIndent(), listOf(value.id, value.elderId, value.caregiverId, value.elder.displayName,
                value.caregiver.displayName, value.createdAtEpochMillis))
        }

        override fun revokeRelationship(id: String, now: Long): CareRelationshipView {
            update("UPDATE pairing_relationship SET status = 'REVOKED', revoked_at = ? WHERE id = ? AND status = 'ACTIVE'", listOf(now, id))
            return checkNotNull(relationship(id))
        }

        override fun closeRequest(value: PairingRequestView) {
            val old = checkNotNull(rawRequest(value.id))
            check(old.status == PairingRequestStatus.PENDING)
            check(old == value.copy(status = old.status, closedReason = old.closedReason, relationshipId = old.relationshipId))
            when (value.status) {
                PairingRequestStatus.ACCEPTED -> {
                    val relation = checkNotNull(value.relationshipId?.let(::relationship))
                    check(relation.status == CareRelationshipStatus.ACTIVE && relation.elderId == old.elderId)
                    check(relation.caregiverId == old.caregiver.id && value.closedReason == null)
                }
                PairingRequestStatus.REJECTED -> check(value.closedReason == PairingClosedReason.ELDER_REJECTED && value.relationshipId == null)
                PairingRequestStatus.CANCELLED -> check(value.closedReason in setOf(PairingClosedReason.ELDER_CANCELLED,
                    PairingClosedReason.CAREGIVER_CANCELLED) && value.relationshipId == null)
                else -> error("Unsupported explicit pairing decision")
            }
            check(update("UPDATE pairing_request SET status = ?, closed_reason = ?, relationship_id = ? WHERE id = ? AND status = 'PENDING'",
                listOf(value.status.name, value.closedReason?.name, value.relationshipId, value.id)) == 1)
        }

        override fun decisionOperation(key: PairingDecisionKey): PairingDecisionOperation? = query(
            "SELECT * FROM pairing_decision_operation WHERE actor_id = ? AND action = ? AND operation_id = ?",
            listOf(key.actorId, key.action.name, key.operationId),
        ) { row ->
            // Explicit fixed DTO classes only. No polymorphic/default typing or current-row reconstruction.
            val result = when (row.getString("result_kind")) {
                "RELATIONSHIP" -> PairingDecisionResult.Relationship(mapper.readValue(row.getString("result_payload"), CareRelationshipView::class.java))
                "REQUEST" -> PairingDecisionResult.Request(mapper.readValue(row.getString("result_payload"), PairingRequestView::class.java))
                "CANCELLED" -> PairingDecisionResult.Cancelled
                "REJECTED" -> PairingDecisionResult.Rejected(PairingDecisionRejection.valueOf(row.getString("rejection_reason")),
                    row.getObject("retry_after", java.lang.Long::class.java)?.toLong())
                else -> error("Invalid stored pairing decision kind")
            }
            PairingDecisionOperation(row.getString("installation_id"), row.getString("fingerprint"), result)
        }.singleOrNull()

        override fun putDecisionOperation(key: PairingDecisionKey, value: PairingDecisionOperation) {
            val result = value.result
            val relation = (result as? PairingDecisionResult.Relationship)?.view
            val request = (result as? PairingDecisionResult.Request)?.view
            val rejected = result as? PairingDecisionResult.Rejected
            val kind = when (result) {
                is PairingDecisionResult.Relationship -> "RELATIONSHIP"
                is PairingDecisionResult.Request -> "REQUEST"
                PairingDecisionResult.Cancelled -> "CANCELLED"
                is PairingDecisionResult.Rejected -> "REJECTED"
            }
            val payload = (relation ?: request)?.let(mapper::writeValueAsString)
            val count = update("""
                INSERT INTO pairing_decision_operation AS current
                    (actor_id, action, operation_id, installation_id, fingerprint, result_kind, result_payload,
                     relationship_id, request_id, rejection_reason, retry_after)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (actor_id, action, operation_id) DO UPDATE SET
                    result_kind = EXCLUDED.result_kind, result_payload = EXCLUDED.result_payload,
                    relationship_id = EXCLUDED.relationship_id, request_id = EXCLUDED.request_id,
                    rejection_reason = EXCLUDED.rejection_reason, retry_after = EXCLUDED.retry_after
                WHERE current.installation_id = EXCLUDED.installation_id AND current.fingerprint = EXCLUDED.fingerprint
                  AND (current.result_kind = 'REJECTED' OR
                    (current.result_kind = EXCLUDED.result_kind AND current.result_payload IS NOT DISTINCT FROM EXCLUDED.result_payload
                     AND current.relationship_id IS NOT DISTINCT FROM EXCLUDED.relationship_id
                     AND current.request_id IS NOT DISTINCT FROM EXCLUDED.request_id
                     AND EXCLUDED.rejection_reason IS NULL AND EXCLUDED.retry_after IS NULL))
            """.trimIndent(), listOf(key.actorId, key.action.name, key.operationId, value.installationId, value.fingerprint,
                kind, payload, relation?.id, request?.id, rejected?.reason?.name, rejected?.retryAfterSeconds))
            check(count == 1) { "Decision operation binding or accepted receipt cannot be replaced" }
        }

        override fun mutationOperation(key: PlanMutationKey): PlanMutationOperation? = query(
            "SELECT * FROM plan_mutation_operation WHERE actor_id = ? AND action = ? AND operation_id = ?",
            listOf(key.actorId, key.action.name, key.operationId),
        ) { row ->
            PlanMutationOperation(row.getString("installation_id"), row.getString("elder_id"), row.getString("plan_id"), row.getString("fingerprint"),
                row.getString("receipt_payload")?.let { mapper.readValue(it, com.group33.shared.contract.UpdateReminderPlanResponse::class.java) },
                row.getString("rejection_reason")?.let { PlanMutationResult.Rejected(PlanMutationRejection.valueOf(it),
                    mapper.readValue(row.getString("violations"), Array<ConfirmationViolation>::class.java).toList()) }).detached()
        }.singleOrNull()

        override fun putMutationOperation(key: PlanMutationKey, value: PlanMutationOperation) {
            val receipt = value.receipt
            receipt?.let {
                check(it.plan.id == value.planId && it.plan.elderId == value.elderId && it.medication.elderId == value.elderId)
                check(it.plan.medicationId == it.medication.id && plan(value.planId) == it.plan && medication(it.medication.id) == it.medication)
                check(revision(value.planId, it.plan.revision) == PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(it.medication, it.plan)))
            }
            val count = update("""
                INSERT INTO plan_mutation_operation AS current
                    (actor_id, action, operation_id, installation_id, elder_id, plan_id, fingerprint,
                     receipt_revision, receipt_payload, rejection_reason, violations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))
                ON CONFLICT (actor_id, action, operation_id) DO UPDATE SET
                    receipt_revision = EXCLUDED.receipt_revision, receipt_payload = EXCLUDED.receipt_payload,
                    rejection_reason = EXCLUDED.rejection_reason, violations = EXCLUDED.violations
                WHERE current.installation_id = EXCLUDED.installation_id AND current.elder_id = EXCLUDED.elder_id
                  AND current.plan_id = EXCLUDED.plan_id AND current.fingerprint = EXCLUDED.fingerprint
                  AND (current.receipt_payload IS NULL OR (current.receipt_revision = EXCLUDED.receipt_revision
                       AND current.receipt_payload = EXCLUDED.receipt_payload AND EXCLUDED.rejection_reason IS NULL
                       AND EXCLUDED.violations = '[]'::jsonb))
            """.trimIndent(), listOf(key.actorId, key.action.name, key.operationId, value.installationId, value.elderId,
                UUID.fromString(value.planId), value.fingerprint, receipt?.plan?.revision, receipt?.let(mapper::writeValueAsString),
                value.rejection?.reason?.name, mapper.writeValueAsString(value.rejection?.violations.orEmpty())))
            check(count == 1) { "Plan mutation binding or accepted receipt cannot be replaced" }
        }
        override fun updateMedication(value: MedicationView) {
            val old = checkNotNull(medication(value.id))
            check(old.elderId == value.elderId && old.photoId == value.photoId && old.active == value.active)
            check(update("UPDATE medication SET display_name = ?, dosage_text = ?, instructions = ? WHERE id = ?",
                listOf(value.displayName, value.dosageText, value.instructions, UUID.fromString(value.id))) == 1)
        }
        override fun updatePlan(value: ReminderPlanView, expectedRevision: Long) {
            val old = checkNotNull(plan(value.id))
            check(old.revision == expectedRevision && expectedRevision < Long.MAX_VALUE && value.revision == expectedRevision + 1)
            check(old.elderId == value.elderId && old.medicationId == value.medicationId && (!value.active || old.active))
            check(update("""
                UPDATE reminder_plan SET revision = ?, local_times = CAST(? AS text[]), start_date = ?, end_date = ?,
                    execution_time_zone_id = ?, active = ?, effective_from = ?, confirmed_at = ? WHERE id = ? AND revision = ?
            """.trimIndent(), listOf(value.revision, arrayLiteral(value.localTimes), value.startDate, value.endDate,
                value.executionTimeZoneId, value.active, value.effectiveFromEpochMillis, value.confirmedAtEpochMillis,
                UUID.fromString(value.id), expectedRevision)) == 1)
        }

        override fun confirmationOperation(key: PlanConfirmationKey): PlanConfirmationOperation? = query(
            "SELECT * FROM plan_confirmation_operation WHERE actor_id = ? AND operation_id = ?", listOf(key.actorId, key.operationId),
        ) { row ->
            val payload = row.getString("receipt_payload")
            val receipt = payload?.let { PlanConfirmationCopies.receipt(mapper.readValue(it, ConfirmReminderPlanResponse::class.java)) }
            val rejection = row.getString("rejection_reason")?.let { reason ->
                PlanConfirmationResult.Rejected(PlanConfirmationRejection.valueOf(reason),
                    mapper.readValue(row.getString("violations"), Array<ConfirmationViolation>::class.java).toList())
            }
            PlanConfirmationOperation(row.getString("installation_id"), row.getString("elder_id"), row.getString("fingerprint"), receipt, rejection)
        }.singleOrNull()

        override fun putConfirmationOperation(key: PlanConfirmationKey, value: PlanConfirmationOperation) {
            val receipt = value.receipt
            var receiptId: UUID? = null
            if (receipt != null) {
                check(receipt.medication.elderId == value.elderId && receipt.plan.elderId == value.elderId)
                check(receipt.plan.medicationId == receipt.medication.id && receipt.plan.revision == 1L)
                check(medication(receipt.medication.id) == receipt.medication && plan(receipt.plan.id) == receipt.plan)
                check(revision(receipt.plan.id, 1) == PlanConfirmationCopies.initialRevision(receipt))
                receiptId = query("SELECT initial_receipt_id FROM medication WHERE id = ?", listOf(UUID.fromString(receipt.medication.id))) {
                    it.getObject(1, UUID::class.java)
                }.single()
            }
            val count = update("""
                INSERT INTO plan_confirmation_operation AS current
                    (actor_id, operation_id, installation_id, elder_id, fingerprint, receipt_id, medication_id, plan_id,
                     receipt_revision, receipt_payload, rejection_reason, violations)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb))
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET
                    receipt_id = EXCLUDED.receipt_id, medication_id = EXCLUDED.medication_id, plan_id = EXCLUDED.plan_id,
                    receipt_revision = EXCLUDED.receipt_revision, receipt_payload = EXCLUDED.receipt_payload,
                    rejection_reason = EXCLUDED.rejection_reason, violations = EXCLUDED.violations
                WHERE current.installation_id = EXCLUDED.installation_id AND current.elder_id = EXCLUDED.elder_id
                  AND current.fingerprint = EXCLUDED.fingerprint AND
                    (current.receipt_id IS NULL OR (current.receipt_id = EXCLUDED.receipt_id
                     AND current.medication_id = EXCLUDED.medication_id AND current.plan_id = EXCLUDED.plan_id
                     AND current.receipt_revision = EXCLUDED.receipt_revision AND current.receipt_payload = EXCLUDED.receipt_payload
                     AND EXCLUDED.rejection_reason IS NULL AND EXCLUDED.violations = '[]'::jsonb))
            """.trimIndent(), listOf(key.actorId, key.operationId, value.installationId, value.elderId, value.fingerprint,
                receiptId, receipt?.medication?.id?.let(UUID::fromString), receipt?.plan?.id?.let(UUID::fromString),
                receipt?.plan?.revision, receipt?.let(mapper::writeValueAsString), value.rejection?.reason?.name,
                mapper.writeValueAsString(value.rejection?.violations.orEmpty())))
            check(count == 1) { "Plan confirmation binding or accepted receipt cannot be replaced" }
        }

        override fun insertMedication(value: MedicationView) {
            val photoId = checkNotNull(value.photoId)
            check(value.active && readyPhoto(photoId)?.view?.elderId == value.elderId)
            update("""
                INSERT INTO medication (id, elder_id, display_name, dosage_text, instructions, photo_id, active, initial_receipt_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), listOf(UUID.fromString(value.id), value.elderId, value.displayName, value.dosageText,
                value.instructions, UUID.fromString(photoId), value.active, UUID.randomUUID()))
        }

        override fun insertPlan(value: ReminderPlanView) {
            check(value.revision == 1L && value.active && medication(value.medicationId)?.elderId == value.elderId)
            check(ReminderPlanInputValidator.validate(ReminderPlanDraft(value.localTimes, value.startDate, value.executionTimeZoneId, value.endDate)).isEmpty())
            update("""
                INSERT INTO reminder_plan (id, elder_id, medication_id, revision, local_times, start_date, end_date,
                    execution_time_zone_id, active, effective_from, confirmed_at, initial_receipt_id)
                SELECT ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, initial_receipt_id FROM medication WHERE id = ?
            """.trimIndent(), listOf(UUID.fromString(value.id), value.elderId, UUID.fromString(value.medicationId), value.revision,
                arrayLiteral(value.localTimes), value.startDate, value.endDate, value.executionTimeZoneId, value.active,
                value.effectiveFromEpochMillis, value.confirmedAtEpochMillis, UUID.fromString(value.medicationId)))
        }

        override fun insertRevision(value: PlanRevisionView) {
            val currentPlan = checkNotNull(plan(value.id)); val currentMedication = checkNotNull(medication(value.medicationId))
            check(value == PlanConfirmationCopies.initialRevision(ConfirmReminderPlanResponse(currentMedication, currentPlan)))
            update("""
                INSERT INTO reminder_plan_revision (plan_id, revision, elder_id, medication_id, local_times, start_date,
                    end_date, execution_time_zone_id, active, effective_from, confirmed_at, display_name, dosage_text, instructions)
                VALUES (?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(), listOf(UUID.fromString(value.id), value.revision, value.elderId, UUID.fromString(value.medicationId),
                arrayLiteral(value.localTimes), value.startDate, value.endDate, value.executionTimeZoneId, value.active,
                value.effectiveFromEpochMillis, value.confirmedAtEpochMillis, value.medicationSnapshot.displayName,
                value.medicationSnapshot.dosageText, value.medicationSnapshot.instructions))
        }

        override fun medications(elderId: String): List<MedicationView> = query("SELECT * FROM medication WHERE elder_id = ? ORDER BY id", listOf(elderId), ::readMedication)
        override fun plans(elderId: String): List<ReminderPlanView> = query("SELECT * FROM reminder_plan WHERE elder_id = ? ORDER BY id", listOf(elderId), ::readPlan)
        override fun revisions(elderId: String): List<PlanRevisionView> = query("SELECT * FROM reminder_plan_revision WHERE elder_id = ? ORDER BY plan_id, revision", listOf(elderId), ::readRevision)
        override fun medication(id: String) = query("SELECT * FROM medication WHERE id = ?", listOf(UUID.fromString(id)), ::readMedication).singleOrNull()
        override fun plan(id: String) = query("SELECT * FROM reminder_plan WHERE id = ?", listOf(UUID.fromString(id)), ::readPlan).singleOrNull()
        private fun revision(id: String, revision: Long) = query("SELECT * FROM reminder_plan_revision WHERE plan_id = ? AND revision = ?", listOf(UUID.fromString(id), revision), ::readRevision).singleOrNull()
        private fun arrayLiteral(times: List<String>): String {
            check(times.isNotEmpty() && times.all { it.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) })
            return times.joinToString(",", "{", "}") // bound as one text parameter, never interpolated SQL
        }
        private fun readTimes(row: ResultSet): List<String> = row.getArray("local_times").let { values ->
            try { (values.array as Array<*>).map { it as String } } finally { values.free() }
        }
        private fun readMedication(row: ResultSet) = MedicationView(row.getString("id"), row.getString("elder_id"),
            row.getString("display_name"), row.getString("dosage_text"), row.getBoolean("active"), row.getString("instructions"), row.getString("photo_id"))
        private fun readPlan(row: ResultSet) = PlanConfirmationCopies.plan(ReminderPlanView(row.getString("id"), row.getString("elder_id"),
            row.getString("medication_id"), row.getLong("revision"), readTimes(row), row.getString("start_date"), row.getString("execution_time_zone_id"),
            row.getBoolean("active"), row.getLong("effective_from"), row.getLong("confirmed_at"), row.getString("end_date")))
        private fun readRevision(row: ResultSet) = PlanConfirmationCopies.revision(PlanRevisionView(row.getString("plan_id"), row.getString("elder_id"),
            row.getString("medication_id"), row.getLong("revision"), readTimes(row), row.getString("start_date"), row.getString("execution_time_zone_id"),
            row.getBoolean("active"), row.getLong("effective_from"), row.getLong("confirmed_at"),
            MedicationText(row.getString("display_name"), row.getString("dosage_text"), row.getString("instructions")), row.getString("end_date")))

        override fun photoOperation(key: PhotoUploadKey): PhotoUploadOperation? = query(
            "SELECT * FROM photo_upload_operation WHERE actor_id = ? AND operation_id = ?",
            listOf(key.actorId, key.operationId),
        ) { row ->
            // These receipt columns retain the original POST result, independently of resource reads.
            val receipt = row.getString("receipt_photo_id")?.let { id ->
                MedicationPhotoView(id, row.getString("receipt_elder_id"), row.getString("receipt_content_type"),
                    row.getLong("receipt_created_at"))
            }
            PhotoUploadOperation(row.getString("installation_id"), row.getString("elder_id"),
                row.getString("fingerprint"), row.getObject("photo_id", UUID::class.java),
                PhotoUploadState.valueOf(row.getString("state")), receipt,
                row.getString("rejection_reason")?.let { PhotoInputRejection.valueOf(it) })
        }.singleOrNull()

        override fun putPhotoOperation(key: PhotoUploadKey, value: PhotoUploadOperation) {
            val receipt = value.receipt
            when (value.state) {
                PhotoUploadState.STAGING -> check(receipt == null && value.rejection == null)
                PhotoUploadState.REJECTED -> check(receipt == null && value.rejection != null)
                PhotoUploadState.READY -> {
                    check(receipt != null && value.rejection == null)
                    check(receipt.id == value.photoId.toString() && receipt.elderId == value.elderId)
                    check(readyPhoto(receipt.id)?.view == receipt)
                }
            }
            val count = update("""
                INSERT INTO photo_upload_operation AS current
                    (actor_id, operation_id, installation_id, elder_id, fingerprint, photo_id, state,
                     receipt_photo_id, receipt_elder_id, receipt_content_type, receipt_created_at, rejection_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET
                    state = EXCLUDED.state, receipt_photo_id = EXCLUDED.receipt_photo_id,
                    receipt_elder_id = EXCLUDED.receipt_elder_id, receipt_content_type = EXCLUDED.receipt_content_type,
                    receipt_created_at = EXCLUDED.receipt_created_at, rejection_reason = EXCLUDED.rejection_reason
                WHERE current.installation_id = EXCLUDED.installation_id AND current.elder_id = EXCLUDED.elder_id
                  AND current.fingerprint = EXCLUDED.fingerprint AND current.photo_id = EXCLUDED.photo_id
                  AND (current.state <> 'READY' OR
                    (EXCLUDED.state = 'READY' AND current.receipt_photo_id = EXCLUDED.receipt_photo_id
                     AND current.receipt_elder_id = EXCLUDED.receipt_elder_id
                     AND current.receipt_content_type = EXCLUDED.receipt_content_type
                     AND current.receipt_created_at = EXCLUDED.receipt_created_at
                     AND EXCLUDED.rejection_reason IS NULL))
            """.trimIndent(), listOf(key.actorId, key.operationId, value.installationId, value.elderId,
                value.fingerprint, value.photoId, value.state.name, receipt?.let { value.photoId }, receipt?.elderId,
                receipt?.contentType, receipt?.createdAtEpochMillis, value.rejection?.name))
            check(count == 1) { "Photo operation binding or accepted receipt cannot be replaced" }
        }

        override fun readyPhoto(id: String): ReadyMedicationPhoto? {
            val photoId = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { return null }
            return query("SELECT * FROM medication_photo WHERE id = ?", listOf(photoId)) { row ->
                ReadyMedicationPhoto(readPhoto(row), PhotoObjectKey(row.getObject("object_id", UUID::class.java),
                    row.getLong("byte_count"), row.getString("sha256")))
            }.singleOrNull()
        }

        override fun readyPhotos(elderId: String): List<MedicationPhotoView> = query(
            "SELECT * FROM medication_photo WHERE elder_id = ? ORDER BY created_at, id", listOf(elderId), ::readPhoto,
        )

        override fun publishPhoto(value: ReadyMedicationPhoto) {
            val view = value.view
            val objectKey = value.objectKey
            check(view.id == objectKey.objectId.toString())
            val count = update("""
                INSERT INTO medication_photo AS current
                    (id, elder_id, content_type, created_at, object_id, byte_count, sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id
                WHERE current.elder_id = EXCLUDED.elder_id AND current.content_type = EXCLUDED.content_type
                  AND current.created_at = EXCLUDED.created_at AND current.object_id = EXCLUDED.object_id
                  AND current.byte_count = EXCLUDED.byte_count AND current.sha256 = EXCLUDED.sha256
            """.trimIndent(), listOf(objectKey.objectId, view.elderId, view.contentType, view.createdAtEpochMillis,
                objectKey.objectId, objectKey.byteCount, objectKey.sha256))
            check(count == 1) { "Published photo cannot be replaced" }
        }

        private fun readPhoto(row: ResultSet) = MedicationPhotoView(row.getString("id"), row.getString("elder_id"),
            row.getString("content_type"), row.getLong("created_at"))

        private companion object { val mapper = jacksonObjectMapper() }

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
