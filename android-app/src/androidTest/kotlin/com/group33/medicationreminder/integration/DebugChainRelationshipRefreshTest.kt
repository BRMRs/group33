package com.group33.medicationreminder.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.group33.medicationreminder.core.data.contract.AccessState
import com.group33.medicationreminder.core.data.contract.Outcome
import com.group33.shared.contract.CareRelationshipStatus
import com.group33.shared.contract.CareRelationshipView
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.contract.MedicationView
import com.group33.shared.contract.PairingRequestView
import com.group33.shared.contract.ReminderPlanCollection
import com.group33.shared.model.ApiError
import com.group33.shared.model.AuthResponse
import com.group33.shared.model.User
import com.group33.shared.model.UserRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repository/Room regressions for relationship history sharing an Elder-keyed cache. */
class DebugChainRelationshipRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val elder = User("elder-repair", "Elder Repair", UserRole.ELDER)
    private val caregiver = User("caregiver-repair", "Caregiver Repair", UserRole.CAREGIVER)

    @Test fun activeRelationshipPreventsOldRevokedHistoryPoisoningInEitherOrder() = runBlocking {
        listOf(
            listOf(relationship("old", CareRelationshipStatus.REVOKED), relationship("new", CareRelationshipStatus.ACTIVE)),
            listOf(relationship("new", CareRelationshipStatus.ACTIVE), relationship("old", CareRelationshipStatus.REVOKED)),
        ).forEach { response ->
            val transport = FixtureTransport().apply { relationships = response }
            val repository = repository(transport)
            try {
                authorizeTarget(repository)
                assertSuccess(repository.refresh())
                assertTargetAccess(repository, AccessState.AUTHORIZED)
            } finally {
                repository.close()
            }
        }
    }

    @Test fun relationshipRefreshCannotRestoreRevokedOrDeniedTargetAuthority() = runBlocking {
        val transport = FixtureTransport()
        val repository = repository(transport)
        try {
            authorizeTarget(repository)

            transport.relationships = listOf(relationship("old", CareRelationshipStatus.REVOKED))
            assertSuccess(repository.refresh())
            assertTargetAccess(repository, AccessState.REVOKED)

            transport.relationships = listOf(relationship("replacement", CareRelationshipStatus.ACTIVE))
            assertSuccess(repository.refresh())
            assertTargetAccess(repository, AccessState.REVOKED)

            // Only successful resource reads may restore resource authority.
            authorizeTarget(repository)
            transport.denyPhotoRead = true
            assertTrue(repository.refresh(elder.id) is Outcome.Failure)
            assertTargetAccess(repository, AccessState.UNKNOWN)

            transport.denyPhotoRead = false
            assertSuccess(repository.refresh())
            assertTargetAccess(repository, AccessState.UNKNOWN)
        } finally {
            repository.close()
        }
    }

    private suspend fun repository(transport: FixtureTransport): DebugChainRepository {
        val repository = DebugChainRepository(
            context,
            "https://relationship-refresh.invalid",
            namespace = "relationship-refresh-${UUID.randomUUID()}",
            http = transport,
        )
        assertSuccess(repository.login(UserRole.CAREGIVER, caregiver.displayName))
        return repository
    }

    private suspend fun authorizeTarget(repository: DebugChainRepository) {
        assertSuccess(repository.refresh(elder.id))
        assertTargetAccess(repository, AccessState.AUTHORIZED)
    }

    private suspend fun assertTargetAccess(repository: DebugChainRepository, expected: AccessState) {
        assertEquals(expected, repository.observePhotos(elder.id).first().access)
        assertEquals(expected, repository.observePlans(elder.id).first().access)
        assertEquals(expected, repository.observeMedications(elder.id).first().access)
    }

    private fun relationship(id: String, status: CareRelationshipStatus) = CareRelationshipView(
        id = id,
        elderId = elder.id,
        caregiverId = caregiver.id,
        elder = elder,
        caregiver = caregiver,
        status = status,
        createdAtEpochMillis = 1L,
        revokedAtEpochMillis = if (status == CareRelationshipStatus.REVOKED) 2L else null,
    )

    private fun <T> assertSuccess(outcome: Outcome<T>): T {
        assertTrue("Expected repository success but got $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private inner class FixtureTransport : ChainHttp("https://relationship-refresh.invalid") {
        var relationships: List<CareRelationshipView> = emptyList()
        var denyPhotoRead = false

        override suspend fun call(
            method: String,
            path: String,
            token: String?,
            operationId: String?,
            json: String?,
            photo: File?,
            photoType: String?,
        ): Reply = when {
            method == "POST" && path == "/api/v1/auth/anonymous" -> reply(
                201,
                AuthResponse("fixture-token", "Bearer", 3_600L, caregiver),
                AuthResponse.serializer(),
            )
            path == "/api/v1/pairing-requests" -> reply(
                200,
                ItemsResponse<PairingRequestView>(emptyList()),
                ItemsResponse.serializer(PairingRequestView.serializer()),
            )
            path == "/api/v1/care-relationships" -> reply(
                200,
                ItemsResponse(relationships),
                ItemsResponse.serializer(CareRelationshipView.serializer()),
            )
            path.endsWith("/medication-photos") && denyPhotoRead -> error(404, "RESOURCE_NOT_ACCESSIBLE")
            path.endsWith("/medication-photos") -> reply(
                200,
                ItemsResponse(listOf(MedicationPhotoView("photo-repair", elder.id, "image/png", 1L))),
                ItemsResponse.serializer(MedicationPhotoView.serializer()),
            )
            path.endsWith("/reminder-plans") -> reply(
                200,
                ReminderPlanCollection(emptyList(), emptyList()),
                ReminderPlanCollection.serializer(),
            )
            path.endsWith("/medications") -> reply(
                200,
                ItemsResponse<MedicationView>(emptyList()),
                ItemsResponse.serializer(MedicationView.serializer()),
            )
            else -> error(500, "UNEXPECTED_FIXTURE_REQUEST")
        }

        private fun <T> reply(status: Int, value: T, serializer: KSerializer<T>): Reply = Reply(
            status,
            ContractProtocol.json.encodeToString(serializer, value).toByteArray(),
            "application/json",
            null,
            null,
        )

        private fun error(status: Int, code: String): Reply {
            val requestId = "fixture-$code"
            val body = ContractProtocol.json.encodeToString(
                ApiError.serializer(),
                ApiError(code, code, false, requestId),
            )
            return Reply(status, body.toByteArray(), "application/json", null, requestId)
        }
    }
}
