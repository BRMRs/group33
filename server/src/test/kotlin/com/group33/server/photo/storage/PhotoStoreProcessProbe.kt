package com.group33.server.photo.storage

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Separate-JVM filesystem evidence only. Never packaged into the production application. */
internal object PhotoStoreProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = LocalFilePhotoObjectStore(Path.of(args[1]))
        val key = PhotoObjectKey(UUID.fromString(args[2]), args[3].toLong(), args[4])
        if (args[0] == "crash-write") {
            val bytes = Files.readAllBytes(Path.of(args[5]))
            val interrupted = object : InputStream() {
                var first = true
                override fun read(): Int { Runtime.getRuntime().halt(23); return -1 }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!first) Runtime.getRuntime().halt(23)
                    first = false
                    bytes.copyInto(buffer, offset, 0, 10)
                    return 10
                }
            }
            store.putIfAbsent(key, interrupted)
            error("child was expected to halt during the partial write")
        }
        if (args[0] == "write") Files.newInputStream(Path.of(args[5])).use { store.putIfAbsent(key, it) }
        val bytes = store.read(key)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(bytes.size.toLong() == key.byteCount && digest == key.sha256)
        println("OK")
    }
}
