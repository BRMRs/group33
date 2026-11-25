package com.group33.server.photo.inventory

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Mac JDK lacks SecureDirectoryStream. The packaged stdlib helper uses openat/statat on a retained fd. */
class PythonPhotoInventoryCursor(root: Path, device: String, inode: String) : PhotoInventoryCursor {
    private val process: Process
    private val reader: java.io.BufferedReader
    private val writer: java.io.BufferedWriter
    private val input = Executors.newSingleThreadExecutor { task -> Thread(task,"photo-inventory-metadata").apply { isDaemon = true } }
    private var closed = false
    private var sequence = 0
    init {
        try {
            val script = requireNotNull(javaClass.getResourceAsStream("/photo-inventory/scan.py")).use { it.readBytes().toString(Charsets.UTF_8) }
            val builder = ProcessBuilder("python3","-I","-B","-c",script).redirectError(ProcessBuilder.Redirect.DISCARD)
            val path = builder.environment()["PATH"]
            builder.environment().clear()
            if (path != null) builder.environment()["PATH"] = path
            builder.environment().putAll(mapOf("GROUP33_INVENTORY_ROOT" to root.toString(),
                "GROUP33_INVENTORY_DEVICE" to device,"GROUP33_INVENTORY_INODE" to inode))
            process = builder.start()
            reader = process.inputStream.bufferedReader(Charsets.US_ASCII)
            writer = process.outputStream.bufferedWriter(Charsets.US_ASCII)
        } catch (_: Exception) { input.shutdownNow(); throw InventoryStorageUnavailable() }
        try { check(response() == "READY") } catch (error: Exception) { close(); throw error }
    }
    override fun toString() = "PythonPhotoInventoryCursor(REDACTED)"
    private fun response(): String = try {
        input.submit<String> { reader.readLine()?.also { check(it.length <= 100) } ?: error("Closed metadata pipe") }
            .get(5,TimeUnit.SECONDS)
    } catch (error: InterruptedException) { throw error }
      catch (_: Exception) { throw InventoryStorageUnavailable() }
    private fun command(value: String): String = try {
        writer.write(value); writer.newLine(); writer.flush(); response()
    } catch (error: InterruptedException) { throw error }
      catch (_: Exception) { throw InventoryStorageUnavailable() }
    override fun next(): PhotoInventoryEntry? {
        check(!closed && sequence <= 1000)
        val value = command("NEXT")
        if (value == "END") return null
        val parts = value.split('\t')
        if (parts.size !in 2..3 || parts[0] != "ENTRY") throw InventoryStorageUnavailable()
        val kind = when (parts[1]) { "OBJECT" -> InventoryEntryKind.OBJECT; "TEMPORARY" -> InventoryEntryKind.TEMPORARY; "UNSAFE" -> InventoryEntryKind.UNSAFE; else -> throw InventoryStorageUnavailable() }
        val id = if (kind == InventoryEntryKind.OBJECT) {
            if (parts.size != 3) throw InventoryStorageUnavailable()
            try { UUID.fromString(parts[2]).also { check(it.toString() == parts[2]) } }
            catch (_: Exception) { throw InventoryStorageUnavailable() }
        } else { if (parts.size != 2) throw InventoryStorageUnavailable(); null }
        return PhotoInventoryEntry(kind,id,sequence++)
    }
    private fun stable(value: String): Boolean = when (value) { "STABLE" -> true; "CHANGED" -> false; else -> throw InventoryStorageUnavailable() }
    override fun unchanged(entry: PhotoInventoryEntry): Boolean {
        val index = entry.observed as? Int ?: throw InventoryStorageUnavailable()
        check(index in 0 until sequence)
        return stable(command("CHECK\t$index"))
    }
    override fun stableDirectory() = stable(command("STABLE"))
    override fun close() {
        if (closed) return
        closed = true
        process.destroyForcibly() // No persistent helper or filesystem side effects remain.
        try { process.waitFor(2,TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        runCatching { writer.close() }; runCatching { reader.close() }; input.shutdownNow()
    }
}
