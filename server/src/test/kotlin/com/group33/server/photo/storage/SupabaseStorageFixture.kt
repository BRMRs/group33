package com.group33.server.photo.storage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Test-only HTTPS protocol stand-in. Not a Supabase process/cloud integration or production adapter. */
internal class SupabaseStorageFixture(directory: Path) : AutoCloseable {
    val secret = "sb_secret_FICTIONAL_TEST_ONLY_COMPONENT_2026"
    val bucket = "fictional-photos"
    val objects = ConcurrentHashMap<String, ByteArray>()
    data class Call(val method: String, val path: String, val key: String?, val authorization: String?, val upsert: String?, val contentType: String?)
    val calls = CopyOnWriteArrayList<Call>()
    private val executor = Executors.newCachedThreadPool()
    private val active = AtomicInteger()
    private val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin: URI = URI("https://127.0.0.1:${server.address.port}")
    val client: HttpClient
    @Volatile var metadata: String = privateBucket()
    @Volatile var metadataStatus = 200
    @Volatile var metadataDelay = 0L
    @Volatile var getStatus: Int? = null
    @Volatile var getCode = "NoSuchKey"
    @Volatile var getBody: String? = null
    @Volatile var missingStatus = 404
    @Volatile var missingBody = """{"code":"NoSuchKey"}"""
    @Volatile var getDelay = 0L
    @Volatile var postMode = "normal"
    @Volatile var postDelay = 0L
    @Volatile var stallBody = false

    init {
        val file = directory.resolve("fictional-tls.p12")
        val password = "fictional-test-only".toCharArray()
        val keytool = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/keytool").toString(), "-genkeypair",
            "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", String(password), "-keypass", String(password),
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-noprompt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { check(keytool.waitFor(30, TimeUnit.SECONDS) && keytool.exitValue() == 0) { "Fictional TLS fixture setup failed" } }
        finally { if (keytool.isAlive) keytool.destroyForcibly() }
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        server.httpsConfigurator = HttpsConfigurator(tls)
        server.executor = executor
        server.createContext("/") { exchange ->
            active.incrementAndGet()
            try { handle(exchange) } catch (_: Exception) { exchange.close() } finally { active.decrementAndGet() }
        }
        server.start()
        client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build()
    }
    fun store(budget: Duration = Duration.ofSeconds(10)) = SupabasePhotoObjectStore(origin, bucket, secret, budget, client)
    fun privateBucket() = """{"id":"$bucket","public":false,"name":"Private fixture"}"""
    fun reset() {
        awaitIdle(); objects.clear(); calls.clear(); metadata = privateBucket(); metadataStatus = 200; metadataDelay = 0
        getStatus = null; getCode = "NoSuchKey"; getBody = null; missingStatus = 404; missingBody = """{"code":"NoSuchKey"}"""
        getDelay = 0; postMode = "normal"; postDelay = 0; stallBody = false
    }
    fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (active.get() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
        check(active.get() == 0) { "HTTPS fixture still has active requests" }
    }
    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath; val method = exchange.requestMethod
        val call = Call(method, path, exchange.requestHeaders.getFirst("apikey"), exchange.requestHeaders.getFirst("Authorization"),
            exchange.requestHeaders.getFirst("x-upsert"), exchange.requestHeaders.getFirst("Content-Type"))
        calls += call
        if (call.key != secret || call.authorization != null) { reply(exchange, 401, """{"code":"InvalidJWT"}"""); return }
        if (path == "/storage/v1/bucket/$bucket") {
            val value = metadata; val status = metadataStatus; val delay = metadataDelay
            Thread.sleep(delay); reply(exchange, status, value); return
        }
        val name = path.substringAfterLast('/'); UUID.fromString(name.removeSuffix(".blob"))
        if (method == "GET" && path.startsWith("/storage/v1/object/authenticated/$bucket/")) {
            val status = getStatus; val code = getCode; val delay = getDelay; val stall = stallBody
            Thread.sleep(delay)
            if (status != null) { reply(exchange, status, getBody ?: """{"code":"$code","message":"PRIVATE diagnostic"}"""); return }
            val bytes = objects[name]
            if (bytes == null) reply(exchange, missingStatus, missingBody)
            else if (stall) {
                exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes, 0, 1); exchange.responseBody.flush(); Thread.sleep(1500)
                exchange.responseBody.write(bytes, 1, bytes.size - 1); exchange.close()
            } else reply(exchange, 200, bytes)
            return
        }
        if (method == "POST" && path.startsWith("/storage/v1/object/$bucket/")) {
            val mode = postMode; val delay = postDelay
            val bytes = exchange.requestBody.readAllBytes(); Thread.sleep(delay)
            when (mode) {
                "ack-only" -> reply(exchange, 200, """{"Key":"$bucket/$name"}""")
                "false-conflict" -> reply(exchange, 409, """{"code":"ResourceAlreadyExists"}""")
                "legacy-duplicate" -> { objects.putIfAbsent(name, bytes); reply(exchange, 400, """{"statusCode":"409","error":"Duplicate"}""") }
                "persist-then-fail" -> { objects.putIfAbsent(name, bytes); reply(exchange, 500, """{"code":"InternalError"}""") }
                "wrong-bytes" -> { objects.putIfAbsent(name, bytes.map { (it.toInt() xor 1).toByte() }.toByteArray()); reply(exchange, 200, "{}") }
                "redirect" -> { exchange.responseHeaders.set("Location", origin.toString() + "/credential-trap"); reply(exchange, 307, "{}") }
                "denied" -> reply(exchange, 403, """{"code":"AccessDenied"}""")
                else -> if (objects.putIfAbsent(name, bytes) == null) reply(exchange, 201, """{"Key":"$bucket/$name"}""")
                    else reply(exchange, 409, """{"code":"ResourceAlreadyExists"}""")
            }
            return
        }
        reply(exchange, 404, """{"code":"UnknownRoute"}""")
    }
    private fun reply(exchange: HttpExchange, status: Int, text: String) = reply(exchange, status, text.toByteArray(), "application/json")
    private fun reply(exchange: HttpExchange, status: Int, bytes: ByteArray, type: String = "application/octet-stream") {
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    override fun close() { server.stop(0); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
}
