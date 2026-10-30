package com.group33.server.config

import com.group33.server.auth.*
import com.group33.server.pairing.*
import com.group33.server.plan.PlanMutationStore
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

internal class PersistenceRuntime(val identities: AnonymousIdentityStore, val pairing: PlanMutationStore) {
    override fun toString() = "PersistenceRuntime(REDACTED)"
}

/** Explicit local PostgreSQL opt-in. No implicit schema creation, cloud credentials or fallback to memory. */
@Configuration(proxyBeanMethods = false)
internal class PersistenceRuntimeConfiguration {
    @Bean fun persistenceRuntime(env: Environment): PersistenceRuntime = create(env)
    @Bean fun anonymousIdentityStore(runtime: PersistenceRuntime): AnonymousIdentityStore = runtime.identities

    internal fun create(env: Environment): PersistenceRuntime {
        val plansEnabled = env.getProperty("app.plan.enabled", Boolean::class.java, false)
        val occurrencesEnabled = env.getProperty("app.occurrence.enabled", Boolean::class.java, false)
        require(!occurrencesEnabled || plansEnabled) { "Occurrence registration requires the plan runtime" }
        require(!plansEnabled || env.getProperty("app.photo.enabled", Boolean::class.java, false)) {
            "Plan confirmation requires the private photo runtime"
        }
        val mode = env.getProperty("app.persistence.mode", "memory")
        val requireVersioned = env.getProperty("app.persistence.require-versioned-schema", Boolean::class.java, false)
        require(!requireVersioned || mode == "postgres-local") { "Versioned schema startup validation requires postgres-local persistence" }
        if (mode == "memory") return PersistenceRuntime(InMemoryAnonymousIdentityStore(), InMemoryPairingCodeStore())
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
            if (requireVersioned) RuntimeSchemaHistory.validate(source, schema)
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
                    if (env.getProperty("app.photo.enabled", Boolean::class.java, false)) {
                        s.executeQuery("SELECT photo_id, state, receipt_photo_id FROM photo_upload_operation LIMIT 0").close()
                        s.executeQuery("SELECT object_id, sha256 FROM medication_photo LIMIT 0").close()
                    }
                    if (plansEnabled) {
                        listOf("SELECT id, elder_id, initial_receipt_id, photo_id FROM medication LIMIT 0",
                            "SELECT id, medication_id, revision, initial_receipt_id FROM reminder_plan LIMIT 0",
                            "SELECT plan_id, revision, display_name, instructions FROM reminder_plan_revision LIMIT 0",
                            "SELECT actor_id, operation_id, receipt_payload, rejection_reason, violations FROM plan_confirmation_operation LIMIT 0",
                            "SELECT actor_id, action, operation_id, receipt_revision, receipt_payload, violations FROM plan_mutation_operation LIMIT 0")
                            .forEach { sql -> s.executeQuery(sql).close() }
                    }
                    if (occurrencesEnabled) {
                        listOf("SELECT actor_id, operation_id, installation_id, elder_id, canonicalization_version, request_payload, response_payload, rejection_payload FROM occurrence_registration_operation LIMIT 0",
                            "SELECT id, plan_id, local_date, local_time, plan_revision, elder_id, medication_id, installation_id, generation_id, origin_operation_id, receipt_payload FROM registered_occurrence LIMIT 0")
                            .forEach { sql -> s.executeQuery(sql).close() }
                    }
                }
            }
            source.connection.use { c -> c.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM pairing_issue_operation WHERE receipt_cipher IS NOT NULL").use { rows ->
                    while (rows.next()) cipher.open(CodeIssueKey(rows.getString("actor_id"), rows.getObject("operation_id", UUID::class.java)),
                        CodeIssueBinding(rows.getString("installation_id"), rows.getString("target_elder_id"), rows.getString("fingerprint")),
                        rows.getString("receipt_id"), rows.getLong("receipt_expiry"), rows.getBytes("receipt_cipher"))
                }
            } }
            return PersistenceRuntime(PostgresAnonymousIdentityStore(source), PostgresPairingCodeStore(source, cipher))
        } catch (_: Exception) {
            // Connection/property/key failures can contain credentials. Deliberately omit values and the private cause.
            throw IllegalStateException("Local PostgreSQL persistence initialization failed; verify private settings and initialized schema")
        }
    }
}
