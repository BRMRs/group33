package com.group33.server.config

import com.group33.server.photo.MedicationPhotoConfiguration
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.SupabasePhotoObjectStore
import com.group33.server.photo.storage.SupabaseStorageFixture
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.Base64

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SupabaseRuntimeConfigurationTest {
    private lateinit var directory: Path
    private lateinit var certificate: Path
    private val project = "abcdefghijklmnopqrst"
    private val marker = "PRIVATE_TEST_CONFIGURATION_NEVER_LOG"
    @BeforeAll fun certificateFixture(@TempDir temporary: Path) {
        directory = temporary
        SupabaseStorageFixture(directory).use { fixture ->
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(directory.resolve("fictional-tls.p12")).use { store.load(it, "fictional-test-only".toCharArray()) }
            val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(store.getCertificate("fixture").encoded)
            certificate = directory.resolve("fictional-ca.crt")
            Files.writeString(certificate, "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n")
            assertTrue(fixture.calls.isEmpty())
        }
    }
    private fun cloud() = MockEnvironment().withProperty("app.persistence.mode", "postgres-supabase")
        .withProperty("app.supabase.project-ref", project)
        .withProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co:5432/postgres")
        .withProperty("app.persistence.username", "group33_fixture_app")
        .withProperty("app.persistence.password", marker).withProperty("app.persistence.schema", "group33_fixture")
        .withProperty("app.persistence.ssl-root-cert", certificate.toString())

    @Test fun `direct cloud configuration forces verified TLS and separate credentials without opening a connection`() {
        val source = PersistenceDataSourceFactory.create(cloud(), "postgres-supabase")
        assertEquals("verify-full", source.sslMode); assertEquals(certificate.toString(), source.sslRootCert)
        assertEquals("group33_fixture", source.currentSchema); assertEquals("group33_fixture_app", source.user)
        assertEquals(marker, source.password); assertFalse(source.getURL().contains(marker))
        assertEquals(5, source.connectTimeout); assertEquals(15, source.socketTimeout)
    }
    @Test fun `IPv4 shared session pooler keeps TLS and exact project-qualified role`() {
        val env = cloud().withProperty("app.persistence.jdbc-url", "jdbc:postgresql://aws-1-ap-southeast-1.pooler.supabase.com:5432/postgres")
            .withProperty("app.persistence.username", "group33_fixture_app.$project")
        val source = PersistenceDataSourceFactory.create(env, "postgres-supabase")
        assertEquals("verify-full", source.sslMode); assertEquals("group33_fixture_app.$project", source.user)
    }
    @Test fun `local mode remains loopback only with independent schema and credentials`() {
        val env = cloud().withProperty("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1:55432/fixture")
            .withProperty("app.persistence.schema", "fixture").withProperty("app.persistence.username", "fixture_local")
        val source = PersistenceDataSourceFactory.create(env, "postgres-local")
        assertNotEquals("verify-full", source.sslMode); assertEquals("fixture", source.currentSchema)
        assertThrows(IllegalStateException::class.java) { PersistenceDataSourceFactory.create(cloud(), "postgres-local") }
    }

    @ParameterizedTest @ValueSource(strings = ["http", "loopback", "wrong-project", "host-suffix", "pooler-port", "missing-port",
        "userinfo", "query", "fragment", "encoded-path", "database", "public-schema", "reserved-role", "pooler-role",
        "pooler-project", "missing-project", "bad-project", "missing-cert", "relative-cert", "invalid-cert", "cert-directory",
        "missing-password", "missing-user"])
    fun `cloud settings reject unsupported endpoints TLS overrides privileged names and missing material without disclosure`(kind: String) {
        val env = cloud()
        when (kind) {
            "http" -> env.setProperty("app.persistence.jdbc-url", "https://db.$project.supabase.co:5432/postgres")
            "loopback" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://127.0.0.1:5432/postgres")
            "wrong-project" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.zzzzzzzzzzzzzzzzzzzz.supabase.co:5432/postgres")
            "host-suffix" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co.example.invalid:5432/postgres")
            "pooler-port" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:6543/postgres")
            "missing-port" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co/postgres")
            "userinfo" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://$marker@db.$project.supabase.co:5432/postgres")
            "query" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co:5432/postgres?sslmode=disable&password=$marker")
            "fragment" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co:5432/postgres#$marker")
            "encoded-path" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co:5432/%70ostgres")
            "database" -> env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://db.$project.supabase.co:5432/other")
            "public-schema" -> env.setProperty("app.persistence.schema", "public")
            "reserved-role" -> env.setProperty("app.persistence.username", "postgres")
            "pooler-role", "pooler-project" -> {
                env.setProperty("app.persistence.jdbc-url", "jdbc:postgresql://aws-0-eu-west-1.pooler.supabase.com:5432/postgres")
                env.setProperty("app.persistence.username", if (kind == "pooler-role") "group33_fixture_app" else "group33_fixture_app.zzzzzzzzzzzzzzzzzzzz")
            }
            "missing-project" -> env.setProperty("app.supabase.project-ref", "")
            "bad-project" -> env.setProperty("app.supabase.project-ref", "bad-project")
            "missing-cert" -> env.setProperty("app.persistence.ssl-root-cert", directory.resolve("absent").toString())
            "relative-cert" -> env.setProperty("app.persistence.ssl-root-cert", "relative.crt")
            "invalid-cert" -> { val file=directory.resolve("invalid.crt"); Files.writeString(file, marker); env.setProperty("app.persistence.ssl-root-cert", file.toString()) }
            "cert-directory" -> env.setProperty("app.persistence.ssl-root-cert", directory.toString())
            "missing-password" -> env.setProperty("app.persistence.password", "")
            "missing-user" -> env.setProperty("app.persistence.username", "")
        }
        val error = assertThrows(IllegalStateException::class.java) { PersistenceDataSourceFactory.create(env, "postgres-supabase") }
        assertNull(error.cause); assertFalse(error.toString().contains(marker))
    }

    private fun photo(file: Path) = cloud().withProperty("app.photo.storage-mode", "supabase")
        .withProperty("app.photo.supabase.origin", "https://$project.supabase.co")
        .withProperty("app.photo.supabase.bucket", "fictional-photos")
        .withProperty("app.photo.supabase.secret-key-file", file.toString())
    private fun key(): Path {
        val file = Files.createTempFile(directory, "fixture-", ".key")
        Files.writeString(file, "sb_secret_FICTIONAL_TEST_ONLY_COMPONENT_2026\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); return file
    }
    @Test fun `explicit private cloud store can be constructed without contacting or mutating provider`() {
        val store = MedicationPhotoConfiguration().photoObjectStore(photo(key()))
        assertInstanceOf(SupabasePhotoObjectStore::class.java, store)
        assertFalse(store.toString().contains("sb_secret_"))
    }
    @Test fun `storage cloud with explicit local PostgreSQL is available for isolated provider-only integration`() {
        assertInstanceOf(SupabasePhotoObjectStore::class.java, MedicationPhotoConfiguration().photoObjectStore(
            photo(key()).withProperty("app.persistence.mode", "postgres-local")))
    }
    @Test fun `local fallback ignores cloud key and endpoint while remaining a local file store`() {
        val env = photo(directory.resolve("absent"))
            .withProperty("app.persistence.mode", "postgres-local").withProperty("app.photo.storage-mode", "local-file")
            .withProperty("app.photo.root", directory.resolve("private-objects").toString())
        assertInstanceOf(LocalFilePhotoObjectStore::class.java, MedicationPhotoConfiguration().photoObjectStore(env))
    }
    @ParameterizedTest @ValueSource(strings=["memory", "unknown-mode", "cloud-local-file", "http-origin", "other-project", "path-origin",
        "public-key", "missing-key", "wide-key", "symlink-key", "directory-key", "long-key", "malformed-key", "budget-zero", "budget-large", "bucket-path"])
    fun `runtime selection and private key failures never leak values or silently change storage`(kind: String) {
        val file=key(); val env=photo(file)
        when(kind) {
            "memory" -> env.setProperty("app.persistence.mode", "memory")
            "unknown-mode" -> env.setProperty("app.photo.storage-mode", "supabaze")
            "cloud-local-file" -> env.setProperty("app.photo.storage-mode", "local-file")
            "http-origin" -> env.setProperty("app.photo.supabase.origin", "http://$project.supabase.co")
            "other-project" -> env.setProperty("app.photo.supabase.origin", "https://zzzzzzzzzzzzzzzzzzzz.supabase.co")
            "path-origin" -> env.setProperty("app.photo.supabase.origin", "https://$project.supabase.co/storage/v1")
            "public-key" -> Files.writeString(file, "sb_publishable_$marker")
            "missing-key" -> Files.delete(file)
            "wide-key" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
            "symlink-key" -> { val link=directory.resolve("link-${file.fileName}"); Files.createSymbolicLink(link,file); env.setProperty("app.photo.supabase.secret-key-file",link.toString()) }
            "directory-key" -> env.setProperty("app.photo.supabase.secret-key-file",directory.toString())
            "long-key" -> Files.write(file,ByteArray(4097){65})
            "malformed-key" -> Files.write(file,byteArrayOf(-1,-2))
            "budget-zero" -> env.setProperty("app.photo.supabase.request-budget-ms","0")
            "budget-large" -> env.setProperty("app.photo.supabase.request-budget-ms","120001")
            "bucket-path" -> env.setProperty("app.photo.supabase.bucket","../bucket")
        }
        val error=assertThrows(IllegalStateException::class.java){MedicationPhotoConfiguration().photoObjectStore(env)}
        assertNull(error.cause); assertFalse(error.toString().contains(marker)); assertFalse(error.toString().contains(file.toString()))
    }
}
