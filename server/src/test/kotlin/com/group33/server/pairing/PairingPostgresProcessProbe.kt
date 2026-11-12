package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Child-JVM test entry point, never a production endpoint; output contains no raw code or credentials. */
object PairingPostgresProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val key = Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE")))
        val store = PostgresPairingCodeStore(PostgresPairingCodeStoreTest.dataSource(args[0]), PairingReceiptCipher(key))
        val generator = PairingCodeGenerator { check(args[1] == "issue"); "00004567" }
        val clock = Clock.fixed(Instant.ofEpochMilli(args[3].toLong()), ZoneOffset.UTC)
        val actor = AnonymousUser("fictional-elder", "fictional-installation", "Fixture", UserRole.ELDER)
        val result = PairingCodeIssuanceService(store, clock, generator).issue(actor, UUID.fromString(args[2]))
        check(result is CodeIssuanceResult.Issued)
        println("${result.view.id}|${PairingCodeIssuanceService.hash(result.view.code)}|${result.view.expiresAtEpochMillis}")
    }
}
