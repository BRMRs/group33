package com.group33.server.photo.storage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SupabasePhotoObjectStoreTest {
    private lateinit var fixture: SupabaseStorageFixture
    private val bytes = "fictional photo bytes".toByteArray()
    @BeforeAll fun start(@TempDir directory: Path) { fixture = SupabaseStorageFixture(directory) }
    @AfterAll fun stop() { if (::fixture.isInitialized) fixture.close() }
    @BeforeEach fun reset() { fixture.reset() }
    @AfterEach fun idle() { fixture.awaitIdle() }
    private fun key(data: ByteArray = bytes, id: UUID = UUID.randomUUID()) = PhotoObjectKey(id, data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })
    private fun failure(reason: PhotoObjectFailure, work: () -> Unit) {
        val error = assertThrows(PhotoObjectException::class.java, work)
        assertEquals(reason, error.failure); assertNull(error.cause)
        assertFalse(error.toString().contains(fixture.secret)); assertFalse(error.toString().contains("PRIVATE"))
        assertFalse(error.toString().contains(fixture.origin.toString()))
    }
    private fun input(data: ByteArray = bytes) = ByteArrayInputStream(data)
    private fun unreadable() = object: InputStream() { override fun read(): Int = error("Retry source must remain unread") }

    @Test fun `HTTPS upload and authenticated read preserve bytes key and private wire protocol`() {
        val key = key(); val store = fixture.store()
        assertEquals(key, store.putIfAbsent(key, input())); assertArrayEquals(bytes, store.read(key))
        assertTrue(fixture.calls.all { it.key == fixture.secret && it.authorization == null })
        val post = fixture.calls.single { it.method == "POST" }
        assertEquals("false", post.upsert); assertEquals("application/octet-stream", post.contentType)
        assertEquals("/storage/v1/object/${fixture.bucket}/${key.objectId}.blob", post.path)
        assertTrue(fixture.calls.any { it.path == "/storage/v1/object/authenticated/${fixture.bucket}/${key.objectId}.blob" })
        assertFalse(fixture.calls.any { "/public/" in it.path || "/sign/" in it.path || it.method in listOf("PUT", "DELETE") })
        assertEquals("SupabasePhotoObjectStore(REDACTED)", store.toString())
    }
    @Test fun `fresh adapter reconciles stored retry without reading or closing caller source`() {
        val key = key();fixture.store().putIfAbsent(key, input())
        val source = object: InputStream() { var closed = false; override fun read(): Int = error("Do not read accepted source")
            override fun close() { closed = true } }
        assertEquals(key, fixture.store().putIfAbsent(key, source)); assertFalse(source.closed)
        assertEquals(1, fixture.calls.count { it.method == "POST" })
    }
    @Test fun `caller keeps ownership of source on successful initial upload`() {
        val source = object: ByteArrayInputStream(bytes) { var closed = false; override fun close() { closed = true; super.close() } }
        fixture.store().putIfAbsent(key(), source);assertFalse(source.closed)
    }
    @ParameterizedTest @ValueSource(strings = ["short", "long", "digest", "io"])
    fun `invalid source never uploads and failures omit raw private diagnostics`(kind: String) {
        val source = when(kind) { "short" -> input(bytes.dropLast(1).toByteArray()); "long" -> input(bytes + 1.toByte())
            "digest" -> input(ByteArray(bytes.size)); else -> object: InputStream() { override fun read(): Int = throw IOException("PRIVATE secret payload") } }
        failure(if(kind == "io") PhotoObjectFailure.UNAVAILABLE else PhotoObjectFailure.INTEGRITY_MISMATCH) { fixture.store().putIfAbsent(key(), source) }
        assertTrue(fixture.objects.isEmpty()); assertFalse(fixture.calls.any { it.method == "POST" })
    }
    @Test fun `zero length bulk reads still make bounded progress`() {
        val source = object: ByteArrayInputStream(bytes) { var first = true
            override fun read(buffer: ByteArray, start: Int, count: Int): Int { if(first) { first = false; return 0 };return super.read(buffer,start,count) } }
        val key=key();assertEquals(key,fixture.store().putIfAbsent(key,source));assertArrayEquals(bytes,fixture.store().read(key))
    }
    @Test fun `same UUID different digest conflicts without overwrite or consuming source`() {
        val key=key();fixture.store().putIfAbsent(key,input())
        val other=ByteArray(bytes.size) { 7 };val changed=key(other,key.objectId)
        failure(PhotoObjectFailure.OBJECT_CONFLICT) { fixture.store().putIfAbsent(changed,unreadable()) }
        assertArrayEquals(bytes,fixture.objects["${key.objectId}.blob"]);assertEquals(1,fixture.calls.count { it.method=="POST" })
    }
    @Test fun `read verifies stored length and complete SHA256`() {
        val key=key();fixture.objects["${key.objectId}.blob"]=ByteArray(bytes.size)
        failure(PhotoObjectFailure.INTEGRITY_MISMATCH) { fixture.store().read(key) }
        fixture.objects["${key.objectId}.blob"]=bytes + ByteArray(1024*1024)
        failure(PhotoObjectFailure.INTEGRITY_MISMATCH) { fixture.store().read(key) }
    }
    @ParameterizedTest @ValueSource(strings = ["public", "missing-public", "string-public", "wrong-id", "duplicate", "trailing", "oversized", "malformed"])
    fun `private bucket must be verified before object access or source consumption`(kind: String) {
        fixture.metadata = when(kind) {
            "public" -> fixture.privateBucket().replace("false","true")
            "missing-public" -> """{"id":"${fixture.bucket}"}"""
            "string-public" -> """{"id":"${fixture.bucket}","public":"false"}"""
            "wrong-id" -> """{"id":"wrong","public":false}"""
            "duplicate" -> """{"id":"${fixture.bucket}","public":false,"public":false}"""
            "trailing" -> fixture.privateBucket()+" {}"
            "oversized" -> """{"id":"${fixture.bucket}","public":false,"padding":"${"x".repeat(20000)}"}"""
            else -> "{"
        }
        failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().putIfAbsent(key(),unreadable()) }
        assertEquals(1,fixture.calls.size);assertTrue(fixture.objects.isEmpty())
    }
    @Test fun `bucket becoming public is rechecked on next call`() {
        val key=key();fixture.store().putIfAbsent(key,input());fixture.calls.clear();fixture.metadata=fixture.privateBucket().replace("false","true")
        failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().read(key) };assertEquals(1,fixture.calls.size)
    }
    @ParameterizedTest @ValueSource(ints = [401,403,404,429,500,503])
    fun `bucket errors never masquerade as absent object`(status: Int) {
        fixture.metadataStatus=status;failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().putIfAbsent(key(),unreadable()) }
        assertEquals(1,fixture.calls.size)
    }
    @Test fun `only documented missing key maps to missing while other statuses stay unavailable`() {
        failure(PhotoObjectFailure.MISSING) { fixture.store().read(key()) }
        for ((status,code) in listOf(404 to "NoSuchBucket",404 to "TenantNotFound",404 to "Unknown",403 to "NoSuchKey",401 to "InvalidJWT",429 to "SlowDown",503 to "InternalError")) {
            fixture.getStatus=status;fixture.getCode=code
            failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().read(key()) }
        }
    }
    @ParameterizedTest @ValueSource(strings = ["ack-only", "false-conflict", "denied", "redirect"])
    fun `unproven upload never returns a successful key or follows redirect`(mode: String) {
        fixture.postMode=mode;failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().putIfAbsent(key(),input()) }
        assertFalse(fixture.calls.any { it.path=="/credential-trap" });assertTrue(fixture.objects.isEmpty())
    }
    @Test fun `legacy duplicate reconciles verified bytes without upsert`() {
        fixture.postMode="legacy-duplicate";val key=key();assertEquals(key,fixture.store().putIfAbsent(key,input()))
        assertArrayEquals(bytes,fixture.objects["${key.objectId}.blob"])
    }
    @Test fun `remote write with failed response recovers same identity on explicit retry`() {
        fixture.postMode="persist-then-fail";val key=key()
        failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store().putIfAbsent(key,input()) }
        assertArrayEquals(bytes,fixture.objects["${key.objectId}.blob"])
        assertEquals(key,fixture.store().putIfAbsent(key,unreadable()));assertEquals(1,fixture.calls.count { it.method=="POST" })
    }
    @Test fun `upload acknowledgment with wrong stored bytes is a conflict`() {
        fixture.postMode="wrong-bytes";failure(PhotoObjectFailure.OBJECT_CONFLICT) { fixture.store().putIfAbsent(key(),input()) }
    }
    @Test fun `parallel independent adapters converge on one immutable object`() {
        val key=key();val pool=Executors.newFixedThreadPool(8)
        try {
            val results=(1..8).map { pool.submit<PhotoObjectKey> { fixture.store().putIfAbsent(key,input()) } }.map { it.get(15,TimeUnit.SECONDS) }
            assertTrue(results.all { it==key });assertEquals(1,fixture.objects.size);assertArrayEquals(bytes,fixture.objects.values.single())
            assertTrue(fixture.calls.filter { it.method=="POST" }.all { it.upsert=="false" })
        } finally { pool.shutdownNow() }
    }
    @Test fun `concurrent different content with the same UUID has one immutable winner`() {
        val id=UUID.randomUUID();val other=ByteArray(bytes.size) { 7 };val pool=Executors.newFixedThreadPool(8)
        try {
            val attempts=(1..8).map { i -> pool.submit<Result<PhotoObjectKey>> {
                val data=if(i%2==0) bytes else other
                runCatching { fixture.store().putIfAbsent(key(data,id),input(data)) }
            } }.map { it.get(15,TimeUnit.SECONDS) }
            assertEquals(4,attempts.count { it.isSuccess });assertEquals(4,attempts.count { it.isFailure })
            val stored=fixture.objects["$id.blob"]!!;val accepted=key(stored,id)
            attempts.filter { it.isSuccess }.forEach { assertEquals(accepted,it.getOrThrow()) }
            attempts.filter { it.isFailure }.forEach { assertEquals(PhotoObjectFailure.OBJECT_CONFLICT,
                assertInstanceOf(PhotoObjectException::class.java,it.exceptionOrNull()).failure) }
        } finally { pool.shutdownNow() }
    }
    @Test fun `default client retains certificate validation instead of trusting fixture TLS`() {
        val store=SupabasePhotoObjectStore(fixture.origin,fixture.bucket,fixture.secret,Duration.ofSeconds(3))
        failure(PhotoObjectFailure.UNAVAILABLE) { store.read(key()) };assertTrue(fixture.calls.isEmpty())
    }
    @Test fun `interrupted caller keeps interrupt flag and never receives private diagnostics`() {
        fixture.metadataDelay=1000
        val result=CompletableFuture<Pair<PhotoObjectException,Boolean>>()
        val thread=Thread {
            try { fixture.store().read(key());result.completeExceptionally(AssertionError("Expected interruption")) }
            catch (failure:PhotoObjectException) { result.complete(failure to Thread.currentThread().isInterrupted) }
        }
        thread.start()
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(fixture.calls.isEmpty() && System.nanoTime()<deadline) Thread.sleep(10)
            assertFalse(fixture.calls.isEmpty());thread.interrupt()
            val (error,interrupted)=result.get(2,TimeUnit.SECONDS)
            assertEquals(PhotoObjectFailure.UNAVAILABLE,error.failure);assertNull(error.cause);assertTrue(interrupted)
        } finally { thread.interrupt();thread.join(3000) }
    }
    @Test fun `five MiB limit object uploads and reads within bounded buffers`() {
        val data=ByteArray(PhotoObjectKey.MAX_BYTES.toInt()) { (it%251).toByte() };val key=key(data)
        fixture.store().putIfAbsent(key,input(data));assertArrayEquals(data,fixture.store().read(key))
    }
    @Test fun `request budget covers body completion after response headers`() {
        val key=key();fixture.objects["${key.objectId}.blob"]=bytes;fixture.stallBody=true
        val start=System.nanoTime()
        failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store(Duration.ofMillis(300)).read(key) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1200)
    }
    @Test fun `one budget is shared across metadata lookup upload and verification`() {
        fixture.metadataDelay=150;fixture.getDelay=150;fixture.postDelay=150
        val start=System.nanoTime();failure(PhotoObjectFailure.UNAVAILABLE) { fixture.store(Duration.ofMillis(400)).putIfAbsent(key(),input()) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1400)
    }
    @ParameterizedTest @ValueSource(strings = ["http", "path", "query", "fragment", "userinfo", "bucket", "publishable", "newline-key", "zero-budget", "large-budget", "redirect-client"])
    fun `invalid configuration fails before network without revealing secret`(kind: String) {
        var url=fixture.origin;var bucket=fixture.bucket;var key=fixture.secret;var budget=Duration.ofSeconds(10);var client=fixture.client
        when(kind) {
            "http" -> url=URI("http://127.0.0.1")
            "path" -> url=URI(fixture.origin.toString()+"/storage/v1")
            "query" -> url=URI(fixture.origin.toString()+"?private=value")
            "fragment" -> url=URI(fixture.origin.toString()+"#private")
            "userinfo" -> url=URI("https://private-value@127.0.0.1")
            "bucket" -> bucket="../outside"
            "publishable" -> key="sb_publishable_NOT_A_SERVER_CREDENTIAL"
            "newline-key" -> key+="\r\n"
            "zero-budget" -> budget=Duration.ZERO
            "large-budget" -> budget=Duration.ofSeconds(121)
            else -> client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        }
        val error=assertThrows(IllegalArgumentException::class.java) { SupabasePhotoObjectStore(url,bucket,key,budget,client) }
        assertFalse(error.toString().contains(fixture.secret));assertTrue(fixture.calls.isEmpty())
    }
}
