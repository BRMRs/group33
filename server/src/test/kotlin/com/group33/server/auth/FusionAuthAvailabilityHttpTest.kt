package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.*
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["app.fusion.enabled=true", "app.fusion.scheme-code=fictional",
    "app.jwt.secret=FICTIONAL_AUTH_AVAILABILITY_SIGNING_KEY_2026"])
@AutoConfigureMockMvc
class FusionAuthAvailabilityHttpTest(@Autowired private val mvc: MockMvc, @Autowired private val json: ObjectMapper) {
    @MockitoBean lateinit var provider: FusionAuthProvider
    @MockitoBean lateinit var accounts: PhoneAccountStore
    @MockitoBean lateinit var jwt: JwtService

    private fun response(path: String, body: String) {
        val result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("PHONE_AUTH_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("WWW-Authenticate")).andReturn().response
        assertEquals(json.readTree(result.contentAsString)["requestId"].asText(), result.getHeader("X-Request-ID"))
        for (secret in listOf("PRIVATE", "FICTIONAL_PROOF", "127.0.0.1", "aliyun", "jdbc:"))
            assertFalse(result.contentAsString.contains(secret))
        verifyNoInteractions(accounts, jwt)
    }

    @Test fun `actual bounded provider timeout is retryable safe HTTP without later identity work`() {
        val pool = FusionCallExecutor.pool(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        try {
            val client = object : Client(Config().setAccessKeyId("fictional").setAccessKeySecret("fictional")) {
                override fun getFusionAuthTokenWithOptions(request: GetFusionAuthTokenRequest, runtime: RuntimeOptions): GetFusionAuthTokenResponse {
                    try {
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                        return GetFusionAuthTokenResponse().setBody(GetFusionAuthTokenResponseBody().setSuccess(true).setCode("OK").setModel("PRIVATE_LATE_TOKEN"))
                    } finally { finished.countDown() }
                }
            }
            val actual = FusionAuthProvider("fictional", "fictional", "scheme", "package", "sign",
                FusionCallExecutor(pool,FusionCallPolicy(responseMillis=60))) { client }
            `when`(provider.getSdkToken()).thenAnswer { actual.getSdkToken() }
            response("/api/v1/auth/fusion/token", "")
            release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));verifyNoInteractions(accounts,jwt)
        } finally { release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
    }

    @Test fun `actual saturated provider returns retryable safe HTTP without SDK dispatch`() {
        val pool = FusionCallExecutor.pool(1); val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val caller=Executors.newSingleThreadExecutor();val dispatches=AtomicInteger()
        try {
            val calls=FusionCallExecutor(pool,FusionCallPolicy(responseMillis=2_000))
            val occupying=caller.submit<String> { calls.execute(FusionStage.VERIFY) { entered.countDown();release.await();"fictional" } }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val actual=FusionAuthProvider("fictional","fictional","scheme","package","sign",calls) {
                dispatches.incrementAndGet();error("No SDK dispatch when saturated")
            }
            `when`(provider.verify("FICTIONAL_PROOF")).thenAnswer { actual.verify("FICTIONAL_PROOF") }
            response("/api/v1/auth/fusion/verify", """{"verifyToken":"FICTIONAL_PROOF"}""")
            assertEquals(0,dispatches.get());assertEquals(0,pool.queue.size)
            release.countDown();assertEquals("fictional",occupying.get(2,TimeUnit.SECONDS))
        } finally { release.countDown();caller.shutdownNow();pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS)) }
    }
}
