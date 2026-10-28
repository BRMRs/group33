package com.group33.server.occurrence

import com.group33.server.plan.PlanMutationStore
import com.group33.server.plan.PlanMutationTransaction
import com.group33.shared.contract.*
import java.util.Collections
import java.util.UUID

internal data class RegistrationKey(val actorId: String, val operationId: UUID) {
    override fun toString() = "RegistrationKey(REDACTED)"
}
internal class RegistrationBinding(val installationId: String, val elderId: String, val request: OccurrenceRegistrationRequest) {
    fun matches(other: RegistrationBinding) = installationId == other.installationId && elderId == other.elderId && request == other.request
    override fun toString() = "RegistrationBinding(REDACTED)"
}
internal class RegistrationOperation(val binding: RegistrationBinding, val response: OccurrenceRegistrationResponse? = null,
    val rejection: RegistrationResult.Rejected? = null) {
    init { require((response == null) != (rejection == null)) }
    override fun toString() = "RegistrationOperation(REDACTED)"
}
internal class RegisteredOccurrence(val installationId: String, val origin: RegistrationKey, val receipt: OccurrenceRegistrationReceipt) {
    override fun toString() = "RegisteredOccurrence(REDACTED)"
}

/** Same guard/transaction as retained confirmed revisions and prospective plan mutations. */
internal interface OccurrenceRegistrationStore : PlanMutationStore {
    fun <T> registrationTransaction(work: (OccurrenceRegistrationTransaction) -> T): T
}
internal interface OccurrenceRegistrationTransaction : PlanMutationTransaction {
    fun registrationOperation(key: RegistrationKey): RegistrationOperation?
    fun putRegistrationOperation(key: RegistrationKey, value: RegistrationOperation)
    fun occurrence(key: OccurrenceKey): RegisteredOccurrence?
    fun generation(actorId: String, generationId: UUID): RegisteredOccurrence?
    fun insertOccurrence(value: RegisteredOccurrence)
}

internal object RegistrationCopies {
    fun request(value: OccurrenceRegistrationRequest) = value.copy(items = Collections.unmodifiableList(value.items.toList()))
    fun receipt(value: OccurrenceRegistrationReceipt) = value.copy(event = value.event.copy(
        lateTakenReports = Collections.unmodifiableList(value.event.lateTakenReports.toList())))
    fun response(value: OccurrenceRegistrationResponse) = value.copy(items = Collections.unmodifiableList(value.items.map(::receipt)))
    fun operation(value: RegistrationOperation) = RegistrationOperation(
        RegistrationBinding(value.binding.installationId, value.binding.elderId, request(value.binding.request)),
        value.response?.let(::response), value.rejection?.let { it.copy(violations = it.violations.toList()) })
    fun occurrence(value: RegisteredOccurrence) = RegisteredOccurrence(value.installationId, value.origin, receipt(value.receipt))
}
