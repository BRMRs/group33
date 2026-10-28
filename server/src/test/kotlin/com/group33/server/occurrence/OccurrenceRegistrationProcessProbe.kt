package com.group33.server.occurrence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.*
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.util.Base64
import java.util.UUID

/** Test-only independent JVM. Never a production endpoint or bootJar class. */
internal object OccurrenceRegistrationProcessProbe {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==4 && args[0].matches(Regex("s2_occurrence_it_[a-f0-9]+")))
        val mapper=jacksonObjectMapper();val input=mapper.readValue(Base64.getDecoder().decode(args[3]),OccurrenceGenerationEvidence::class.java)
        val actor=AnonymousUser("fictional-registration-elder","fixture-registration-install","虚构长者",UserRole.ELDER)
        val store=PostgresPairingCodeStore(dataSource(args[0]),PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))))
        val clock=Clock.fixed(Instant.ofEpochMilli(args[2].toLong()),ZoneOffset.UTC)
        val result=OccurrenceRegistrationService(store,clock).register(actor,actor.id,UUID.fromString(args[1]),OccurrenceRegistrationRequest(listOf(input)))
        check(result is RegistrationResult.Accepted)
        println(Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(result.response)))
    }
}
