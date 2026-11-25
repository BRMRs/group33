package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

internal class FusionSdkGrant(val schemeCode: String, val token: String,
    val authenticationAttemptToken: String, val expiresInSeconds: Long)
internal class AppSession(val account: PhoneAccount, val token: String, val expiresInSeconds: Long)
internal sealed interface VerifiedPhone {
    data class Existing(val session: AppSession) : VerifiedPhone
    class New(val ticket: String, val expiresInSeconds: Long = 300,
              val localDeadline: Long? = null) : VerifiedPhone
}

/** Transport only; no credential is included in URLs or logs. */
internal interface PhoneServerClient {
    fun grant(installationId: String, authenticationAttemptToken: String? = null): FusionSdkGrant
    fun verify(installationId: String, authenticationAttemptToken: String, token: String, expectedPhone: String? = null): VerifiedPhone
    fun register(installationId: String, ticket: String, role: UserRole, displayName: String?): VerifiedPhone
}

internal class FusionServerClient(private val baseUrl: String, allowHttp: Boolean) : PhoneServerClient {
    private val base = URL(baseUrl.trimEnd('/') + "/")

    init {
        require(
            base.protocol == "https" ||
                (allowHttp && base.protocol == "http" &&
                    base.host in setOf("10.0.2.2", "127.0.0.1", "localhost")),
        )
        require(base.userInfo == null && base.query == null && base.ref == null)
    }

    override fun grant(installationId: String, authenticationAttemptToken: String?): FusionSdkGrant =
        post("api/v1/auth/fusion/token", buildJsonObject {
            put("installationId", installationId)
            authenticationAttemptToken?.let { put("authenticationAttemptToken", it) }
        }).let {
            FusionSdkGrant(it.text("schemeCode"), it.text("fusionAuthToken"),
                it.text("authenticationAttemptToken"), it.number("expiresInSeconds"))
        }

    override fun verify(installationId: String, authenticationAttemptToken: String, token: String,
                        expectedPhone: String?): VerifiedPhone = parse(post("api/v1/auth/fusion/verify", buildJsonObject {
        put("installationId", installationId)
        put("authenticationAttemptToken", authenticationAttemptToken)
        put("verifyToken", token)
        expectedPhone?.let { put("expectedPhone", it) }
    }, readTimeoutMillis = 45_000), installationId)

    override fun register(installationId: String, ticket: String, role: UserRole, displayName: String?): VerifiedPhone =
        parse(post("api/v1/auth/phone/register", buildJsonObject {
            put("installationId", installationId)
            put("registrationTicket", ticket)
            put("role", role.name)
            displayName?.let { put("displayName", it) }
        }), installationId)

    private fun parse(body: JsonObject, installationId: String): VerifiedPhone {
        require(body.text("installationId") == installationId)
        return when (body.text("status")) {
            "REGISTRATION_REQUIRED" -> VerifiedPhone.New(body.text("registrationTicket").also {
                require(CAPABILITY.matches(it))
            }, body.number("registrationTicketExpiresInSeconds").also { require(it in 1..300) })
            "SIGNED_IN" -> {
                val user = body.getValue("user").jsonObject
                val lifetime = body.number("expiresInSeconds").also { require(it in 1..604800) }
                val token = body.text("accessToken").also { require(it.isNotBlank()) }
                val id = user.text("id").also { require(it.isNotBlank()) }
                val name = user.text("displayName").also { require(it.isNotBlank()) }
                VerifiedPhone.Existing(AppSession(PhoneAccount(id, UserRole.valueOf(user.text("role")), name), token, lifetime))
            }
            else -> error("Unexpected phone authentication response")
        }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString); it.content
    }
    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.let {
        require(!it.isString); requireNotNull(it.longOrNull)
    }
    private companion object { val CAPABILITY = Regex("[A-Za-z0-9_-]{43}") }

    private fun post(path: String, payload: JsonObject, readTimeoutMillis: Int = 20_000): JsonObject {
        val connection = URL(base, path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            // Streaming bodies cannot be replayed by HttpURLConnection after a lost response.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val code = runCatching {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { readBounded(it, 8192) }
                    body?.let { Json.parseToJsonElement(it).jsonObject["code"]?.jsonPrimitive?.contentOrNull }
                }.getOrNull()
                throw FusionHttpException(status, code)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                readBounded(reader, 32_768)
            }
            return Json.parseToJsonElement(body).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(reader: java.io.Reader, limit: Int): String {
        val buffer = CharArray(limit + 1)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        require(count <= limit)
        return String(buffer, 0, count)
    }
}

internal class FusionHttpException(val status: Int, val code: String?) : Exception("Phone authentication request failed")
