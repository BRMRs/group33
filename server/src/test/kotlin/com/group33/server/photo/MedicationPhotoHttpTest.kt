package com.group33.server.photo

import com.fasterxml.jackson.databind.ObjectMapper
import com.group33.server.pairing.PairingHttpFixture
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.ItemsResponse
import com.group33.shared.contract.MedicationPhotoView
import com.group33.shared.model.ApiError
import com.group33.shared.model.FieldError
import com.group33.shared.model.UserRole
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** Real Boot filters, signed anonymous sessions, pairing services and private local object IO.
 * MockMvc builds multipart parts directly; real container parsing/byte limits require the TCP probe.
 */
@SpringBootTest(properties = ["app.photo.enabled=true", "app.persistence.mode=memory"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
internal class MedicationPhotoHttpTest(@Autowired private val mvc: MockMvc) {
    private val http = PairingHttpFixture(mvc)
    private val mapper = ObjectMapper()
    private fun elder() = http.login(UserRole.ELDER)
    private fun caregiver() = http.login(UserRole.CAREGIVER)
    private fun path(elderId: String) = "/api/v1/elders/$elderId/medication-photos"
    private fun content(id: String) = "/api/v1/medication-photos/$id/content"
    private fun key() = UUID.randomUUID().toString()
    private fun file(bytes: ByteArray = png(), type: String? = "image/png", name: String = "fictional.png", field: String = "file") =
        MockMultipartFile(field, name, type, bytes)
    private fun upload(session: PairingHttpFixture.Session, elderId: String = session.id, operation: String? = key(),
                       parts: List<MockMultipartFile> = listOf(file()), token: String? = session.token,
                       query: Boolean = false, text: Boolean = false, duplicate: Boolean = false,
                       boundary: String = "local-test-boundary"): MockHttpServletResponse {
        val request = MockMvcRequestBuilders.multipart(path(elderId) + if (query) "?actorId=spoof" else "")
        parts.forEach { request.file(it) }
        request.contentType(MediaType.parseMediaType("multipart/form-data; boundary=$boundary"))
        if (operation != null) {
            request.header(ContractProtocol.IDEMPOTENCY_HEADER, operation)
            if (duplicate) request.header(ContractProtocol.IDEMPOTENCY_HEADER, operation)
        }
        if (token != null) request.header("Authorization", "Bearer $token")
        if (text) request.param("elderId", "spoof")
        return mvc.perform(request).andReturn().response
    }
    private fun photo(response: MockHttpServletResponse): MedicationPhotoView {
        assertEquals(201, response.status)
        http.noStore(response)
        val tree = mapper.readTree(response.contentAsString)
        assertEquals(setOf("id", "elderId", "contentType", "createdAtEpochMillis"), tree.fieldNames().asSequence().toSet())
        val view = http.json.decodeFromString<MedicationPhotoView>(response.contentAsString)
        assertNotNull(UUID.fromString(view.id))
        assertFalse(response.contentAsString.contains("objectId"))
        assertFalse(response.contentAsString.contains(root.toString()))
        return view
    }
    private fun list(session: PairingHttpFixture.Session, elderId: String = session.id): List<MedicationPhotoView> {
        val response = http.call(session, "GET", path(elderId), null)
        assertEquals(200, response.status); http.noStore(response)
        assertEquals(setOf("items"), mapper.readTree(response.contentAsString).fieldNames().asSequence().toSet())
        return http.json.decodeFromString<ItemsResponse<MedicationPhotoView>>(response.contentAsString).items
    }
    private fun bytes(session: PairingHttpFixture.Session, id: String, expected: ByteArray) {
        val response = http.call(session, "GET", content(id), null)
        assertEquals(200, response.status); http.noStore(response)
        assertEquals("image/png", response.contentType)
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertArrayEquals(expected, response.contentAsByteArray)
    }
    private fun paired(e: PairingHttpFixture.Session, c: PairingHttpFixture.Session): String {
        val pending = http.pending(e, c)
        return http.relationship(http.call(e, "POST", "${PairingHttpFixture.REQUESTS}/${pending.id}/accept")).id
    }

    @Test fun `upload returns only shared metadata and authenticated content preserves exact bytes`() {
        val e = elder(); val source = png(); assertTrue(list(e).isEmpty())
        val value = photo(upload(e, parts = listOf(file(source))))
        assertEquals(e.id, value.elderId); assertEquals("image/png", value.contentType)
        assertEquals(listOf(value), list(e)); bytes(e, value.id, source)
    }

    @Test fun `same operation replays original receipt despite filename boundary MIME parameters and UUID case`() {
        val e = elder(); val source = png(); val op = key()
        val original = photo(upload(e, operation = op.uppercase(), parts = listOf(file(source, "IMAGE/PNG; charset=UTF-8", "../../fictional.png"))))
        val replay = photo(upload(e, operation = op, parts = listOf(file(source, "image/png", "renamed.png")), boundary = "different-boundary"))
        assertEquals(original, replay); assertEquals(listOf(original), list(e)); bytes(e, original.id, source)
    }

    @Test fun `same accepted key binds original bytes and controlled type while a distinct key is a new intent`() {
        val e = elder(); val op = key(); val original = photo(upload(e, operation = op))
        http.failure(upload(e, operation = op, parts = listOf(file(png(3)))), 409, "IDEMPOTENCY_KEY_REUSED")
        http.failure(upload(e, operation = op, parts = listOf(file(type = "image/jpeg"))), 409, "IDEMPOTENCY_KEY_REUSED")
        val other = photo(upload(e)); assertNotEquals(original.id, other.id)
        assertEquals(setOf(original, other), list(e).toSet())
    }

    @Test fun `current active caregiver reads existing photo and revoke immediately removes list and content access`() {
        val e = elder(); val c = caregiver(); val source = png(); val value = photo(upload(e, parts = listOf(file(source))))
        http.failure(http.call(c, "GET", path(e.id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "GET", content(value.id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
        val relationshipId = paired(e, c)
        assertEquals(listOf(value), list(c, e.id)); bytes(c, value.id, source)
        http.relationship(http.call(c, "POST", "/api/v1/care-relationships/$relationshipId/revoke"))
        http.failure(http.call(c, "GET", path(e.id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(c, "GET", content(value.id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
        assertEquals(listOf(value), list(e)); bytes(e, value.id, source)
    }

    @Test fun `pending request never grants photo access and unrelated users see generic inaccessible errors`() {
        val e = elder(); val c = caregiver(); val stranger = elder(); val value = photo(upload(e))
        http.pending(e, c)
        for (caller in listOf(c, stranger)) {
            http.failure(http.call(caller, "GET", path(e.id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
            for (id in listOf(value.id, "missing", key()))
                http.failure(http.call(caller, "GET", content(id), null), 404, "RESOURCE_NOT_ACCESSIBLE")
        }
    }

    @Test fun `JWT required on every photo route and upload identity cannot be supplied as another elder`() {
        val e = elder(); val other = elder(); val c = caregiver(); val value = photo(upload(e))
        for (token in listOf(null, "invalid")) {
            val denied = upload(e, token = token)
            http.failure(denied, 401, "UNAUTHENTICATED"); assertNotNull(denied.getHeader("WWW-Authenticate"))
            http.failure(http.call(e, "GET", path(e.id), null, token = token), 401, "UNAUTHENTICATED")
            http.failure(http.call(e, "GET", content(value.id), null, token = token), 401, "UNAUTHENTICATED")
        }
        http.failure(upload(other, elderId = e.id), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(upload(c, elderId = e.id), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(upload(c), 403, "ROLE_NOT_ALLOWED")
        assertEquals(listOf(value), list(e))
    }

    @Test fun `business byte limit binds rejected intent and cannot hide an accepted key conflict`() {
        val e = elder(); val op = key(); val oversized = ByteArray(PhotoInputValidator.MAX_FILE_BYTES + 1)
        repeat(2) {
            http.failure(upload(e, operation = op, parts = listOf(file(oversized))), 413, "PHOTO_TOO_LARGE")
        }
        assertTrue(list(e).isEmpty())
        http.failure(upload(e, operation = op), 409, "IDEMPOTENCY_KEY_REUSED")
        val acceptedKey = key(); val accepted = photo(upload(e, operation = acceptedKey))
        http.failure(upload(e, operation = acceptedKey, parts = listOf(file(oversized))), 409, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(listOf(accepted), list(e))
    }

    @Test fun `unsupported or missing declared image type has 415 and rejected intent remains payload bound`() {
        val e = elder(); val op = key()
        for (type in listOf("image/gif", "image/*", "application/octet-stream", null))
            http.failure(upload(e, parts = listOf(file(type = type))), 415, "PHOTO_TYPE_UNSUPPORTED")
        http.failure(upload(e, operation = op, parts = listOf(file(type = "image/gif"))), 415, "PHOTO_TYPE_UNSUPPORTED")
        http.failure(upload(e, operation = op, parts = listOf(file(type = "image/gif"))), 415, "PHOTO_TYPE_UNSUPPORTED")
        http.failure(upload(e, operation = op), 409, "IDEMPOTENCY_KEY_REUSED")
        assertTrue(list(e).isEmpty())
    }

    @Test fun `invalid actual bytes and excessive dimensions return scoped 422 validation errors`() {
        val e = elder()
        for ((source, code) in listOf(byteArrayOf() to "INVALID_IMAGE", "fictional text".toByteArray() to "INVALID_IMAGE",
            dimensions(png(), 20_000_001, 1) to "TOO_MANY_PIXELS")) {
            val response = upload(e, parts = listOf(file(source)))
            assertEquals(422, response.status); http.noStore(response)
            val error = http.json.decodeFromString<ApiError>(response.contentAsString)
            assertEquals("VALIDATION_FAILED", error.code); assertFalse(error.retryable)
            assertEquals(listOf(FieldError("file", code)), error.fieldErrors)
            assertEquals(error.requestId, response.getHeader("X-Request-ID")); assertNull(error.reason)
            assertNotNull(UUID.fromString(error.requestId))
            assertFalse(response.contentAsString.contains(root.toString()))
        }
        assertTrue(list(e).isEmpty())
    }

    @Test fun `multipart requires exactly one file part named file and forbids extra text or query fields`() {
        val e = elder()
        for (parts in listOf(emptyList(), listOf(file(field = "photo")), listOf(file(), file()), listOf(file(), file(field = "extra"))))
            http.failure(upload(e, parts = parts), 400, "MALFORMED_REQUEST")
        http.failure(upload(e, query = true), 400, "MALFORMED_REQUEST")
        http.failure(upload(e, text = true), 400, "MALFORMED_REQUEST")
        assertTrue(list(e).isEmpty())
    }

    @Test fun `operation header must be a single complete UUID before accepting multipart intent`() {
        val e = elder()
        for (op in listOf(null, "", "invalid", "1-1-1-1-1", key() + "," + key()))
            http.failure(upload(e, operation = op), 400, "MALFORMED_REQUEST")
        http.failure(upload(e, duplicate = true), 400, "MALFORMED_REQUEST")
        assertTrue(list(e).isEmpty())
    }

    @Test fun `read query fields cannot change targets and private object paths are not public routes`() {
        val e = elder(); val value = photo(upload(e))
        http.failure(http.call(e, "GET", path(e.id) + "?elderId=spoof", null), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "GET", content(value.id) + "?actorId=spoof", null), 400, "MALFORMED_REQUEST")
        http.failure(http.call(e, "GET", "/${value.id}.blob", null), 404, "RESOURCE_NOT_ACCESSIBLE")
        http.failure(http.call(e, "GET", "/objects/${value.id}.blob", null), 404, "RESOURCE_NOT_ACCESSIBLE")
    }

    private fun png(size: Int = 2): ByteArray {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        return try { ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "PNG", output)); output.toByteArray() } }
            finally { image.flush() }
    }
    private fun dimensions(original: ByteArray, width: Int, height: Int) = original.copyOf().also {
        ByteBuffer.wrap(it).putInt(16, width).putInt(20, height)
        ByteBuffer.wrap(it).putInt(29, CRC32().apply { update(it, 12, 17) }.value.toInt())
    }
    companion object {
        private val root = Files.createTempDirectory("s2-photo-http-")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) { registry.add("app.photo.root") { root.toString() } }
        @JvmStatic @AfterAll fun cleanup() { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
