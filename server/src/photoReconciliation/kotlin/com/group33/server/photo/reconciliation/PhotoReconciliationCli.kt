package com.group33.server.photo.reconciliation

import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.*
import javax.sql.DataSource
import kotlin.system.exitProcess

class PhotoInspectionSettings private constructor(
    val source: DataSource, val schema: String, val objects: PhotoObjectInspector, val limits: PhotoInspectionLimits,
) {
    override fun toString() = "PhotoInspectionSettings(REDACTED)"
    companion object {
        fun from(env: Map<String, String>): PhotoInspectionSettings = try {
            fun required(suffix: String) = requireNotNull(env["GROUP33_PHOTO_INSPECT_$suffix"]?.takeIf { it.isNotBlank() })
            val database = required("DATABASE")
            require(database.matches(Regex("[a-zA-Z0-9_-]{1,63}")))
            val schema = required("SCHEMA")
            require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")) && schema !in setOf("public", "information_schema") && !schema.startsWith("pg_"))
            val user = required("USER")
            require(user.matches(Regex("[a-zA-Z0-9_-]{1,63}")))
            val passwordFile = Path.of(required("PASSWORD_FILE"))
            require(Files.isRegularFile(passwordFile, NOFOLLOW_LINKS) && Files.size(passwordFile) in 1..4096)
            require(Files.getPosixFilePermissions(passwordFile, NOFOLLOW_LINKS).none {
                it in setOf(GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE)
            })
            val password = Files.readString(passwordFile).removeSuffix("\n").removeSuffix("\r")
            require(password.isNotBlank() && password.none { it in "\r\n\u0000" })
            val port = required("PORT").toInt().also { require(it in 1..65535) }
            val objects = LocalPhotoObjectInspector(Path.of(required("OBJECT_ROOT")))
            val limits = PhotoInspectionLimits(
                env["GROUP33_PHOTO_INSPECT_BATCH_SIZE"]?.toInt() ?: 25,
                env["GROUP33_PHOTO_INSPECT_MAX_PHOTOS"]?.toInt() ?: 100,
                env["GROUP33_PHOTO_INSPECT_MAX_BYTES"]?.toLong() ?: 33_554_432,
                env["GROUP33_PHOTO_INSPECT_MAX_MILLIS"]?.toLong() ?: 30_000)
            val source = PGSimpleDataSource().apply {
                serverNames = arrayOf("127.0.0.1"); portNumbers = intArrayOf(port)
                databaseName = database; this.user = user; this.password = password
                connectTimeout = 3; socketTimeout = 5; applicationName = "group33-photo-inspection"
            }
            PhotoInspectionSettings(source, schema, objects, limits)
        } catch (_: Exception) { throw IllegalArgumentException("Invalid private photo inspection settings") }
    }
}

object PhotoReconciliationCli {
    /** 0: checked scope has no findings; 2: missing/corrupt observations; 3: incomplete; 64: invalid input. */
    fun execute(args: Array<String>, env: Map<String, String>, output: (String) -> Unit): Int {
        if (!args.contentEquals(arrayOf("inspect"))) {
            output("""{"error":"INVALID_PHOTO_INSPECTION_INPUT"}"""); return 64
        }
        val settings = try { PhotoInspectionSettings.from(env) } catch (_: Exception) {
            output("""{"error":"INVALID_PHOTO_INSPECTION_SETTINGS"}"""); return 64
        }
        val report = PhotoReconciliationService(JdbcPhotoSnapshotSource(settings.source, settings.schema), settings.objects).inspect(settings.limits)
        output(report.json())
        return when {
            report.completion != PhotoInspectionCompletion.COMPLETE -> 3
            report.missing + report.integrityMismatch > 0 -> 2
            else -> 0
        }
    }
    @JvmStatic fun main(args: Array<String>) { exitProcess(execute(args, System.getenv(), ::println)) }
}
