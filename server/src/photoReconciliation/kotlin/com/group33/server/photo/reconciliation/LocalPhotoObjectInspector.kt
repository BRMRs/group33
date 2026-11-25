package com.group33.server.photo.reconciliation

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/** Existing, trusted POSIX directory only. No create, write, unlink, age sweep or source consumption. */
class LocalPhotoObjectInspector(configuredRoot: Path) : PhotoObjectInspector {
    private val root: Path
    init {
        val absolute = configuredRoot.toAbsolutePath().normalize()
        root = requireNotNull(absolute.parent).toRealPath().resolve(absolute.fileName)
        checkRoot()
    }
    override fun toString() = "LocalPhotoObjectInspector(REDACTED)"

    private fun checkRoot() {
        check(Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory)
        check(Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
    }

    override fun inspect(key: PhotoInspectionKey): PhotoObjectObservation {
      return try {
        checkRoot()
        val path = root.resolve("${key.id}.blob")
        val before = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) { checkRoot(); return PhotoObjectObservation.MISSING }
        check(before.isRegularFile)
        val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        check(permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ) &&
            PosixFilePermissions.fromString("rw-------").containsAll(permissions))
        val outcome = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != key.bytes) return@use PhotoObjectObservation.INTEGRITY_MISMATCH
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(16 * 1024)
            var count = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), key.bytes - count + 1).toInt())
                val size = channel.read(buffer)
                if (size < 0) break
                count += size
                if (count > key.bytes) return@use PhotoObjectObservation.INTEGRITY_MISMATCH
                digest.update(buffer.array(), 0, size)
            }
            if (count == key.bytes && HexFormat.of().formatHex(digest.digest()) == key.sha256)
                PhotoObjectObservation.VERIFIED else PhotoObjectObservation.INTEGRITY_MISMATCH
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime())
            PhotoObjectObservation.UNAVAILABLE else outcome
    } catch (failure: InterruptedException) { throw failure }
      catch (_: Exception) { PhotoObjectObservation.UNAVAILABLE }
    }
}
