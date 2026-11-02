package com.group33.server.photo.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalPhotoInventoryTest {
    @TempDir lateinit var temporary: Path
    private fun root() = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private fun file(root: Path, name: String) = Files.writeString(root.resolve(name),"fictional-private-content").also {
        Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rw-------"))
    }
    private fun source(work: (Set<UUID>) -> Map<UUID,PhotoReference> = { ids -> ids.associateWith { PhotoReference.UNMATCHED } }) =
        object : PhotoInventorySource { override fun <T> read(block: (PhotoInventorySnapshot) -> T) = block(PhotoInventorySnapshot(work)) }
    private fun inspect(root: Path, work: (Set<UUID>) -> Map<UUID,PhotoReference> = { ids -> ids.associateWith { PhotoReference.UNMATCHED } }) =
        PhotoInventoryService(source(work),LocalPhotoInventory(root)).inspect()

    @Test fun `flat inventory observes regular object and part without reading or changing contents`() {
        val root = root(); val id = UUID.randomUUID(); val objectFile = file(root,"$id.blob")
        val part = file(root,".upload-fictional.part"); val unknown = file(root,"unknown.blob")
        val nested = Files.createDirectory(root.resolve("nested")); val child = file(nested,"${UUID.randomUUID()}.blob")
        val files = listOf(objectFile,part,unknown,child)
        files.forEach { Files.setLastModifiedTime(it,FileTime.fromMillis(1000)) }
        val bytes = files.map { Files.readAllBytes(it) }; val permissions = files.map { Files.getPosixFilePermissions(it) }
        val result = inspect(root)
        assertEquals(InventoryCompletion.COMPLETE,result.completion)
        assertEquals(1,result.unmatchedCandidates); assertEquals(1,result.temporary); assertEquals(2,result.unsafe)
        files.forEachIndexed { i,p ->
            assertArrayEquals(bytes[i],Files.readAllBytes(p)); assertEquals(FileTime.fromMillis(1000),Files.getLastModifiedTime(p))
            assertEquals(permissions[i],Files.getPosixFilePermissions(p))
            assertFalse(result.json().contains(p.toString()))
        }
    }

    @Test fun `symlink object and directory are unsafe and their outside targets remain untouched`() {
        val root = root(); val outside = file(temporary,"outside")
        Files.setLastModifiedTime(outside,FileTime.fromMillis(2000))
        Files.createSymbolicLink(root.resolve("${UUID.randomUUID()}.blob"),outside)
        Files.createSymbolicLink(root.resolve("outside-dir"),temporary)
        val result = inspect(root) { error("symlinks must never query object identity") }
        assertEquals(InventoryCompletion.COMPLETE,result.completion); assertEquals(2,result.unsafe)
        assertEquals("fictional-private-content",Files.readString(outside)); assertEquals(FileTime.fromMillis(2000),Files.getLastModifiedTime(outside))
    }

    @Test fun `fifo and unreadable or public file are unsafe without blocking on contents`() {
        val root = root(); val fifo = root.resolve("${UUID.randomUUID()}.blob")
        val process = ProcessBuilder("mkfifo",fifo.toString()).start(); assertEquals(0,process.waitFor())
        val denied = file(root,"${UUID.randomUUID()}.blob")
        Files.setPosixFilePermissions(denied,PosixFilePermissions.fromString("---------"))
        val public = file(root,"${UUID.randomUUID()}.blob")
        Files.setPosixFilePermissions(public,PosixFilePermissions.fromString("rw-r--r--"))
        val result = assertTimeoutPreemptively<PhotoInventoryReport>(Duration.ofSeconds(3)) { inspect(root) { error("unsafe entries are not queried") } }
        assertEquals(InventoryCompletion.COMPLETE,result.completion); assertEquals(3,result.unsafe)
    }

    @Test fun `abbreviated or uppercase UUID object names remain unsafe`() {
        val root = root(); file(root,"1-1-1-1-1.blob"); file(root,"ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB.blob")
        val result = inspect(root) { error("noncanonical names are not identities") }
        assertEquals(InventoryCompletion.COMPLETE,result.completion); assertEquals(2,result.unsafe)
    }

    @Test fun `missing linked or public root is rejected without creation or repair`() {
        val missing = temporary.resolve("missing")
        assertThrows(Exception::class.java) { LocalPhotoInventory(missing) }; assertFalse(Files.exists(missing))
        val root = root(); val link = Files.createSymbolicLink(temporary.resolve("link"),root)
        assertThrows(Exception::class.java) { LocalPhotoInventory(link) }
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-x---"))
        assertThrows(Exception::class.java) { LocalPhotoInventory(root) }
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"),Files.getPosixFilePermissions(root))
    }

    @Test fun `object rewrite during database lookup invalidates its observation`() {
        val root = root(); val id = UUID.randomUUID(); val objectFile = file(root,"$id.blob")
        val result = inspect(root) { ids ->
            Files.writeString(objectFile,"changed-fictional-content")
            Files.setLastModifiedTime(objectFile,FileTime.fromMillis(9000))
            ids.associateWith { PhotoReference.UNMATCHED }
        }
        assertEquals(InventoryCompletion.CONCURRENT_CHANGE,result.completion); assertEquals(0,result.checked)
        assertTrue(Files.exists(objectFile))
    }

    @Test fun `directory creation during lookup prevents a false full scan`() {
        val root = root(); file(root,"${UUID.randomUUID()}.blob")
        Files.setLastModifiedTime(root,FileTime.fromMillis(1000))
        val result = inspect(root) { ids -> file(root,"${UUID.randomUUID()}.blob"); ids.associateWith { PhotoReference.UNMATCHED } }
        assertEquals(InventoryCompletion.CONCURRENT_CHANGE,result.completion); assertEquals(0,result.checked)
        assertEquals(2L,Files.list(root).use { it.count() })
    }

    @Test fun `root replacement with link cannot redirect open cursor into another directory`() {
        val originalId = UUID.randomUUID(); val externalId = UUID.randomUUID()
        val root = root(); file(root,"$originalId.blob")
        val outside = root(); val target = file(outside,"$externalId.blob")
        val bytes = Files.readAllBytes(target); val modified = Files.getLastModifiedTime(target)
        LocalPhotoInventory(root).open().use { cursor ->
            val moved = root.resolveSibling("moved")
            Files.move(root,moved); Files.createSymbolicLink(root,outside)
            assertFalse(cursor.stableDirectory())
            val observed = requireNotNull(cursor.next())
            assertEquals(originalId,observed.objectId); assertTrue(cursor.unchanged(observed))
            assertNull(cursor.next()); assertNotEquals(externalId,observed.objectId)
            assertArrayEquals(bytes,Files.readAllBytes(target)); assertEquals(modified,Files.getLastModifiedTime(target))
        }
    }

    @Test fun `private settings reuse loopback validation and reject cloud host or delete commands`() {
        val root = root(); val password = file(temporary,"password")
        val env = mapOf("DATABASE" to "fictional_db","PORT" to "55432","SCHEMA" to "fictional_schema",
            "USER" to "fictional_reader","PASSWORD_FILE" to password.toString(),"OBJECT_ROOT" to root.toString())
            .mapKeys { "GROUP33_PHOTO_INVENTORY_" + it.key }
        val settings = PhotoInventorySettings.from(env)
        assertEquals("PhotoInventorySettings(REDACTED)",settings.toString())
        assertThrows(IllegalArgumentException::class.java) { PhotoInventorySettings.from(env + ("GROUP33_PHOTO_INVENTORY_HOST" to "cloud.invalid")) }
        assertThrows(IllegalArgumentException::class.java) { PhotoInventorySettings.from(env + ("GROUP33_PHOTO_INVENTORY_MAX_ENTRIES" to "0")) }
        Files.setPosixFilePermissions(password,PosixFilePermissions.fromString("rw-r--r--"))
        val failure = assertThrows(IllegalArgumentException::class.java) { PhotoInventorySettings.from(env) }
        assertEquals("Invalid private photo inventory settings",failure.message)
        val messages = mutableListOf<String>()
        assertEquals(64,PhotoInventoryCli.execute(arrayOf("delete"),env,messages::add))
        assertEquals(listOf("""{"error":"INVALID_PHOTO_INVENTORY_INPUT"}"""),messages)
    }
}
