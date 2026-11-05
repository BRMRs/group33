package com.group33.medicationreminder.auth

import com.group33.shared.model.UserRole
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class FusionSdkGrant(val schemeCode: String, val token: String)
internal data class AppSession(val account: PhoneAccount, val token: String, val expiresAt: Long)
internal sealed interface VerifiedPhone {
    data class Existing(val session: AppSession) : VerifiedPhone
    data class New(val ticket: String) : VerifiedPhone
}

/** Transport only; no credential is included in URLs or logs. */
internal class FusionServerClient(private val baseUrl: String, allowHttp: Boolean) {
    private val base = URL(baseUrl.trimEnd('/') + "/")

    init {
        require(
            base.protocol == "https" ||
                (allowHttp && base.protocol == "http" &&
                    base.host in setOf("10.0.2.2", "127.0.0.1", "localhost")),
        )
        require(base.userInfo == null && base.query == null && base.ref == null)
    }

    fun grant(): FusionSdkGrant = post("api/v1/auth/fusion/token", JSONObject()).let {
        FusionSdkGrant(it.getString("schemeCode"), it.getString("fusionAuthToken"))
    }

    fun verify(token: String, expectedPhone: String? = null): VerifiedPhone =
        parse(post("api/v1/auth/fusion/verify", JSONObject().put("verifyToken", token)
            .put("expectedPhone", expectedPhone ?: JSONObject.NULL), readTimeoutMillis = 45_000))

    fun register(ticket: String, role: UserRole, displayName: String?): VerifiedPhone = parse(
        post(
            "api/v1/auth/phone/register",
            JSONObject().put("registrationTicket", ticket).put("role", role.name)
                .put("displayName", displayName ?: JSONObject.NULL),
        ),
    )

    private fun parse(body: JSONObject): VerifiedPhone = when (body.getString("status")) {
        "REGISTRATION_REQUIRED" -> VerifiedPhone.New(body.getString("registrationTicket").also { require(it.isNotBlank()) })
        "SIGNED_IN" -> {
            val user = body.getJSONObject("user")
            val lifetime = body.getLong("expiresInSeconds")
            require(lifetime in 1..604800)
            val token = body.getString("accessToken").also { require(it.isNotBlank()) }
            VerifiedPhone.Existing(
                AppSession(
                    PhoneAccount(user.getString("id"), UserRole.valueOf(user.getString("role")), user.getString("displayName")),
                    token,
                    System.currentTimeMillis() + lifetime * 1000,
                ),
            )
        }
        else -> error("Unexpected phone authentication response")
    }

    private fun post(path: String, payload: JSONObject, readTimeoutMillis: Int = 20_000): JSONObject {
        val connection = URL(base, path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val code = runCatching {
                    val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(8192) }
                    body?.let { JSONObject(it).optString("code") }
                }.getOrNull()
                throw FusionHttpException(status, code)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(32_769)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= 32_768)
                String(buffer, 0, count)
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

internal class FusionHttpException(val status: Int, val code: String?) : Exception("Phone authentication request failed")
