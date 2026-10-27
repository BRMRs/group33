package com.group33.server.plan

import com.group33.server.auth.AnonymousUser
import com.group33.server.pairing.PairingReceiptCipher
import com.group33.server.pairing.PostgresPairingCodeStore
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.dataSource
import com.group33.server.pairing.PostgresPairingCodeStoreTest.Companion.required
import com.group33.server.photo.storage.LocalFilePhotoObjectStore
import com.group33.shared.contract.*
import com.group33.shared.model.UserRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Test-only independent JVM process; no HTTP route, bypass token or production fixture. */
object PlanConfirmationPostgresProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 5 && args[0] in listOf("write", "read"))
        val key = PlanConfirmationKey("fictional-plan-caregiver", UUID.fromString(args[3]))
        val store = PostgresPairingCodeStore(dataSource(args[1]), PairingReceiptCipher(Files.readAllBytes(Path.of(required("S2_PG_RECEIPT_KEY_FILE")))))
        val actor = AnonymousUser(key.actorId, "fictional-plan-caregiver-installation", "虚构照护者", UserRole.CAREGIVER)
        val input = ConfirmReminderPlanRequest(args[4], MedicationText("虚构药品", "虚构用量", " 保留说明 "),
            ReminderPlanDraft(listOf("20:00", "08:00"), "2026-10-27", "Asia/Shanghai"),
            TimeZoneAcknowledgement("Asia/Shanghai", false, "Asia/Shanghai", "Asia/Shanghai"))
        val original = if (args[0] == "read") store.confirmationTransaction { checkNotNull(it.confirmationOperation(key)?.receipt) } else null
        val clock = Clock.fixed(Instant.parse(if (args[0] == "read") "2027-10-27T00:00:00Z" else "2026-10-27T00:00:00Z"), ZoneOffset.UTC)
        val result = ReminderPlanConfirmationService(store, LocalFilePhotoObjectStore(Path.of(args[2])), clock)
            .confirm(actor, "fictional-plan-elder", key.operationId, input)
        check(result is PlanConfirmationResult.Confirmed)
        if (original != null) check(result.view == original)
        store.confirmationTransaction { tx ->
            check(tx.medications("fictional-plan-elder").size == 1 && tx.plans("fictional-plan-elder").size == 1)
            check(tx.revisions("fictional-plan-elder").single() == PlanConfirmationCopies.initialRevision(result.view))
        }
        println("Plan confirmation independent process ${args[0]} verified")
    }
}
