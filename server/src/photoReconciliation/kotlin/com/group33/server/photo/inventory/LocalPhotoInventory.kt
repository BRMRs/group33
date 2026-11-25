package com.group33.server.photo.inventory

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Flat, trusted POSIX root only. Never opens file contents, recurses, follows links or changes files. */
class LocalPhotoInventory(configuredRoot: Path) : PhotoInventoryObjects {
    private val root = configuredRoot.toAbsolutePath().normalize().let {
        requireNotNull(it.parent).toRealPath().resolve(it.fileName)
    }
    init { checkedRoot() }
    override fun toString() = "LocalPhotoInventory(REDACTED)"
    private fun checkedRoot(): BasicFileAttributes {
        val attrs = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attrs.isDirectory && attrs.fileKey() != null)
        check(Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
        return attrs
    }
    override fun open(): PhotoInventoryCursor = storage {
        val before = checkedRoot()
        val stream = Files.newDirectoryStream(root)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            val identity = Files.readAttributes(root, "unix:dev,ino", NOFOLLOW_LINKS)
            check(before.fileKey() == checkedRoot().fileKey())
            return@storage PythonPhotoInventoryCursor(root, identity.getValue("dev").toString(), identity.getValue("ino").toString())
        }
        try {
            val anchored = stream.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
            check(before.fileKey() == anchored.fileKey())
            Cursor(stream, before)
        } catch (error: Exception) { stream.close(); throw error }
    }
    private data class Signature(val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime,
                                 val permissions: Set<java.nio.file.attribute.PosixFilePermission>, val owner: String,
                                 val regular: Boolean, val link: Boolean, val directory: Boolean)
    private class Observation(val relative: Path, val signature: Signature) {
        override fun toString() = "PhotoInventoryObservation(REDACTED)"
    }
    private inner class Cursor(private val stream: SecureDirectoryStream<Path>, private val before: BasicFileAttributes) : PhotoInventoryCursor {
        private val iterator = stream.iterator()
        private fun signature(relative: Path): Signature {
            val basic = stream.getFileAttributeView(relative, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            val posix = stream.getFileAttributeView(relative, PosixFileAttributeView::class.java, NOFOLLOW_LINKS).readAttributes()
            return Signature(basic.fileKey(), basic.size(), basic.lastModifiedTime(), posix.permissions(), posix.owner().name,
                basic.isRegularFile, basic.isSymbolicLink, basic.isDirectory)
        }
        override fun next(): PhotoInventoryEntry? = storage {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (!iterator.hasNext()) return@storage null
            val relative = iterator.next().fileName
            val observed = signature(relative)
            val privateRegular = observed.regular && observed.key != null &&
                observed.permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ) &&
                PosixFilePermissions.fromString("rw-------").containsAll(observed.permissions)
            val name = relative.toString()
            val id = if (privateRegular && name.endsWith(".blob")) runCatching {
                val raw = name.removeSuffix(".blob")
                UUID.fromString(raw).also { require(it.toString() == raw) }
            }.getOrNull() else null
            val kind = when {
                id != null -> InventoryEntryKind.OBJECT
                privateRegular && name.matches(Regex("\\.upload-[a-zA-Z0-9_-]+\\.part")) -> InventoryEntryKind.TEMPORARY
                else -> InventoryEntryKind.UNSAFE
            }
            PhotoInventoryEntry(kind, id, Observation(relative, observed))
        }
        override fun unchanged(entry: PhotoInventoryEntry): Boolean = storage {
            val observation = entry.observed as? Observation ?: throw InventoryStorageUnavailable()
            try { signature(observation.relative) == observation.signature }
            catch (_: NoSuchFileException) { false }
        }
        override fun stableDirectory(): Boolean = storage {
            val after = try { Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { return@storage false }
            after.isDirectory && before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime() &&
                Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------")
        }
        override fun close() = storage { stream.close() }
    }
    private inline fun <T> storage(work: () -> T): T = try { work() }
        catch (error: InterruptedException) { throw error }
        catch (_: Exception) { throw InventoryStorageUnavailable() }
}
