package com.group33.server.photo.inventory

import com.group33.server.photo.reconciliation.PhotoInspectionSettings
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.system.exitProcess

class PhotoInventorySettings private constructor(
    val source: DataSource, val schema: String, val objects: PhotoInventoryObjects, val limits: PhotoInventoryLimits,
) {
    override fun toString() = "PhotoInventorySettings(REDACTED)"
    companion object {
        fun from(env: Map<String, String>): PhotoInventorySettings = try {
            val prefix = "GROUP33_PHOTO_INVENTORY_"
            // Reuse the existing private loopback/password/root validation; never read App/cloud settings.
            val names = setOf("DATABASE", "PORT", "SCHEMA", "USER", "PASSWORD_FILE", "OBJECT_ROOT")
            require(env.keys.filter { it.startsWith(prefix) }.all {
                it.removePrefix(prefix) in names + setOf("BATCH_SIZE", "MAX_ENTRIES", "MAX_MILLIS")
            })
            val common = env.filterKeys { it.removePrefix(prefix) in names && it.startsWith(prefix) }
                .mapKeys { "GROUP33_PHOTO_INSPECT_" + it.key.removePrefix(prefix) }
            val selected = PhotoInspectionSettings.from(common)
            val limits = PhotoInventoryLimits(
                env[prefix + "BATCH_SIZE"]?.toInt() ?: 25,
                env[prefix + "MAX_ENTRIES"]?.toInt() ?: 100,
                env[prefix + "MAX_MILLIS"]?.toLong() ?: 30_000)
            PhotoInventorySettings(selected.source, selected.schema,
                LocalPhotoInventory(Path.of(requireNotNull(env[prefix + "OBJECT_ROOT"]))), limits)
        } catch (_: Exception) { throw IllegalArgumentException("Invalid private photo inventory settings") }
    }
}
object PhotoInventoryCli {
    /** 0: exhausted selected scope without candidates; 2: candidates/unsafe entries; 3: incomplete; 64: invalid input. */
    fun execute(args: Array<String>, env: Map<String, String>, output: (String) -> Unit): Int {
        if (!args.contentEquals(arrayOf("inventory"))) {
            output("""{"error":"INVALID_PHOTO_INVENTORY_INPUT"}"""); return 64
        }
        val settings = try { PhotoInventorySettings.from(env) } catch (_: Exception) {
            output("""{"error":"INVALID_PHOTO_INVENTORY_SETTINGS"}"""); return 64
        }
        val report = PhotoInventoryService(JdbcPhotoInventorySource(settings.source, settings.schema), settings.objects).inspect(settings.limits)
        output(report.json())
        return when {
            report.completion != InventoryCompletion.COMPLETE -> 3
            report.unmatchedCandidates + report.temporary + report.unsafe > 0 -> 2
            else -> 0
        }
    }
    @JvmStatic fun main(args: Array<String>) { exitProcess(execute(args, System.getenv(), ::println)) }
}
