package com.group33.server.capability

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.auth.CareRelationshipAccess
import com.group33.server.capability.CapabilityPostgresTestSupport.source
import com.group33.server.capability.CapabilityPostgresTestSupport.elder
import com.group33.server.capability.CapabilityPostgresTestSupport.report
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** Test-only independent JVM; never registered as a production endpoint or packaged into bootJar. */
internal object CapabilityPostgresProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size==5 && args[1] in listOf("submit","replay"))
        val schema=args[0];val input=report(args[3].toLong(),UUID.fromString(args[2]))
        val clock=Clock.fixed(Instant.ofEpochMilli(args[4].toLong()),ZoneOffset.UTC)
        val service=CapabilityService(PostgresCapabilityStore(source(schema)),CareRelationshipAccess { _,_->false },clock)
        val result=service.report(elder,elder.id,UUID.fromString(input.reportId),input)
        check(result is CapabilityAcceptance.Accepted)
        println(Base64.getEncoder().encodeToString(jacksonObjectMapper().writeValueAsBytes(result.receipt)))
    }
}
