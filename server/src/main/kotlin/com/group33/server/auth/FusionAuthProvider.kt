package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.GetFusionAuthTokenRequest
import com.aliyun.dypnsapi20170525.models.VerifyWithFusionAuthTokenRequest
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Server-only Alibaba integration; never return provider credentials or error details to clients. */
@Component
class FusionAuthProvider internal constructor(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val schemeCode: String,
    private val packageName: String,
    private val packageSign: String,
    private val calls: FusionCallExecutor,
    private val clientFactory: (Config) -> Client,
) {
    @Autowired
    constructor(
        @Value("\${app.fusion.access-key-id:}") accessKeyId: String,
        @Value("\${app.fusion.access-key-secret:}") accessKeySecret: String,
        @Value("\${app.fusion.scheme-code:}") schemeCode: String,
        @Value("\${app.fusion.package-name:}") packageName: String,
        @Value("\${app.fusion.package-sign:}") packageSign: String,
    ) : this(accessKeyId, accessKeySecret, schemeCode, packageName, packageSign, FusionCallExecutor.shared, ::Client)

    fun getSdkToken(): String {
        val request = GetFusionAuthTokenRequest()
            .setSchemeCode(schemeCode)
            .setPackageName(packageName)
            .setPackageSign(packageSign)
            .setPlatform("Android")
            .setDurationSeconds(900L)
        return calls.execute(FusionStage.SDK_TOKEN) {
            val response = client().getFusionAuthTokenWithOptions(request, runtime()).body
            if (response.success != true || response.code != "OK" || response.model.isNullOrBlank()) {
                throw FusionProviderException()
            }
            response.model
        }
    }

    /** Returns the verified phone only when Alibaba explicitly reports PASS. */
    fun verify(token: String): String? {
        if (token.isBlank() || token.length > 8192) return null
        return calls.execute(FusionStage.VERIFY) {
            val response = client().verifyWithFusionAuthTokenWithOptions(
                VerifyWithFusionAuthTokenRequest().setVerifyToken(token), runtime()).body
            val result = response.model
            if (response.success != true || response.code != "OK" || result?.verifyResult != "PASS") null
            else result.phoneNumber?.takeIf { it.matches(Regex("\\+?[0-9]{8,15}")) }
        }
    }

    private fun client(): Client {
        if (listOf(accessKeyId, accessKeySecret, schemeCode, packageName, packageSign).any { it.isBlank() }) {
            throw FusionProviderException()
        }
        return clientFactory(
            Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
                .setEndpoint("dypnsapi.aliyuncs.com"),
        )
    }

    private fun runtime() = RuntimeOptions().setConnectTimeout(calls.policy.connectMillis)
        .setReadTimeout(calls.policy.readMillis).setAutoretry(false).setMaxAttempts(1)
}

/** Deliberately contains no upstream message or credentials. */
class FusionProviderException : RuntimeException("Fusion authentication service unavailable")
