package com.group33.medicationreminder.elder.photo

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.group33.medicationreminder.core.data.contract.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivatePhotoDraftStoreTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): File = File.createTempFile("photo-test-", ".png", app.cacheDir).apply {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun originalBytesRemainReadableAfterProviderFileDisappearsAndStoreRecreation() = runBlocking {
        val namespace = UUID.randomUUID().toString()
        val input = fixture()
        val original = input.readBytes()
        val store = PrivatePhotoDraftStore(app, namespace)
        val draft = store.importPhoto(Uri.fromFile(input).toString())
        val preview = PhotoPreviewDecoder.decode(draft.previewPath)
        assertNotNull("A real imported photo must decode without crashing", preview)
        preview?.recycle()
        assertTrue(input.delete())
        val restored = PrivatePhotoDraftStore(app, namespace).restore()!!
        assertNull(restored.second)
        assertArrayEquals(original, restored.first.source.openStream().use { it.readBytes() })
        assertArrayEquals(original, restored.first.source.openStream().use { it.readBytes() })
        assertEquals("image/png", restored.first.source.contentType)
        assertEquals(original.size.toLong(), restored.first.source.byteCount)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }, draft.source.sha256)
    }

    @Test fun attemptRetainsOriginalContextAndOldPhotoAcrossReplacement() = runBlocking {
        val store = PrivatePhotoDraftStore(app, UUID.randomUUID().toString())
        val input = fixture()
        try {
            val first = store.importPhoto(Uri.fromFile(input).toString())
            val context = CommandContext(UUID.randomUUID().toString(), RepositoryScope("test", "install", "elder"), OnlineCommandKind.SUBMIT_PHOTO, null)
            store.markAttempted(first, context)
            assertEquals(context, store.restore()!!.second)
            val second = store.importPhoto(Uri.fromFile(input).toString())
            assertNotEquals(first.id, second.id)
            assertTrue(File(first.previewPath).exists())
            assertTrue(File(first.previewPath + ".attempted").exists())
            assertEquals(second.id, store.restore()!!.first.id)
            assertNull(store.restore()!!.second)
        } finally { input.delete() }
    }

    @Test fun overLimitAndNonImageDoNotReplaceExistingDraft() = runBlocking {
        val store = PrivatePhotoDraftStore(app, UUID.randomUUID().toString())
        val valid = fixture()
        val original = store.importPhoto(Uri.fromFile(valid).toString())
        val bad = File.createTempFile("bad-", ".png", app.cacheDir)
        try {
            for (size in listOf(4, 5_242_881)) {
                bad.outputStream().use { output ->
                    val block = ByteArray(4096)
                    var remaining = size
                    while (remaining > 0) { val count = minOf(block.size, remaining); output.write(block, 0, count); remaining -= count }
                }
                var rejected = false
                try { store.importPhoto(Uri.fromFile(bad).toString()) } catch (_: Exception) { rejected = true }
                assertTrue(rejected)
                assertEquals(original.id, store.restore()!!.first.id)
            }
        } finally { valid.delete(); bad.delete() }
    }

    @Test fun switchingScopeInTheSameStoreKeepsPrivateSourcesSeparate() = runBlocking {
        var scope = UUID.randomUUID().toString()
        val firstScope = scope
        val store = PrivatePhotoDraftStore(app) { scope }
        val input = fixture()
        try {
            val first = store.importPhoto(Uri.fromFile(input).toString())
            scope = UUID.randomUUID().toString()
            assertNull(store.restore())
            val second = store.importPhoto(Uri.fromFile(input).toString())
            scope = firstScope
            assertEquals(first.id, store.restore()!!.first.id)
            assertNotEquals(second.id, store.restore()!!.first.id)
        } finally { input.delete() }
    }

    @Test fun namespacesCannotRestoreAnotherSessionsPhoto() = runBlocking {
        val input = fixture()
        try {
            PrivatePhotoDraftStore(app, UUID.randomUUID().toString()).importPhoto(Uri.fromFile(input).toString())
            assertNull(PrivatePhotoDraftStore(app, UUID.randomUUID().toString()).restore())
        } finally { input.delete() }
    }

    @Test fun inFlightImportKeepsOriginalScopeWhenIoWaitsAcrossAccountChange() = runBlocking {
        val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString()
        var selected = a
        val caller = Thread.currentThread()
        val release = CountDownLatch(1)
        val store = PrivatePhotoDraftStore(app) {
            if (Thread.currentThread() != caller) release.await()
            selected
        }
        val input = fixture()
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { store.importPhoto(Uri.fromFile(input).toString()) }
            selected = b
            release.countDown()
            val draft = pending.await()
            assertEquals(draft.id, PrivatePhotoDraftStore(app, a).restore()?.first?.id)
            assertNull(PrivatePhotoDraftStore(app, b).restore())
        } finally {
            release.countDown(); input.delete()
            listOf(a, b).forEach { namespace ->
                val name = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
                File(app.filesDir, "elder-photos/$name").deleteRecursively()
            }
        }
    }

    @Test fun independentStoresOfOneProfileSerializeCurrentAndRetainAttemptedSources() = runBlocking {
        val namespace = UUID.randomUUID().toString()
        val input = fixture()
        val retained = mutableListOf<PhotoDraft>()
        try {
            repeat(20) {
                val a = PrivatePhotoDraftStore(app, namespace).forCurrentScope()
                val b = PrivatePhotoDraftStore(app, namespace).forCurrentScope()
                val first = async(Dispatchers.IO) { a.importPhoto(Uri.fromFile(input).toString()) }
                val second = async(Dispatchers.IO) { b.importPhoto(Uri.fromFile(input).toString()) }
                val ids = setOf(first.await().id, second.await().id)
                val current = requireNotNull(a.restore()).first
                assertTrue(current.id in ids)
                assertTrue(File(current.previewPath).exists())
                a.markAttempted(current, CommandContext(UUID.randomUUID().toString(),
                    RepositoryScope("test", "install", "elder"), OnlineCommandKind.SUBMIT_PHOTO, null))
                retained += current
                retained.forEach { draft -> assertTrue(File(draft.previewPath).exists()) }
            }
        } finally {
            input.delete()
            val name = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
            File(app.filesDir, "elder-photos/$name").deleteRecursively()
        }
    }

    @Test fun replacedUnattemptedSourceCannotBeMarkedForSubmission() = runBlocking {
        val namespace = UUID.randomUUID().toString()
        val input = fixture()
        try {
            val a = PrivatePhotoDraftStore(app, namespace)
            val original = a.importPhoto(Uri.fromFile(input).toString())
            PrivatePhotoDraftStore(app, namespace).importPhoto(Uri.fromFile(input).toString())
            assertFalse(File(original.previewPath).exists())
            var rejected = false
            try {
                a.markAttempted(original, CommandContext(UUID.randomUUID().toString(),
                    RepositoryScope("test", "install", "elder"), OnlineCommandKind.SUBMIT_PHOTO, null))
            } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            assertFalse(File(original.previewPath + ".attempted").exists())
        } finally {
            input.delete()
            val name = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).joinToString("") { "%02x".format(it) }
            File(app.filesDir, "elder-photos/$name").deleteRecursively()
        }
    }
}
