package com.group33.server.photo

import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.server.photo.storage.PhotoObjectStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.nio.file.Path
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["app.photo.enabled"], havingValue = "true")
internal class MedicationPhotoConfiguration {
    @Bean fun photoObjectStore(env: Environment): PhotoObjectStore {
        val root = env.getProperty("app.photo.root")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("A private photo object root is required")
        return LocalFilePhotoObjectStore(Path.of(root))
    }
    @Bean fun medicationPhotoService(store: PhotoMetadataStore, objects: PhotoObjectStore, clock: Clock) =
        MedicationPhotoService(store, objects, clock)
}
