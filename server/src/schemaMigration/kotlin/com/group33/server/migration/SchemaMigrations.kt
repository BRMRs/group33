package com.group33.server.migration

import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.*
import javax.sql.DataSource

/** Separate administration credentials. Neither values nor private causes belong in console output. */
class MigrationSettings private constructor(val source: DataSource, val schema: String) {
    override fun toString() = "MigrationSettings(REDACTED)"

    companion object {
        fun from(env: Map<String, String>): MigrationSettings {
            try {
                fun required(key: String) = requireNotNull(env[key]?.takeIf { it.isNotBlank() })
                val url = required("GROUP33_MIGRATION_URL")
                require(url.startsWith("jdbc:postgresql://"))
                val uri = URI(url.removePrefix("jdbc:"))
                require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
                require(uri.path?.matches(Regex("/[a-zA-Z0-9_-]+")) == true)
                require(uri.port == -1 || uri.port in 1..65535)
                val schema = required("GROUP33_MIGRATION_SCHEMA")
                require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
                require(schema != "public" && schema != "information_schema" && !schema.startsWith("pg_"))
                val user = required("GROUP33_MIGRATION_USER")
                val passwordFile = Path.of(required("GROUP33_MIGRATION_PASSWORD_FILE"))
                require(Files.isRegularFile(passwordFile, NOFOLLOW_LINKS))
                require(Files.size(passwordFile) in 1..4096)
                require(Files.getPosixFilePermissions(passwordFile, NOFOLLOW_LINKS).none {
                    it in setOf(GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)
                })
                val password = Files.readString(passwordFile).removeSuffix("\n").removeSuffix("\r")
                require(password.isNotBlank() && '\n' !in password && '\r' !in password && '\u0000' !in password)
                val loopback = uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
                val source = PGSimpleDataSource().apply {
                    setURL(url); this.user = user; this.password = password
                    currentSchema = schema; connectTimeout = 5; socketTimeout = 30
                    applicationName = "group33-schema-migration"
                    if (!loopback) {
                        val certificate = Path.of(required("GROUP33_MIGRATION_SSL_ROOT_CERT_FILE"))
                        require(Files.isRegularFile(certificate, NOFOLLOW_LINKS))
                        sslMode = "verify-full"; sslRootCert = certificate.toAbsolutePath().toString()
                    }
                }
                return MigrationSettings(source, schema)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid private migration settings")
            }
        }
    }
}

/** Versioned SQL is owned by Flyway. No clean, repair, baseline or startup hook is exposed. */
class SchemaMigrations(private val source: DataSource, private val schema: String) {
    companion object { const val VERSION = "10" }
    override fun toString() = "SchemaMigrations(REDACTED)"

    private fun flyway(): Flyway {
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
        require(schema != "public" && schema != "information_schema" && !schema.startsWith("pg_"))
        source.connection.use { c ->
            c.prepareStatement("SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = ?").use { s ->
                s.setString(1, schema)
                s.executeQuery().use { check(it.next()) { "Schema must be provisioned explicitly" } }
            }
        }
        return Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
            .createSchemas(false).table("group33_schema_history")
            .locations("classpath:db/migration").target(VERSION)
            .baselineOnMigrate(false).cleanDisabled(true).outOfOrder(false)
            .validateMigrationNaming(true).validateOnMigrate(true).ignoreMigrationPatterns(*emptyArray<String>())
            .group(true).mixed(false).lockRetryCount(5).placeholderReplacement(false).loggers(*emptyArray<String>()).load()
    }

    fun migrate(): Int = safely {
        val tool = flyway()
        val count = tool.migrate().migrationsExecuted
        checkCurrent(tool)
        count
    }

    /** Read-only verification: a new, incomplete, changed, missing or future history fails. */
    fun validate(): Unit = safely { checkCurrent(flyway()) }

    private fun checkCurrent(tool: Flyway) {
        tool.validate()
        val applied = tool.info().applied()
        check(applied.size == VERSION.toInt() && applied.map { it.version?.version } == (1..VERSION.toInt()).map(Int::toString))
        check(tool.info().current()?.version?.version == VERSION && tool.info().pending().isEmpty())
    }

    private fun <T> safely(action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        throw IllegalStateException("Schema migration failed; verify private settings and migration history")
    }
}

object SchemaMigrationCli {
    fun run(args: Array<String>, env: Map<String, String>, out: (String) -> Unit, error: (String) -> Unit): Int {
        if (args.size != 1 || args[0] !in setOf("migrate", "validate")) {
            error("Usage: schema-migration migrate|validate")
            return 2
        }
        return try {
            val settings = MigrationSettings.from(env)
            val tool = SchemaMigrations(settings.source, settings.schema)
            if (args[0] == "migrate") out("Schema migrations applied: ${tool.migrate()}; version ${SchemaMigrations.VERSION}")
            else { tool.validate(); out("Schema migration history is current: ${SchemaMigrations.VERSION}") }
            0
        } catch (_: Exception) {
            error("Schema migration failed; verify private settings and migration history")
            1
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        kotlin.system.exitProcess(run(args, System.getenv(), ::println, System.err::println))
    }
}
