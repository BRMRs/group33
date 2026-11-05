package com.group33.server.photo.storage

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Unregistered POSIX/local-filesystem adapter. Its existing parent must be trusted and private
 * root must be controlled exclusively by the service account. Not for shared/network filesystems.
 * No delete/age-cleanup API: reference/lease/reconciliation checks belong to the business layer.
 */
internal class LocalFilePhotoObjectStore(configuredRoot: Path) : PhotoObjectStore {
    private val root: Path = storageIo {
        val absolute = configuredRoot.toAbsolutePath().normalize()
        val parent = absolute.parent ?: unavailable()
        val canonical = parent.toRealPath().resolve(absolute.fileName)
        try {
            Files.createDirectory(canonical, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
        } catch (_: FileAlreadyExistsException) {
            // Validate existing root without following a final symlink or widening its permissions.
        }
        checkRoot(canonical)
        canonical
    }

    override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey = storageIo {
        checkRoot(root)
        val target = objectPath(key)
        if (Files.exists(target, NOFOLLOW_LINKS)) return@storageIo verifyExisting(key)
        val temporary = Files.createTempFile(
            root, ".upload-", ".part", PosixFilePermissions.asFileAttribute(PRIVATE_WRITABLE),
        )
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { file ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(16 * 1024)
                var count = 0L
                while (true) {
                    var length = source.read(buffer)
                    if (length < 0) break
                    if (length == 0) {
                        val one = source.read()
                        if (one < 0) break
                        buffer[0] = one.toByte()
                        length = 1
                    }
                    count += length
                    if (count > key.byteCount) integrityMismatch()
                    digest.update(buffer, 0, length)
                    val chunk = ByteBuffer.wrap(buffer, 0, length)
                    while (chunk.hasRemaining()) file.write(chunk)
                }
                if (count != key.byteCount || hex(digest.digest()) != key.sha256) integrityMismatch()
                file.force(true)
            }
            Files.setPosixFilePermissions(temporary, PRIVATE_READABLE)
            try {
                // createLink fails if target exists. Never use a replace/atomic-move overwrite path.
                Files.createLink(target, temporary)
            } catch (_: FileAlreadyExistsException) {
                return@storageIo verifyExisting(key)
            }
            forceDirectory()
            key
        } finally {
            // A process crash can leave private .part files; no unsafe automatic age cleanup.
            Files.deleteIfExists(temporary)
        }
    }

    override fun read(key: PhotoObjectKey): ByteArray = storageIo {
        checkRoot(root)
        readContent(key)
    }

    private fun verifyExisting(key: PhotoObjectKey): PhotoObjectKey {
        try {
            readContent(key)
        } catch (error: PhotoObjectException) {
            if (error.failure == PhotoObjectFailure.INTEGRITY_MISMATCH) {
                throw PhotoObjectException(PhotoObjectFailure.OBJECT_CONFLICT)
            }
            throw error
        }
        // Reconcile a successful link whose caller lost the response before directory sync.
        forceDirectory()
        return key
    }

    private fun readContent(key: PhotoObjectKey): ByteArray {
        val target = objectPath(key)
        val attributes = try {
            Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            throw PhotoObjectException(PhotoObjectFailure.MISSING)
        }
        if (!attributes.isRegularFile) unavailable()
        val permissions = Files.getPosixFilePermissions(target, NOFOLLOW_LINKS)
        if (!permissions.contains(PosixFilePermission.OWNER_READ) || !PRIVATE_WRITABLE.containsAll(permissions)) {
            unavailable()
        }
        FileChannel.open(target, READ, NOFOLLOW_LINKS).use { file ->
            if (file.size() != key.byteCount) integrityMismatch()
            // One extra byte detects a length change without trusting an unbounded filesystem size.
            val buffer = ByteBuffer.allocate(key.byteCount.toInt() + 1)
            while (buffer.hasRemaining() && file.read(buffer) != -1) { /* bounded regular-file read */ }
            if (buffer.position().toLong() != key.byteCount) integrityMismatch()
            val bytes = buffer.array().copyOf(buffer.position())
            if (hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != key.sha256) integrityMismatch()
            return bytes
        }
    }

    private fun objectPath(key: PhotoObjectKey): Path = root.resolve("${key.objectId}.blob")

    private fun forceDirectory() {
        FileChannel.open(root, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private companion object {
        val PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        val PRIVATE_WRITABLE = PosixFilePermissions.fromString("rw-------")
        val PRIVATE_READABLE = PosixFilePermissions.fromString("r--------")

        fun checkRoot(path: Path) {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) != PRIVATE_DIRECTORY) {
                unavailable()
            }
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        fun integrityMismatch(): Nothing = throw PhotoObjectException(PhotoObjectFailure.INTEGRITY_MISMATCH)
        fun unavailable(): Nothing = throw PhotoObjectException(PhotoObjectFailure.UNAVAILABLE)

        inline fun <T> storageIo(work: () -> T): T = try {
            work()
        } catch (_: IOException) {
            unavailable()
        } catch (_: UnsupportedOperationException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
    }
}
