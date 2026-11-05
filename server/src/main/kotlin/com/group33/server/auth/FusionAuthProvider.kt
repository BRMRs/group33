package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.GetFusionAuthTokenRequest
import com.aliyun.dypnsapi20170525.models.VerifyWithFusionAuthTokenRequest
import com.aliyun.teaopenapi.models.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Server-only Alibaba integration; never return provider credentials or error details to clients. */
@Component
class FusionAuthProvider(
    @Value("\${app.fusion.access-key-id:}") private val accessKeyId: String,
    @Value("\${app.fusion.access-key-secret:}") private val accessKeySecret: String,
    @Value("\${app.fusion.scheme-code:}") private val schemeCode: String,
    @Value("\${app.fusion.package-name:}") private val packageName: String,
    @Value("\${app.fusion.package-sign:}") private val packageSign: String,
) {
    fun getSdkToken(): String {
        val request = GetFusionAuthTokenRequest()
            .setSchemeCode(schemeCode)
            .setPackageName(packageName)
            .setPackageSign(packageSign)
            .setPlatform("Android")
            .setDurationSeconds(900L)
        val response = callProvider { client().getFusionAuthToken(request).body }
        if (response.success != true || response.code != "OK" || response.model.isNullOrBlank()) {
            throw FusionProviderException()
        }
        return response.model
    }

    /** Returns the verified phone only when Alibaba explicitly reports PASS. */
    fun verify(token: String): String? {
        if (token.isBlank() || token.length > 8192) return null
        val response = callProvider {
            client().verifyWithFusionAuthToken(VerifyWithFusionAuthTokenRequest().setVerifyToken(token)).body
        }
        val result = response.model
        if (response.success != true || response.code != "OK" || result?.verifyResult != "PASS") return null
        return result.phoneNumber?.takeIf { it.matches(Regex("\\+?[0-9]{8,15}")) }
    }

    private fun client(): Client {
        if (listOf(accessKeyId, accessKeySecret, schemeCode, packageName, packageSign).any { it.isBlank() }) {
            throw FusionProviderException()
        }
        return Client(
            Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
                .setEndpoint("dypnsapi.aliyuncs.com"),
        )
    }

    private inline fun <T> callProvider(call: () -> T): T = try {
        call()
    } catch (_: Exception) {
        throw FusionProviderException()
    }
}

/** Deliberately contains no upstream message or credentials. */
class FusionProviderException : RuntimeException("Fusion authentication service unavailable")
