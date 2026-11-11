package com.group33.server.photo.storage

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
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Redacted shape observed against the isolated real provider: HTTP400 + code NoSuchKey + statusCode404. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SupabaseLegacyMissingTest {
    private lateinit var fixture: SupabaseStorageFixture
    private val bytes = "FICTIONAL LEGACY OBJECT".toByteArray()
    private fun key() = PhotoObjectKey(UUID.randomUUID(), bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
    @BeforeAll fun start(@TempDir directory: Path) { fixture=SupabaseStorageFixture(directory) }
    @AfterAll fun close() { fixture.close() }
    @BeforeEach fun reset() { fixture.reset() }
    @AfterEach fun idle() { fixture.awaitIdle() }

    @ParameterizedTest @ValueSource(strings=["404","\"404\""])
    fun `explicit legacy missing status permits original first upload and byte verified readback`(reported: String) {
        fixture.missingStatus=400
        fixture.missingBody="""{"statusCode":$reported,"error":"not_found","code":"NoSuchKey","message":"Fictional missing object"}"""
        val store=fixture.store();val key=key()
        val missing=assertThrows(PhotoObjectException::class.java){store.read(key)}
        assertEquals(PhotoObjectFailure.MISSING,missing.failure)
        assertEquals(key,store.putIfAbsent(key,bytes.inputStream()))
        assertArrayEquals(bytes,store.read(key))
        assertEquals(1,fixture.calls.count{it.method=="POST"})
        assertEquals(key,store.putIfAbsent(key,bytes.inputStream()))
        assertEquals(1,fixture.calls.count{it.method=="POST"})
    }
    @ParameterizedTest @ValueSource(strings=["no-status","wrong-status","decimal-status","padded-status","boolean-status",
        "null-status","no-code","bucket-code","malformed","duplicate-status","trailing","server-status","denied-status"])
    fun `legacy compatibility never treats bucket auth malformed or unproven errors as missing`(kind: String) {
        fixture.getStatus=if(kind=="server-status")503 else if(kind=="denied-status")403 else 400
        fixture.getBody=when(kind){
            "no-status" -> """{"code":"NoSuchKey"}"""
            "wrong-status" -> """{"code":"NoSuchKey","statusCode":"403"}"""
            "decimal-status" -> """{"code":"NoSuchKey","statusCode":404.0}"""
            "padded-status" -> """{"code":"NoSuchKey","statusCode":" 404"}"""
            "boolean-status" -> """{"code":"NoSuchKey","statusCode":true}"""
            "null-status" -> """{"code":"NoSuchKey","statusCode":null}"""
            "no-code" -> """{"error":"not_found","statusCode":"404"}"""
            "bucket-code" -> """{"code":"NoSuchBucket","statusCode":"404"}"""
            "malformed" -> "not json"
            "duplicate-status" -> """{"code":"NoSuchKey","statusCode":"403","statusCode":"404"}"""
            "trailing" -> """{"code":"NoSuchKey","statusCode":"404"} {}"""
            else -> """{"code":"NoSuchKey","statusCode":"404"}"""
        }
        val failure=assertThrows(PhotoObjectException::class.java){fixture.store().putIfAbsent(key(),bytes.inputStream())}
        assertEquals(PhotoObjectFailure.UNAVAILABLE,failure.failure)
        assertEquals(0,fixture.calls.count{it.method=="POST"})
    }
}
