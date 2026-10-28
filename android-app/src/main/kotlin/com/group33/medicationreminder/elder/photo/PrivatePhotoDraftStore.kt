package com.group33.medicationreminder.elder.photo

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.group33.medicationreminder.core.data.contract.*
import java.util.Properties
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private original bytes, bounded before decode, excluded from backup and never logged. */
class PrivatePhotoDraftStore(context: Context, private val namespace: () -> String = { "local-preview" }) : PhotoDraftStore {
    constructor(context: Context, namespace: String) : this(context, { namespace })
    private val resolver = context.applicationContext.contentResolver
    private val cameraAuthority = "${context.packageName}.elderphotos"
    private val root = File(context.filesDir, "elder-photos")
    private val directory get() = File(root, MessageDigest.getInstance("SHA-256").digest(namespace().toByteArray()).joinToString("") { "%02x".format(it) }).apply { mkdirs() }
    private val mutex = Mutex()

    override suspend fun importPhoto(uri: String): PhotoDraft = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directory
            val file = File(directory, UUID.randomUUID().toString() + ".image")
            try {
                resolver.openInputStream(Uri.parse(uri)).use { input ->
                    requireNotNull(input)
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            total += size
                            require(total <= 5_242_880L)
                            output.write(buffer, 0, size)
                        }
                        output.fd.sync()
                    }
                }
                val draft = readDraft(file)
                writeCurrent(directory, file.name)
                draft
            } catch (failure: Exception) {
                file.delete()
                throw failure
            } finally {
                val selected = Uri.parse(uri)
                if (selected.authority == cameraAuthority && selected.path?.startsWith("/camera/") == true) {
                    try { resolver.delete(selected, null, null) } catch (_: Exception) { /* Cache cleanup can be retried by the OS. */ }
                }
            }
        }
    }

    override suspend fun restore(): Pair<PhotoDraft, CommandContext?>? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directory
            val current = File(directory, "current")
            if (!current.exists()) return@withLock null
            val name = current.readText()
            require(name.matches(Regex("[a-f0-9-]{36}\\.image")))
            val file = File(directory, name)
            val marker = File(directory, "$name.attempted")
            val command = if (marker.exists()) Properties().let { properties ->
                marker.inputStream().use { properties.load(it) }
                CommandContext(properties.getProperty("operationId"),
                    RepositoryScope(properties.getProperty("environment"), properties.getProperty("installation"), properties.getProperty("user")),
                    OnlineCommandKind.SUBMIT_PHOTO, null)
            } else null
            val draft = readDraft(file)
            if (marker.exists()) {
                val metadata = Properties().apply { marker.inputStream().use { load(it) } }
                require(metadata.getProperty("sha256") == draft.source.sha256)
                require(metadata.getProperty("byteCount") == draft.source.byteCount.toString())
                require(metadata.getProperty("contentType") == draft.source.contentType)
            }
            draft to command
        }
    }

    override suspend fun markAttempted(draft: PhotoDraft, command: CommandContext) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directory
            val file = File(draft.previewPath)
            require(file.parentFile?.canonicalFile == directory.canonicalFile)
            val temporary = File(directory, file.name + ".attempting")
            temporary.outputStream().use { stream ->
                Properties().apply {
                    setProperty("sha256", draft.source.sha256)
                    setProperty("byteCount", draft.source.byteCount.toString())
                    setProperty("contentType", draft.source.contentType)
                    setProperty("operationId", command.operationId)
                    setProperty("environment", command.scope.environmentId)
                    setProperty("installation", command.scope.installationId)
                    setProperty("user", command.scope.userId)
                }.store(stream, null)
                stream.fd.sync()
            }
            if (!temporary.renameTo(File(directory, file.name + ".attempted"))) throw IOException("Draft unavailable")
        }
    }

    private fun writeCurrent(directory: File, name: String) {
        val current = File(directory, "current")
        val previous = if (current.exists()) current.readText() else null
        val temporary = File(directory, "current.tmp")
        temporary.outputStream().use { stream -> stream.write(name.toByteArray()); stream.fd.sync() }
        if (!temporary.renameTo(current)) throw IOException("Draft unavailable")
        if (previous != null && previous.matches(Regex("[a-f0-9-]{36}\\.image")) &&
            !File(directory, "$previous.attempted").exists()) File(directory, previous).delete()
    }

    private fun readDraft(file: File): PhotoDraft {
        require(file.length() in 1..5_242_880L)
        val header = ByteArray(8)
        file.inputStream().use { require(it.read(header) == 8) }
        val type = when {
            header.take(3) == listOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) -> "image/jpeg"
            header.contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
            else -> throw IOException("Unsupported image")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        require(bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000L)
        val sample = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / sample.inSampleSize > 1024 || bounds.outHeight / sample.inSampleSize > 1024) sample.inSampleSize *= 2
        val decoded = BitmapFactory.decodeFile(file.path, sample) ?: throw IOException("Unreadable image")
        decoded.recycle()
        val hash = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val source = object : PhotoSource {
            override val contentType = type
            override val byteCount = file.length()
            override val sha256 = hash
            override fun openStream() = FileInputStream(file)
        }
        return PhotoDraft(file.name, source, file.path)
    }
}
