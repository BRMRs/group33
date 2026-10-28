package com.group33.server.photo.storage

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Explicit server-only adapter. Caller must authorize first and publish READY/receipt separately.
 * No Bean, public/signed URL, overwrite, delete, bucket creation or automatic retry is registered.
 */
internal class SupabasePhotoObjectStore(
    projectOrigin: URI, private val bucket: String, private val secretKey: String,
    private val requestBudget: Duration = Duration.ofSeconds(120),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
) : PhotoObjectStore {
    private val origin: String
    private val reader = ObjectMapper().readerFor(JsonNode::class.java).with(
        DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
    init {
        require(projectOrigin.scheme == "https" && projectOrigin.host != null && projectOrigin.userInfo == null &&
            projectOrigin.rawQuery == null && projectOrigin.fragment == null && projectOrigin.rawPath in listOf("", "/") &&
            (projectOrigin.port == -1 || projectOrigin.port in 1..65535) && bucket.matches(Regex("[a-z0-9][a-z0-9_-]{0,62}")) &&
            secretKey.matches(Regex("sb_secret_[A-Za-z0-9_-]{16,256}")) &&
            requestBudget >= Duration.ofMillis(1) && requestBudget <= Duration.ofSeconds(120) &&
            client.followRedirects() == HttpClient.Redirect.NEVER) { "Invalid private Supabase storage configuration" }
        origin = projectOrigin.toASCIIString().removeSuffix("/") + "/storage/v1"
    }
    override fun toString() = "SupabasePhotoObjectStore(REDACTED)"

    override fun putIfAbsent(key: PhotoObjectKey, source: InputStream): PhotoObjectKey = safe {
        val budget = Budget(requestBudget)
        checkPrivateBucket(budget)
        try {
            load(key, budget)
            return@safe key // Already proven bytes: do not consume caller-owned retry source.
        } catch (failure: PhotoObjectException) {
            when (failure.failure) {
                PhotoObjectFailure.MISSING -> Unit
                PhotoObjectFailure.INTEGRITY_MISMATCH -> fail(PhotoObjectFailure.OBJECT_CONFLICT)
                else -> throw failure
            }
        }
        val bytes = readSource(key, source, budget)
        val response = exchange("/object/$bucket/${key.objectId}.blob", budget, JSON_LIMIT, PhotoObjectFailure.UNAVAILABLE,
            HttpRequest.BodyPublishers.ofByteArray(bytes))
        val duplicate = response.statusCode() == 409 || response.statusCode() == 400 &&
            errorCode(response.body()) in setOf("Duplicate", "ResourceAlreadyExists", "KeyAlreadyExists")
        if (response.statusCode() !in setOf(200, 201) && !duplicate) unavailable()
        // Neither a success status nor an ETag proves the stored content matches this immutable key.
        try { load(key, budget) } catch (failure: PhotoObjectException) {
            when (failure.failure) {
                PhotoObjectFailure.INTEGRITY_MISMATCH -> fail(PhotoObjectFailure.OBJECT_CONFLICT)
                PhotoObjectFailure.MISSING -> unavailable() // Upload completion is unproven.
                else -> throw failure
            }
        }
        key
    }

    override fun read(key: PhotoObjectKey): ByteArray = safe {
        val budget = Budget(requestBudget)
        checkPrivateBucket(budget)
        load(key, budget)
    }

    private fun checkPrivateBucket(budget: Budget) {
        val response = exchange("/bucket/$bucket", budget, JSON_LIMIT, PhotoObjectFailure.UNAVAILABLE)
        if (response.statusCode() != 200) unavailable()
        val node = json(response.body())
        if (node["id"]?.isTextual != true || node["id"].textValue() != bucket ||
            node["public"]?.isBoolean != true || node["public"].booleanValue()) unavailable()
    }
    private fun load(key: PhotoObjectKey, budget: Budget): ByteArray {
        val response = exchange("/object/authenticated/$bucket/${key.objectId}.blob", budget,
            key.byteCount.toInt() + 1, PhotoObjectFailure.INTEGRITY_MISMATCH)
        if (response.statusCode() == 404 && errorCode(response.body()) == "NoSuchKey") fail(PhotoObjectFailure.MISSING)
        if (response.statusCode() != 200) unavailable()
        return response.body().also { verify(key, it); budget.remaining() }
    }
    private fun readSource(key: PhotoObjectKey, source: InputStream, budget: Budget): ByteArray {
        val output = ByteArrayOutputStream(minOf(key.byteCount.toInt(), 8192)); val buffer = ByteArray(16 * 1024)
        while (true) {
            budget.remaining()
            var n = source.read(buffer)
            if (n < 0) break
            if (n == 0) { val value = source.read(); if (value < 0) break; buffer[0] = value.toByte(); n = 1 }
            if (output.size().toLong() + n > key.byteCount) fail(PhotoObjectFailure.INTEGRITY_MISMATCH)
            output.write(buffer, 0, n)
        }
        return output.toByteArray().also { verify(key, it) }
    }
    private fun verify(key: PhotoObjectKey, bytes: ByteArray) {
        if (bytes.size.toLong() != key.byteCount || MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) } != key.sha256) fail(PhotoObjectFailure.INTEGRITY_MISMATCH)
    }

    private fun exchange(path: String, budget: Budget, successLimit: Int, overflow: PhotoObjectFailure,
        body: HttpRequest.BodyPublisher? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofNanos(budget.remaining()))
            .header("apikey", secretKey).header("Accept-Encoding", "identity").header("Cache-Control", "no-store")
        if (body == null) builder.GET() else builder.header("Content-Type", "application/octet-stream")
            .header("x-upsert", "false").POST(body)
        // New sb_secret keys use apikey, not Authorization: Bearer; group33 JWTs never leave the business API.
        val handler = HttpResponse.BodyHandler<ByteArray> { info ->
            val success = info.statusCode() in 200..299
            BoundedBody(if (success) successLimit else JSON_LIMIT, if (success) overflow else PhotoObjectFailure.UNAVAILABLE)
        }
        val pending = client.sendAsync(builder.build(), handler)
        try { return pending.get(budget.remaining(), TimeUnit.NANOSECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); unavailable() }
        catch (failure: java.util.concurrent.ExecutionException) {
            (failure.cause as? PhotoObjectException)?.let { throw it }; unavailable()
        } catch (_: TimeoutException) { unavailable() }
        finally { if (!pending.isDone) pending.cancel(true) }
    }
    private fun json(bytes: ByteArray): JsonNode = reader.readValue<JsonNode?>(bytes)?.takeIf { it.isObject } ?: unavailable()
    private fun errorCode(bytes: ByteArray): String? {
        val node = json(bytes)
        return (node["code"] ?: node["error"])?.takeIf { it.isTextual }?.textValue()
    }
    private class Budget(duration: Duration) {
        private val start = System.nanoTime(); private val nanos = duration.toNanos()
        fun remaining(): Long = (nanos - (System.nanoTime() - start)).takeIf { it > 0 } ?: unavailable()
    }
    private class BoundedBody(private val limit: Int, private val overflow: PhotoObjectFailure) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>(); private val bytes = ByteArrayOutputStream(minOf(limit, 8192))
        private lateinit var subscription: Flow.Subscription
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
        override fun onNext(items: List<ByteBuffer>) {
            for (item in items) {
                if (item.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(PhotoObjectException(overflow)); return
                }
                val chunk = ByteArray(item.remaining()); item.get(chunk); bytes.write(chunk)
            }
            subscription.request(1)
        }
        override fun onError(failure: Throwable) { result.completeExceptionally(failure) }
        override fun onComplete() { result.complete(bytes.toByteArray()) }
    }
    private companion object {
        const val JSON_LIMIT = 16 * 1024
        fun fail(reason: PhotoObjectFailure): Nothing = throw PhotoObjectException(reason)
        fun unavailable(): Nothing = fail(PhotoObjectFailure.UNAVAILABLE)
        inline fun <T> safe(work: () -> T): T = try { work() }
            catch (failure: PhotoObjectException) { throw failure }
            catch (_: Exception) { unavailable() }
    }
}
