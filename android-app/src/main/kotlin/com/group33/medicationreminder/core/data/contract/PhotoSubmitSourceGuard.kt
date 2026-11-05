package com.group33.medicationreminder.core.data.contract

import java.io.IOException

/**
 * Elder photo submit must open the retained [PhotoSource] before any network attempt.
 * An unreadable source stays [OnlineCommandResult.NotSubmitted] with the original operationId
 * so UI must not present the upload as recorded or accepted.
 */
object PhotoSubmitSourceGuard {
    fun <T> submitAfterOpeningSource(
        source: PhotoSource,
        command: CommandContext,
        transmit: (ByteArray) -> OnlineCommandResult<T>,
    ): OnlineCommandResult<T> {
        val bytes = try {
            source.openStream().use { it.readBytes() }
        } catch (_: IOException) {
            return OnlineCommandResult.NotSubmitted(
                command.operationId,
                RepositoryError.Local(LocalFailureCode.SOURCE_UNAVAILABLE),
            )
        }
        return transmit(bytes)
    }
}
