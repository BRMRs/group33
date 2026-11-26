package com.group33.server.config

import com.group33.server.auth.InMemoryAnonymousIdentityStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment

internal class CloudRuntimePrerequisiteTest {
    @ParameterizedTest @ValueSource(strings = ["memory", "postgres-local", "postgres-supabase"])
    fun `dose groups require occurrence before any runtime or network selection`(mode: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment()
                .withProperty("app.persistence.mode", mode)
                .withProperty("app.photo.enabled", "true")
                .withProperty("app.plan.enabled", "true")
                .withProperty("app.dose-group.enabled", "true"))
        }
        assertEquals("Dose groups require the occurrence registration runtime", failure.message)
    }

    @ParameterizedTest @ValueSource(strings = ["memory", "postgres-local", "postgres-supabase"])
    fun `occurrence requires plan before any runtime or network selection`(mode: String) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment()
                .withProperty("app.persistence.mode", mode)
                .withProperty("app.occurrence.enabled", "true"))
        }
        assertEquals("Occurrence registration requires the plan runtime", failure.message)
    }

    @Test fun `current cloud opt in does not pretend to implement the pending cloud history gate`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment()
                .withProperty("app.persistence.mode", "postgres-supabase")
                .withProperty("app.persistence.require-versioned-schema", "true"))
        }
        assertEquals("Versioned schema startup validation requires postgres-local persistence", failure.message)
    }

    @Test fun `all current feature prerequisites retain explicit memory selection`() {
        val runtime = PersistenceRuntimeConfiguration().create(MockEnvironment()
            .withProperty("app.photo.enabled", "true").withProperty("app.plan.enabled", "true")
            .withProperty("app.occurrence.enabled", "true").withProperty("app.dose-group.enabled", "true"))
        assertInstanceOf(InMemoryAnonymousIdentityStore::class.java, runtime.identities)
    }
}
