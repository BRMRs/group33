package com.group33.server.config

import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.env.Environment
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.cert.CertificateFactory

/** Administrator-selected endpoints only. No URL options can override TLS, credentials or search_path. */
internal object PersistenceDataSourceFactory {
    fun create(env: Environment, mode: String): PGSimpleDataSource {
        try {
            fun setting(name: String) = env.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("Missing setting")
            require(mode in setOf("postgres-local", "postgres-supabase"))
            val url = setting("app.persistence.jdbc-url")
            require(url.startsWith("jdbc:postgresql://"))
            val uri = URI(url.removePrefix("jdbc:"))
            require(uri.userInfo == null && uri.rawQuery == null && uri.fragment == null)
            require(uri.rawPath == uri.path && uri.path?.matches(Regex("/[a-zA-Z0-9_-]+")) == true)
            require(uri.port == -1 || uri.port in 1..65535)
            val schema = setting("app.persistence.schema")
            require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
            val username = setting("app.persistence.username")
            val password = setting("app.persistence.password")
            var certificate: Path? = null
            if (mode == "postgres-local") {
                require(uri.host in setOf("localhost", "127.0.0.1", "[::1]", "::1"))
            } else {
                val project = setting("app.supabase.project-ref")
                require(project.matches(Regex("[a-z0-9]{20}")))
                require(uri.port == 5432 && uri.path == "/postgres")
                val direct = uri.host == "db.$project.supabase.co"
                val session = uri.host?.matches(Regex("aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com")) == true
                require(direct || session)
                require(schema.matches(Regex("group33_[a-z0-9_]{1,56}")))
                val role = if (session) {
                    require(username.endsWith(".$project")); username.removeSuffix(".$project")
                } else username
                require(role.matches(Regex("group33_[a-z0-9_]{1,56}")))
                certificate = Path.of(setting("app.persistence.ssl-root-cert"))
                require(certificate.isAbsolute && Files.isRegularFile(certificate, LinkOption.NOFOLLOW_LINKS))
                require(Files.size(certificate) in 1..65_536)
                Files.newInputStream(certificate).use {
                    require(CertificateFactory.getInstance("X.509").generateCertificates(it).isNotEmpty())
                }
            }
            return PGSimpleDataSource().apply {
                setURL(url); user = username; this.password = password; currentSchema = schema
                connectTimeout = 5; socketTimeout = 15
                if (certificate != null) { sslMode = "verify-full"; sslRootCert = certificate.toString() }
            }
        } catch (_: Exception) {
            throw IllegalStateException("PostgreSQL connection settings are invalid; verify private configuration")
        }
    }
}
