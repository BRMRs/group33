package com.group33.medicationreminder.core.data.contract

import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.MedicationPhotoView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E Gate 1 caller/mapping review. Scripted doubles prove typed pre-send failure only;
 * they do not prove real Storage upload, Room persistence, or device capture.
 */
class ElderContractReviewTest {
    private val json = ContractProtocol.json
    private val examples = json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/examples.json")).bufferedReader().use { it.readText() },
    ).jsonObject
    private val photo: MedicationPhotoView = json.decodeFromJsonElement(examples.getValue("photo"))
    private val elderScope = RepositoryScope("cloud-demo", "elder-install-1", "elder-1")
    private val operationId = "00000000-0000-4000-8000-0000000000e1"
    private val command = CommandContext(operationId, elderScope, OnlineCommandKind.SUBMIT_PHOTO, null)

    @Test
    fun `P01 R04 unreadable photo source yields NotSubmitted SOURCE_UNAVAILABLE without transport`() = runTest {
        var openAttempts = 0
        val failingSource = object : PhotoSource {
            override val contentType = "image/jpeg"
            override val byteCount = 3L
            override val sha256 = sha256Hex(byteArrayOf(1, 2, 3))
            override fun openStream(): InputStream {
                openAttempts++
                throw IOException("retained source unavailable for test")
            }
        }
        val repository = PhotoSubmitProbe(photo)
        val result = repository.submit(failingSource, command)

        assertTrue(result is OnlineCommandResult.NotSubmitted)
        result as OnlineCommandResult.NotSubmitted
        assertEquals(operationId, result.operationId)
        assertEquals(OnlineCommandState.NOT_SUBMITTED, result.status)
        assertEquals(LocalFailureCode.SOURCE_UNAVAILABLE, result.error.code)
        assertEquals(1, openAttempts)
        assertEquals(0, repository.transportCalls)
        assertTrue(repository.transmittedPayloads.isEmpty())
    }

    @Test
    fun `P04 R04 readable photo source reaches transport once with retained bytes`() = runTest {
        val bytes = byteArrayOf(1, 2, 3) // not a valid image; upload validators are out of scope here
        var openAttempts = 0
        val readableSource = object : PhotoSource {
            override val contentType = "image/jpeg"
            override val byteCount = bytes.size.toLong()
            override val sha256 = sha256Hex(bytes)
            override fun openStream(): InputStream {
                openAttempts++
                return ByteArrayInputStream(bytes)
            }
        }
        val repository = PhotoSubmitProbe(photo)
        val result = repository.submit(readableSource, command)

        assertTrue(result is OnlineCommandResult.Confirmed)
        result as OnlineCommandResult.Confirmed
        assertEquals(operationId, result.operationId)
        assertEquals(photo, result.value)
        assertEquals(CacheState.APPLIED, result.cacheState)
        assertEquals(1, openAttempts)
        assertEquals(1, repository.transportCalls)
        assertEquals(1, repository.transmittedPayloads.size)
        assertArrayEquals(bytes, repository.transmittedPayloads.single())
        assertSame(readableSource, repository.seenSources.single())
    }

    /** Caller-facing adapter seam only; not a production Room/HTTP registration. */
    private class PhotoSubmitProbe(
        private val accepted: MedicationPhotoView,
    ) : MedicationPhotoRepository {
        var transportCalls = 0
        val transmittedPayloads = mutableListOf<ByteArray>()
        val seenSources = mutableListOf<PhotoSource>()

        override fun observePhotos(elderId: String) = flowOf(
            Cached(emptyList<MedicationPhotoView>(), false, null, true, AccessState.UNKNOWN),
        )

        override suspend fun submit(
            source: PhotoSource,
            command: CommandContext,
        ): OnlineCommandResult<MedicationPhotoView> {
            seenSources += source
            return PhotoSubmitSourceGuard.submitAfterOpeningSource(source, command) { payload ->
                transportCalls++
                transmittedPayloads += payload
                OnlineCommandResult.Confirmed(command.operationId, accepted, CacheState.APPLIED)
            }
        }

        override suspend fun loadContent(photoId: String): Outcome<PhotoContent> =
            error("Not exercised by Elder photo-source review")

        override suspend fun refresh(elderId: String): Outcome<SyncSummary> =
            error("Not exercised by Elder photo-source review")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
