package com.group33.server.config

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

internal object PrivateRuntimeFiles {
    fun secret(path: String): String {
        val file = Path.of(path)
        require(file.isAbsolute && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        val forbidden = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        require(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).none { it in forbidden })
        require(Files.size(file) in 1..4096)
        val bytes = Files.newInputStream(file).use { it.readNBytes(4097) }; require(bytes.size <= 4096)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().trimEnd('\r', '\n')
    }
}
