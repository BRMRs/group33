package com.group33.server.migration

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import org.postgresql.ds.PGSimpleDataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** No network is attempted: the data source is a counting sentinel, including on the old red path. */
class ManualSchemaCopyBoundaryTest {
    @TempDir lateinit var directory: Path
    private val reference get() = ManualSchemaCopy.Reference("group33_fixture", "a".repeat(64), "none")
    private val target get() = ManualSchemaCopy.Reference("group33_target", "b".repeat(64), "10:" + "c".repeat(64))
    private fun permissions(path: Path, mode: String) = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))

    @Test fun `all library entry points reject every URL option before requesting a connection`() {
        for (query in listOf("host=never-connect.invalid", "socketFactory=never.LoadMe", "currentSchema=public", "connectTimeout=5", "")) {
            val attempts = AtomicInteger()
            val source = object : PGSimpleDataSource() {
                override fun getURL() = "jdbc:postgresql://127.0.0.1/fictional?$query"
                override fun getConnection(): Connection { attempts.incrementAndGet(); throw SQLException("No real connection") }
                override fun getConnection(user: String?, password: String?): Connection = connection
            }
            val calls = listOf<() -> Unit>(
                { ManualSchemaCopy.inspect(source, reference.schema(), false) },
                { ManualSchemaCopy.inspect(source, target.schema(), true) },
                { ManualSchemaCopy.copy(source, reference, target) },
                { ManualSchemaCopy.loopbackSource(source.getURL(), "fictional", "fictional") },
            )
            for (call in calls) {
                assertThrows(IllegalArgumentException::class.java) { call() }
                assertEquals(0, attempts.get(), "URL must be rejected before any DataSource connection request")
            }
        }
    }
    @Test fun `former non private guard mode cannot bypass URL option rejection`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualSchemaCopy.requireLoopback("jdbc:postgresql://127.0.0.1/fictional?host=never-connect.invalid", false)
        }
    }
    @Test fun `moving a valid private reference into a public directory is refused`() {
        val private = Files.createDirectory(directory.resolve("private")); permissions(private, "rwx------")
        val public = Files.createDirectory(directory.resolve("public")); permissions(public, "rwxr-xr-x")
        val file = private.resolve("source.properties"); ManualSchemaCopy.writeReference(file.toString(), reference)
        val moved = public.resolve("source.properties"); Files.move(file, moved, StandardCopyOption.ATOMIC_MOVE)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(moved))
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.readReference(moved.toString()) }
    }
    @Test fun `symlinked parent directory is refused even when target is private`() {
        val private = Files.createDirectory(directory.resolve("private")); permissions(private, "rwx------")
        val file = private.resolve("source.properties"); ManualSchemaCopy.writeReference(file.toString(), reference)
        val alias = directory.resolve("alias"); Files.createSymbolicLink(alias, private)
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.readReference(alias.resolve(file.fileName).toString()) }
    }
    @Test fun `private parent must have its exact documented mode`() {
        val private = Files.createDirectory(directory.resolve("private")); permissions(private, "rwx------")
        val file = private.resolve("source.properties"); ManualSchemaCopy.writeReference(file.toString(), reference)
        permissions(private, "rwx--x---")
        assertThrows(IllegalArgumentException::class.java) { ManualSchemaCopy.readReference(file.toString()) }
    }
}
