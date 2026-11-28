package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.*
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class AlibabaBoundPhoneProviderTest {
    private val phone = "+12025550123" // Fictional NANP 555-01xx range; never sent to a real provider.
    private fun provider(calls: FusionCallExecutor, client: (Config) -> Client) = AlibabaBoundPhoneProvider(
        "FICTIONAL_ACCESS_KEY", "FICTIONAL_SECRET", "FICTIONAL_SCHEME", "fictional.package", "FICTIONAL_SIGN", java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1_800_000_000_000), java.time.ZoneOffset.UTC), calls, client)
    private fun configured() = Config().setAccessKeyId("FICTIONAL_ACCESS_KEY").setAccessKeySecret("FICTIONAL_SECRET")
    private fun pass() = VerifyWithFusionAuthTokenResponse().setBody(VerifyWithFusionAuthTokenResponseBody()
        .setSuccess(true).setCode("OK").setModel(VerifyWithFusionAuthTokenResponseBody.VerifyWithFusionAuthTokenResponseBodyModel()
            .setVerifyResult("PASS").setPhoneNumber(phone)))

    @Test fun `both SDK calls carry explicit socket bounds and no retries`() {
        val pool = FusionCallExecutor.pool(2); val options = mutableListOf<RuntimeOptions>(); val count = AtomicInteger()
        try {
            val client = object : Client(configured()) {
                override fun getFusionAuthTokenWithOptions(request: GetFusionAuthTokenRequest, runtime: RuntimeOptions): GetFusionAuthTokenResponse {
                    options += runtime; count.incrementAndGet()
                    assertEquals(900L, request.durationSeconds); assertEquals("Android", request.platform)
                    return GetFusionAuthTokenResponse().setBody(GetFusionAuthTokenResponseBody().setSuccess(true).setCode("OK").setModel("FICTIONAL_SDK_TOKEN"))
                }
                override fun verifyWithFusionAuthTokenWithOptions(request: VerifyWithFusionAuthTokenRequest, runtime: RuntimeOptions): VerifyWithFusionAuthTokenResponse {
                    options += runtime; count.incrementAndGet(); assertEquals("FICTIONAL_PROOF", request.verifyToken)
                    return pass()
                }
            }
            val p = provider(FusionCallExecutor(pool)) { config ->
                assertEquals("dypnsapi.aliyuncs.com", config.endpoint); assertEquals("https", config.protocol); client
            }
            assertEquals("FICTIONAL_SDK_TOKEN", p.grant().value); assertEquals(phone, p.verify("FICTIONAL_PROOF"))
            assertEquals(2, count.get())
            options.forEach { assertEquals(5_000, it.connectTimeout); assertEquals(10_000, it.readTimeout)
                assertEquals(false, it.autoretry); assertEquals(1, it.maxAttempts); assertNotEquals(true, it.ignoreSSL) }
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `blank and oversized proof never call SDK factory`() {
        val pool = FusionCallExecutor.pool(1); val count = AtomicInteger()
        try {
            val p = provider(FusionCallExecutor(pool)) { count.incrementAndGet(); error("Must not construct") }
            assertNull(p.verify(" ")); assertNull(p.verify("x".repeat(8193))); assertEquals(0, count.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `non PASS supplier response remains an authentication denial`() {
        val pool = FusionCallExecutor.pool(1); val records = mutableListOf<FusionDiagnostic>()
        try {
            val client = object : Client(configured()) {
                override fun verifyWithFusionAuthTokenWithOptions(request: VerifyWithFusionAuthTokenRequest, runtime: RuntimeOptions): VerifyWithFusionAuthTokenResponse {
                    return pass().also { it.body.model.verifyResult = "UNKNOWN" }
                }
            }
            assertNull(provider(FusionCallExecutor(pool, record = records::add)) { client }.verify("proof"))
            assertEquals(FusionOutcome.DENIED, records.single().outcome)
        } finally { pool.shutdownNow() }
    }

    @Test fun `pinned SDK socket timeout makes one loopback attempt`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPool = Executors.newCachedThreadPool();server.executor = serverPool
        val count = AtomicInteger(); val release = CountDownLatch(1);val records = mutableListOf<FusionDiagnostic>()
        server.createContext("/") { exchange ->
            count.incrementAndGet(); exchange.requestBody.use { it.readBytes() }
            try { release.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            exchange.close()
        }
        val pool = FusionCallExecutor.pool(1)
        try {
            server.start()
            val calls = FusionCallExecutor(pool, FusionCallPolicy(connectMillis = 500, readMillis = 80, responseMillis = 1_500), records::add)
            val p = provider(calls) { Client(configured().setEndpoint("127.0.0.1:${server.address.port}").setProtocol("http")) }
            assertThrows(FusionProviderException::class.java) { p.verify("FICTIONAL_PROOF") }
            assertEquals(1, count.get()); assertEquals(FusionOutcome.FAILED, records.single().outcome)
            assertTrue(records.single().elapsedMillis < 1_400)
        } finally { release.countDown();server.stop(0);serverPool.shutdownNow();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
    }

    @Test fun `slow streaming supplier cannot extend caller deadline by keeping socket readable`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val serverPool = Executors.newCachedThreadPool(); server.executor = serverPool
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
        val streaming = AtomicBoolean(false)
        val records = mutableListOf<FusionDiagnostic>(); val socketFailure = AtomicReference<Throwable?>()
        server.createContext("/") { exchange ->
            count.incrementAndGet(); exchange.requestBody.use { it.readBytes() }
            try {
                exchange.responseHeaders.add("Content-Type", "application/json")
                if (!streaming.get()) {
                    val body = """{"Success":true,"Code":"OK","Model":"FICTIONAL_SDK_TOKEN"}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                    return@createContext
                }
                exchange.sendResponseHeaders(200, 0);entered.countDown()
                while (release.count > 0) { exchange.responseBody.write(' '.code); exchange.responseBody.flush(); Thread.sleep(15) }
            } catch (failure: Exception) { socketFailure.set(failure) } finally { exchange.close() }
        }
        val pool = FusionCallExecutor.pool(1)
        try {
            server.start()
            val client = Client(configured().setEndpoint("127.0.0.1:${server.address.port}").setProtocol("http"))
            // Warm the pinned SDK/HTTP classes outside the deliberately short fault deadline.
            assertEquals("FICTIONAL_SDK_TOKEN", client.getFusionAuthTokenWithOptions(
                GetFusionAuthTokenRequest().setSchemeCode("FICTIONAL_SCHEME").setPlatform("Android"),
                RuntimeOptions().setConnectTimeout(2_000).setReadTimeout(2_000).setAutoretry(false)).body.model)
            streaming.set(true); count.set(0)
            val calls = FusionCallExecutor(pool, FusionCallPolicy(connectMillis = 500,readMillis = 200,responseMillis = 500), records::add)
            val p = provider(calls) { client }
            assertThrows(FusionProviderException::class.java) { p.grant().value }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(1, count.get())
            assertEquals(FusionOutcome.TIMEOUT, records.single().outcome)
            assertTrue(records.single().elapsedMillis in 400..2_000)
        } finally { release.countDown();server.stop(0);serverPool.shutdownNow();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
    }

    @ParameterizedTest @ValueSource(strings = ["api-failure", "missing-model", "unknown-result", "missing-phone", "bad-phone"])
    fun `unavailable or malformed verification is not a definite denial`(fault: String) {
        val pool = FusionCallExecutor.pool(1); val records = mutableListOf<FusionDiagnostic>()
        try {
            val client = object : Client(configured()) {
                override fun verifyWithFusionAuthTokenWithOptions(request: VerifyWithFusionAuthTokenRequest, runtime: RuntimeOptions): VerifyWithFusionAuthTokenResponse {
                    return pass().also { response -> when (fault) {
                        "api-failure" -> response.body.setSuccess(false).setCode("PRIVATE_UPSTREAM_FAILURE")
                        "missing-model" -> response.body.model = null
                        "unknown-result" -> response.body.model.verifyResult = "FUTURE_RESULT"
                        "missing-phone" -> response.body.model.phoneNumber = null
                        "bad-phone" -> response.body.model.phoneNumber = "PRIVATE_INVALID_PHONE"
                    } }
                }
            }
            val p = provider(FusionCallExecutor(pool, record = records::add)) { client }
            val failure = assertThrows(FusionProviderException::class.java) { p.verify("FICTIONAL_PROOF") }
            assertEquals("Fusion authentication service unavailable", failure.message); assertNull(failure.cause)
            assertEquals(FusionOutcome.FAILED, records.single().outcome)
            assertEquals("AlibabaBoundPhoneProvider(redacted)", p.toString())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

    @Test fun `grant expiry charges supplier latency instead of starting on response`() {
        val pool = FusionCallExecutor.pool(1); val now = java.util.concurrent.atomic.AtomicLong(1_800_000_000_000)
        val clock = object : java.time.Clock() {
            override fun instant() = java.time.Instant.ofEpochMilli(now.get())
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = java.time.Clock.fixed(instant(), zone)
        }
        try {
            val client = object : Client(configured()) {
                override fun getFusionAuthTokenWithOptions(request: GetFusionAuthTokenRequest, runtime: RuntimeOptions): GetFusionAuthTokenResponse {
                    now.addAndGet(2_000)
                    return GetFusionAuthTokenResponse().setBody(GetFusionAuthTokenResponseBody().setSuccess(true).setCode("OK").setModel("FICTIONAL_SDK_TOKEN"))
                }
            }
            val p = AlibabaBoundPhoneProvider("FICTIONAL_ACCESS_KEY", "FICTIONAL_SECRET", "FICTIONAL_SCHEME", "fictional.package", "FICTIONAL_SIGN",
                clock, FusionCallExecutor(pool)) { client }
            val grant = p.grant()
            assertEquals(898_000, grant.expiresAtMillis - now.get()); assertEquals("BoundPhoneGrant(redacted)", grant.toString())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS)) }
    }

}
