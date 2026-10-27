package com.group33.server.config

import com.group33.server.auth.*
import com.group33.server.pairing.*
import com.group33.server.capability.*
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal class PersistenceRuntime(val identities: AnonymousIdentityStore, val pairing: PairingDirectoryStore,
    val capability: CapabilityStore? = null) {
    override fun toString() = "PersistenceRuntime(REDACTED)"
}

/** Explicit local PostgreSQL opt-in. No implicit schema creation, cloud credentials or fallback to memory. */
@Configuration(proxyBeanMethods = false)
internal class PersistenceRuntimeConfiguration {
    @Bean fun persistenceRuntime(env: Environment): PersistenceRuntime = create(env)
    @Bean fun anonymousIdentityStore(runtime: PersistenceRuntime): AnonymousIdentityStore = runtime.identities

    internal fun create(env: Environment): PersistenceRuntime {
        val mode = env.getProperty("app.persistence.mode", "memory")
        val capabilityEnabled = env.getProperty("app.capability.enabled", Boolean::class.java, false)
        if (mode == "memory") return PersistenceRuntime(InMemoryAnonymousIdentityStore(), InMemoryPairingCodeStore(),
            if (capabilityEnabled) InMemoryCapabilityStore() else null)
        require(mode == "postgres-local") { "app.persistence.mode must be memory or postgres-local" }
        fun setting(name: String) = env.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Required local persistence setting is missing: $name")
        try {
            val url = setting("app.persistence.jdbc-url")
            require(url.startsWith("jdbc:postgresql://"))
            val uri = URI(url.removePrefix("jdbc:"))
            require(uri.path?.matches(Regex("/[a-zA-Z0-9_-]+")) == true)
            require(uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1") && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
            val schema = setting("app.persistence.schema")
            require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
            val jwt = setting("app.jwt.secret")
            require(jwt.toByteArray(Charsets.UTF_8).size >= 32 && jwt != "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026")
            val file = Path.of(setting("app.persistence.receipt-key-file"))
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            val permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
            require(permissions.none { it in setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE) })
            val key = Files.readAllBytes(file); require(key.size == 32)
            val cipher = PairingReceiptCipher(key)
            val source = PGSimpleDataSource().apply {
                setURL(url); user = setting("app.persistence.username"); password = setting("app.persistence.password")
                currentSchema = schema; connectTimeout = 5; socketTimeout = 15
            }
            source.connection.use { c ->
                // No DDL at startup. All expected prototype tables/columns must already be initialized.
                c.createStatement().use { s ->
                    listOf("SELECT id, installation_id, display_name, role FROM anonymous_identity LIMIT 0",
                        "SELECT id FROM pairing_issuance_guard WHERE id = 1",
                        "SELECT actor_id, operation_id FROM pairing_issue_operation LIMIT 0",
                        "SELECT code_id FROM pairing_active_code LIMIT 0", "SELECT ordinal FROM pairing_issuance_time LIMIT 0",
                        "SELECT relationship_id FROM pairing_request LIMIT 0", "SELECT receipt_request_id FROM pairing_request_operation LIMIT 0",
                        "SELECT scope FROM pairing_request_attempt LIMIT 0", "SELECT revoked_at FROM pairing_relationship LIMIT 0",
                        "SELECT result_payload FROM pairing_decision_operation LIMIT 0", "SELECT code_id FROM pairing_code_deletion LIMIT 0")
                        .forEach { sql -> s.executeQuery(sql).use { rows -> if (sql.contains("WHERE id = 1")) check(rows.next()) } }
                }
            }
            source.connection.use { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM pairing_issue_operation WHERE receipt_cipher IS NOT NULL").use { rows ->
                    while (rows.next()) cipher.open(CodeIssueKey(rows.getString("actor_id"), rows.getObject("operation_id", UUID::class.java)),
                        CodeIssueBinding(rows.getString("installation_id"), rows.getString("target_elder_id"), rows.getString("fingerprint")),
                        rows.getString("receipt_id"), rows.getLong("receipt_expiry"), rows.getBytes("receipt_cipher"))
                }
            } }
            if (capabilityEnabled) source.connection.use { c -> c.createStatement().use { statement ->
                listOf("SELECT id FROM capability_guard WHERE id = 1",
                    "SELECT elder_id, installation_id, observation_sequence, report_payload FROM capability_observation LIMIT 0",
                    "SELECT elder_id, installation_id, observation_sequence, view_payload FROM capability_current LIMIT 0",
                    "SELECT actor_id, operation_id, report_payload, receipt_payload, rejection_reason FROM capability_operation LIMIT 0")
                    .forEach { sql -> statement.executeQuery(sql).use { rows -> if (sql.contains("WHERE id = 1")) check(rows.next()) } }
            } }
            return PersistenceRuntime(PostgresAnonymousIdentityStore(source), PostgresPairingCodeStore(source, cipher),
                if (capabilityEnabled) PostgresCapabilityStore(source) else null)
        } catch (_: Exception) {
            // Connection/property/key failures can contain credentials. Deliberately omit values and the private cause.
            throw IllegalStateException("Local PostgreSQL persistence initialization failed; verify private settings and initialized schema")
        }
    }
}
