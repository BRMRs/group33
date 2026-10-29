package com.group33.server.photo

import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.PhotoObjectStore
import com.group33.server.photo.storage.SupabasePhotoObjectStore
import com.group33.server.config.PrivateRuntimeFiles
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path
import java.net.URI
import java.time.Duration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.photo.enabled"], havingValue = "true")
internal class MedicationPhotoConfiguration {
    @Bean fun photoObjectStore(env: Environment): PhotoObjectStore {
        try {
            fun setting(name: String) = env.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("Missing setting")
            return when (env.getProperty("app.photo.storage-mode", "local-file")) {
                "local-file" -> {
                    require(env.getProperty("app.persistence.mode", "memory") != "postgres-supabase")
                    LocalFilePhotoObjectStore(Path.of(setting("app.photo.root")))
                }
                "supabase" -> {
                    require(env.getProperty("app.persistence.mode") in setOf("postgres-local", "postgres-supabase"))
                    val project = setting("app.supabase.project-ref")
                    require(project.matches(Regex("[a-z0-9]{20}")))
                    val origin = URI(setting("app.photo.supabase.origin"))
                    require(origin == URI("https://$project.supabase.co"))
                    SupabasePhotoObjectStore(origin, setting("app.photo.supabase.bucket"),
                        PrivateRuntimeFiles.secret(setting("app.photo.supabase.secret-key-file")),
                        Duration.ofMillis(env.getProperty("app.photo.supabase.request-budget-ms", Long::class.java, 30_000L)))
                }
                else -> error("Unknown storage mode")
            }
        } catch (_: Exception) {
            throw IllegalStateException("Private photo storage initialization failed; verify private settings")
        }
    }
    @Bean fun medicationPhotoService(store: PhotoMetadataStore, objects: PhotoObjectStore, clock: Clock) =
        MedicationPhotoService(store, objects, clock)
}
