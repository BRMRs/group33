package com.group33.server.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.env.MockEnvironment
import org.flywaydb.core.internal.resolver.ChecksumCalculator
import org.flywaydb.core.internal.resource.StringResource

internal class RuntimeSchemaHistoryTest {
    @Test fun `packaged release manifest is complete with canonical SQL identities`() {
        val rows = RuntimeSchemaHistory.parse(checkNotNull(javaClass.getResource("/db/runtime-schema-history.tsv")).readText())
        assertEquals((1..10).map(Int::toString), rows.map { it.version })
        assertEquals(10, rows.map { it.script }.distinct().size)
        rows.forEach { row ->
            val migration = checkNotNull(javaClass.getResource("/db/migration/${row.script}")).readText()
            assertEquals(ChecksumCalculator.calculate(StringResource(migration)), row.checksum, row.script)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "1\tV1__one.sql", "0\tV0__one.sql\t1", "01\tV1__one.sql\t1",
        "1\t../../outside.sql\t1", "1\tV1__one.sql\tbad", "1\tV1__one.sql\t2147483648",
        "2\tV2__two.sql\t2", "1\tV1__one.sql\t1\n1\tV1__one.sql\t1",
        "1\tV1__one.sql\t1\n3\tV3__three.sql\t3"])
    fun `malformed release manifests cannot silently weaken the gate`(text: String) {
        assertThrows(IllegalArgumentException::class.java) { RuntimeSchemaHistory.parse(text) }
    }

    @Test fun `required schema validation cannot silently choose memory`() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistenceRuntimeConfiguration().create(MockEnvironment().withProperty("app.persistence.require-versioned-schema", "true"))
        }
    }
}
