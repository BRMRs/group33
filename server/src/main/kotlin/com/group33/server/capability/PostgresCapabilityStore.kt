package com.group33.server.capability

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.shared.contract.ReminderCapabilityReceipt
import com.group33.shared.contract.ReminderCapabilityReport
import com.group33.shared.contract.ReminderCapabilityView
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** Explicit adapter only: caller supplies a private deployment-specific PostgreSQL DataSource.
 * No DDL, HTTP, identity/relationship implementation or Spring registration belongs here.
 */
internal class PostgresCapabilityStore(private val dataSource: DataSource, private val lockWaitMillis: Long = 1_000) : CapabilityStore {
    init { require(lockWaitMillis in 0..30_000) }
    override fun toString() = "PostgresCapabilityStore(REDACTED)"

    override fun <T> transaction(work: (CapabilityTransaction) -> T): T = dataSource.connection.use { connection ->
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        connection.autoCommit = false
        try {
            connection.prepareStatement("SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', '5000ms', true)").use {
                it.setString(1, "${lockWaitMillis}ms"); it.execute()
            }
            val suffix = if (lockWaitMillis == 0L) " NOWAIT" else ""
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM capability_guard WHERE id = 1 FOR UPDATE$suffix").use {
                    check(it.next()) { "Capability Store schema is not initialized" }
                }
            }
            val result = work(Transaction(connection))
            // Validate all pending business/receipt constraints under the SQL budget before COMMIT.
            // A lost final COMMIT acknowledgement still needs original-operation reconciliation.
            connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
            connection.commit()
            result
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: SQLException) { failure.addSuppressed(rollback) }
            if (failure is SQLException && failure.sqlState == "55P03") throw CapabilityStoreBusy()
            throw failure
        }
    }

    private class Transaction(private val db: Connection) : CapabilityTransaction {
        override fun operation(key: CapabilityOperationKey) = query(
            "SELECT * FROM capability_operation WHERE actor_id = ? AND operation_id = ?", listOf(key.actorId, key.operationId),
        ) { row -> CapabilityOperation(
            CapabilityBinding(CapabilityOwner(row.getString("elder_id"), row.getString("installation_id")),
                mapper.readValue(row.getString("report_payload"), ReminderCapabilityReport::class.java), row.getString("canonicalization_version")),
            row.getString("receipt_payload")?.let { mapper.readValue(it, ReminderCapabilityReceipt::class.java).detached() },
            row.getString("rejection_reason")?.let(CapabilityRejection::valueOf),
        ) }.singleOrNull()

        override fun putOperation(key: CapabilityOperationKey, value: CapabilityOperation) {
            val owner = value.binding.owner; val report = value.binding.report; val receipt = value.receipt
            check(key.actorId == owner.elderId && (receipt == null) != (value.rejection == null))
            if (receipt != null) {
                check(report.reportId == key.operationId.toString() && receipt.reportId == report.reportId)
                check(observation(owner, report.observationSequence) == report)
                check(current(owner) == receipt.current && receipt.current.observationSequence >= report.observationSequence)
                if (receipt.applied) check(receipt.current.toReport() == report && receipt.current.receivedAtEpochMillis == receipt.receivedAtEpochMillis)
            }
            check(update("""
                INSERT INTO capability_operation AS current
                    (actor_id, operation_id, elder_id, installation_id, canonicalization_version, report_payload,
                     accepted_report_id, acknowledged_sequence, receipt_current_sequence, receipt_payload, rejection_reason)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (actor_id, operation_id) DO UPDATE SET
                    accepted_report_id = EXCLUDED.accepted_report_id, acknowledged_sequence = EXCLUDED.acknowledged_sequence,
                    receipt_current_sequence = EXCLUDED.receipt_current_sequence, receipt_payload = EXCLUDED.receipt_payload,
                    rejection_reason = EXCLUDED.rejection_reason
                WHERE current.elder_id = EXCLUDED.elder_id AND current.installation_id = EXCLUDED.installation_id
                    AND current.canonicalization_version = EXCLUDED.canonicalization_version AND current.report_payload = EXCLUDED.report_payload
                    AND (current.receipt_payload IS NULL OR (current.receipt_payload = EXCLUDED.receipt_payload
                        AND current.accepted_report_id = EXCLUDED.accepted_report_id AND EXCLUDED.rejection_reason IS NULL))
            """.trimIndent(), listOf(key.actorId, key.operationId, owner.elderId, owner.installationId, value.binding.canonicalizationVersion,
                mapper.writeValueAsString(report), receipt?.let { key.operationId }, receipt?.let { report.observationSequence },
                receipt?.current?.observationSequence, receipt?.let(mapper::writeValueAsString), value.rejection?.name)) == 1) {
                "Capability binding or accepted receipt cannot be replaced"
            }
        }

        override fun current(owner: CapabilityOwner): ReminderCapabilityView? = query(
            "SELECT view_payload FROM capability_current WHERE elder_id = ? AND installation_id = ?", listOf(owner.elderId, owner.installationId),
        ) { mapper.readValue(it.getString(1), ReminderCapabilityView::class.java).detached() }.singleOrNull()

        override fun putCurrent(owner: CapabilityOwner, value: ReminderCapabilityView) {
            check(observation(owner, value.observationSequence) == value.toReport())
            check(update("""
                INSERT INTO capability_current AS current (elder_id, installation_id, observation_sequence, report_id, view_payload)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (elder_id, installation_id) DO UPDATE SET observation_sequence = EXCLUDED.observation_sequence,
                    report_id = EXCLUDED.report_id, view_payload = EXCLUDED.view_payload
                WHERE current.observation_sequence < EXCLUDED.observation_sequence
            """.trimIndent(), listOf(owner.elderId, owner.installationId, value.observationSequence,
                UUID.fromString(value.reportId), mapper.writeValueAsString(value))) == 1) { "Capability sequence must increase" }
        }

        override fun observation(owner: CapabilityOwner, sequence: Long): ReminderCapabilityReport? = query(
            "SELECT report_payload FROM capability_observation WHERE elder_id = ? AND installation_id = ? AND observation_sequence = ?",
            listOf(owner.elderId, owner.installationId, sequence),
        ) { mapper.readValue(it.getString(1), ReminderCapabilityReport::class.java).let { value -> value.copy(reasons = value.reasons.toList()) } }.singleOrNull()

        override fun putObservation(owner: CapabilityOwner, report: ReminderCapabilityReport) {
            val id = UUID.fromString(report.reportId)
            check(id.toString() == report.reportId && report.observationSequence > 0 && report.observedAtEpochMillis >= 0)
            val count = update("""
                INSERT INTO capability_observation (elder_id, installation_id, observation_sequence, report_id, report_payload)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (elder_id, installation_id, observation_sequence) DO NOTHING
            """.trimIndent(), listOf(owner.elderId, owner.installationId, report.observationSequence, id, mapper.writeValueAsString(report)))
            check(count == 1 || observation(owner, report.observationSequence) == report) { "Capability observation is immutable" }
        }

        private fun <T> query(sql: String, values: List<Any?>, read: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
            s.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
        private fun update(sql: String, values: List<Any?>): Int = db.prepareStatement(sql).use { s ->
            values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
        }
        private fun ReminderCapabilityView.toReport() = ReminderCapabilityReport(reportId, observationSequence, observedAtEpochMillis,
            deliveryCondition, notificationPermissionGranted, channelEnabled, exactAlarmAccess, reasons.toList())
        private companion object { val mapper = jacksonObjectMapper() }
    }
}
