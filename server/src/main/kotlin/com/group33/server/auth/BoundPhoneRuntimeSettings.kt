package com.group33.server.auth

import com.group33.server.config.PrivateRuntimeFiles
import java.net.URI
import java.security.MessageDigest
import org.springframework.core.env.Environment

internal class BoundPhoneMode private constructor(val enabled: Boolean) {
    companion object {
        fun read(env: Environment): BoundPhoneMode {
            try {
                val flag = env.getProperty("app.bound-phone.enabled", "false")
                require(flag in setOf("true", "false"))
                if (flag == "true") {
                    require(env.getProperty("app.fusion.enabled", "false") == "false")
                    require(env.getProperty("server.forward-headers-strategy", "none") == "none")
                }
                return BoundPhoneMode(flag == "true")
            } catch (_: Exception) {
                throw IllegalStateException("Phone mode requires exclusive routes and unmodified transport metadata")
            }
        }
    }
}

/** A single startup snapshot. Never bind secrets through a value-printing data class/binding error. */
internal class BoundPhoneRuntimeSettings private constructor(
    val jwtKey: ByteArray,
    val phoneHashKey: ByteArray,
    val receiptKey: ByteArray,
    val issuer: String,
    val audience: String,
    val sessionSeconds: Long,
    val allowanceSeconds: Long,
    val accessKeyId: String,
    val accessKeySecret: String,
    val scheme: String,
    val packageName: String,
    val packageSignature: String,
    val rateLimit: TrustedProxyPhoneRateLimit,
) {
    override fun toString() = "BoundPhoneRuntimeSettings(redacted)"

    companion object {
        fun read(env: Environment): BoundPhoneRuntimeSettings {
            try {
                require(BoundPhoneMode.read(env).enabled)
                require(env.getProperty("app.persistence.mode") in setOf("postgres-local", "postgres-supabase"))
                require(env.getProperty("app.persistence.require-versioned-schema") == "true")
                fun required(name: String): String {
                    val value = checkNotNull(env.getProperty(name))
                    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl))
                    return value
                }
                val legacy = required("app.jwt.secret").toByteArray(Charsets.UTF_8)
                require(legacy.size >= 32 && String(legacy, Charsets.UTF_8) != "GROUP33_LOCAL_DEVELOPMENT_SECRET_CHANGE_BEFORE_DEPLOYMENT_2026")
                val jwt = PrivateRuntimeFiles.key(required("app.bound-phone.jwt-key-file"))
                val phone = PrivateRuntimeFiles.key(required("app.bound-phone.phone-hash-key-file"))
                val receipt = PrivateRuntimeFiles.key(required("app.persistence.receipt-key-file"))
                val keys = listOf(jwt, phone, receipt, legacy)
                for (i in keys.indices) for (j in 0 until i) require(!MessageDigest.isEqual(keys[i], keys[j]))
                val issuer = required("app.bound-phone.jwt-issuer")
                val uri = URI(issuer)
                require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443))
                require(issuer != env.getProperty("app.jwt.issuer", "group33-server"))
                val audience = required("app.bound-phone.jwt-audience")
                require(audience.matches(Regex("[A-Za-z0-9][A-Za-z0-9:_-]{0,127}")))
                val session = env.getProperty("app.bound-phone.session-seconds", "86400").toLong()
                val allowance = env.getProperty("app.bound-phone.clock-allowance-seconds", "60").toLong()
                require(session in 1..86400 && allowance in 0..60)
                val id = required("app.bound-phone.access-key-id")
                val secret = required("app.bound-phone.access-key-secret")
                val scheme = required("app.bound-phone.scheme-code")
                require(id.length <= 128 && secret.length <= 256 && scheme.length <= 512)
                val packageName = required("app.bound-phone.android-package")
                require(packageName == "com.group33.medicationreminder")
                val signature = required("app.bound-phone.android-signature")
                require(signature.matches(Regex("[0-9a-fA-F]{32}")))
                val proxies = required("app.bound-phone.trusted-proxy-cidrs").split(',').map(String::trim)
                val quota = env.getProperty("app.bound-phone.requests-per-minute", "30").toInt()
                val clients = env.getProperty("app.bound-phone.max-tracked-clients", "10000").toInt()
                return BoundPhoneRuntimeSettings(jwt, phone, receipt, issuer, audience, session, allowance,
                    id, secret, scheme, packageName, signature.lowercase(), TrustedProxyPhoneRateLimit(proxies, quota, clients))
            } catch (_: Exception) {
                throw IllegalStateException("Bound phone private runtime configuration is invalid")
            }
        }
    }
}
