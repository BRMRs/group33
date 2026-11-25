package com.group33.server.auth

import com.aliyun.dypnsapi20170525.Client
import com.aliyun.dypnsapi20170525.models.GetFusionAuthTokenRequest
import com.aliyun.dypnsapi20170525.models.VerifyWithFusionAuthTokenRequest
import com.aliyun.teaopenapi.models.Config
import com.aliyun.teautil.models.RuntimeOptions
import java.time.Clock

/** Programmatic supplier seam only. Deployment composition must provide explicit private configuration. */
internal class AlibabaBoundPhoneProvider(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val schemeCode: String,
    private val packageName: String,
    private val packageSign: String,
    private val clock: Clock,
    private val calls: FusionCallExecutor = FusionCallExecutor.shared,
    private val clientFactory: (Config) -> Client = ::Client,
) : BoundPhoneProvider {
    init {
        require(listOf(accessKeyId, accessKeySecret, schemeCode, packageName, packageSign).none { it.isBlank() }) {
            "Invalid private phone provider configuration"
        }
    }

    override fun toString() = "AlibabaBoundPhoneProvider(redacted)"

    override fun grant(): BoundPhoneGrant {
        // The API requests a900s grant. Starting before the bounded call conservatively charges its latency.
        val expiresAt = Math.addExact(clock.millis(), 900_000L)
        val request = GetFusionAuthTokenRequest().setSchemeCode(schemeCode)
            .setPackageName(packageName).setPackageSign(packageSign).setPlatform("Android").setDurationSeconds(900L)
        return calls.execute(FusionStage.SDK_TOKEN) {
            val response = client().getFusionAuthTokenWithOptions(request, runtime()).body
            if (response?.success != true || response.code != "OK" || response.model.isNullOrBlank()) {
                throw FusionProviderException()
            }
            BoundPhoneGrant(response.model, expiresAt)
        }
    }

    override fun verify(proof: String): String? {
        if (proof.isBlank() || proof.length > 8192) return null
        return calls.execute(FusionStage.VERIFY) {
            val response = client().verifyWithFusionAuthTokenWithOptions(
                VerifyWithFusionAuthTokenRequest().setVerifyToken(proof), runtime()).body
            if (response?.success != true || response.code != "OK") throw FusionProviderException()
            val result = response.model ?: throw FusionProviderException()
            when (result.verifyResult) {
                "UNKNOWN" -> null // The documented explicit authentication failure, not an API outage.
                "PASS" -> result.phoneNumber?.takeIf { it.matches(Regex("\\+?[0-9]{8,15}")) }
                    ?: throw FusionProviderException()
                else -> throw FusionProviderException()
            }
        }
    }

    private fun client() = clientFactory(Config().setAccessKeyId(accessKeyId).setAccessKeySecret(accessKeySecret)
        .setEndpoint("dypnsapi.aliyuncs.com").setProtocol("https"))

    private fun runtime() = RuntimeOptions().setConnectTimeout(calls.policy.connectMillis)
        .setReadTimeout(calls.policy.readMillis).setAutoretry(false).setMaxAttempts(1)
}

/** No private upstream cause, credentials, proof or phone in diagnostics. */
internal class FusionProviderException : RuntimeException("Fusion authentication service unavailable")
