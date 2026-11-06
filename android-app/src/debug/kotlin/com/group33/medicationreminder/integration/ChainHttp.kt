package com.group33.medicationreminder.integration

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug transport only. Credentials never enter a ViewModel, URL, redirect or diagnostic. */
internal open class ChainHttp(base: String) {
    val baseUrl = base.trimEnd('/').also {
        val uri = URI(it)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().isEmpty())
    }

    class Reply(val status: Int, val bytes: ByteArray, val contentType: String?, val retryAfter: String?,
                val requestId: String?) {
        fun text() = bytes.toString(Charsets.UTF_8)
    }

    open suspend fun call(method: String, path: String, token: String? = null, operationId: String? = null,
                     json: String? = null, photo: File? = null, photoType: String? = null): Reply = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/") && !path.contains('?') && !path.contains('#'))
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.requestMethod = method
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            operationId?.let { connection.setRequestProperty("Idempotency-Key", it) }
            if (photo != null) {
                require(photoType in setOf("image/png", "image/jpeg"))
                val boundary = "Group33$operationId"
                val head = ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"medicine\"\r\n" +
                    "Content-Type: $photoType\r\n\r\n").toByteArray()
                val tail = "\r\n--$boundary--\r\n".toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.setFixedLengthStreamingMode((head.size + photo.length() + tail.size).toInt())
                connection.outputStream.use { out ->
                    out.write(head); photo.inputStream().use { it.copyTo(out) }; out.write(tail)
                }
            } else if (json != null) {
                val body = json.toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val limit = if (path.endsWith("/content") && status == 200) 5_242_880 else 2_097_152
            val bytes = stream?.use { bounded(it, limit) } ?: byteArrayOf()
            Reply(status, bytes, connection.contentType?.substringBefore(';'),
                connection.getHeaderField("Retry-After"), connection.getHeaderField("X-Request-ID"))
        } finally { connection.disconnect() }
    }

    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return output.toByteArray()
            check(output.size() + n <= limit)
            output.write(buffer, 0, n)
        }
    }
}
