package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Test-only process restart evidence; never included in bootJar. */
object PairingDecisionPostgresProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        check(args[1] in setOf("accept", "replay"))
        val cipher = PairingReceiptCipher(Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE"))))
        val store = PostgresPairingCodeStore(PostgresPairingCodeStoreTest.dataSource(args[0]), cipher)
        val clock = Clock.fixed(Instant.ofEpochMilli(args[4].toLong()), ZoneOffset.UTC)
        val elder = AnonymousUser("fixture-elder", "fixture-e-install", "虚构长者", UserRole.ELDER)
        val result = PairingDecisionService(store, clock).accept(elder, args[2], UUID.fromString(args[3]))
        check(result is PairingDecisionResult.Relationship)
        println("${result.view.id}|${result.view.status}|${result.view.createdAtEpochMillis}")
    }
}
