package com.group33.server.pairing

import com.group33.server.auth.AnonymousUser
import com.group33.shared.contract.RequestPairingRequest
import com.group33.shared.model.UserRole
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Test-only child JVM; no raw code/identity/credentials in output. */
object PairingRequestPostgresProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val cipher = PairingReceiptCipher(Files.readAllBytes(Path.of(PostgresPairingCodeStoreTest.required("S2_PG_RECEIPT_KEY_FILE"))))
        val store = PostgresPairingCodeStore(PostgresPairingCodeStoreTest.dataSource(args[0]), cipher)
        val clock = Clock.fixed(Instant.ofEpochMilli(args[3].toLong()), ZoneOffset.UTC)
        if (args[1] == "consume") {
            val elder = AnonymousUser("child-elder", "child-e-install", "Fixture", UserRole.ELDER)
            check(PairingCodeIssuanceService(store, clock) { "00004567" }.issue(elder, UUID.randomUUID()) is CodeIssuanceResult.Issued)
        }
        val caregiver = AnonymousUser("child-caregiver", "child-c-install", "Fixture", UserRole.CAREGIVER)
        val result = PairingRequestService(store, clock).request(caregiver, UUID.fromString(args[2]),
            RequestPairingRequest("00004567"), InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)))
        check(result is PairingRequestResult.Success)
        println("${result.view.id}|${result.view.status}|${result.view.expiresAtEpochMillis}")
    }
}
