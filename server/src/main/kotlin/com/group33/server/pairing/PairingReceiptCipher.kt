package com.group33.server.pairing

import com.group33.shared.contract.PairingCodeView
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stable external key required; no generated fallback, logging, or public wire representation. */
internal class PairingReceiptCipher(keyBytes: ByteArray) {
    init { require(keyBytes.size == 32) { "A 32-byte external receipt key is required" } }
    private val key = SecretKeySpec(keyBytes.copyOf(), "AES")
    private val random = SecureRandom()

    fun seal(key: CodeIssueKey, binding: CodeIssueBinding, view: PairingCodeView): ByteArray {
        require(view.code.matches(Regex("[0-9]{8}")))
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
        cipher.updateAAD(context(key, binding, view.id, view.expiresAtEpochMillis))
        return byteArrayOf(1) + nonce + cipher.doFinal(view.code.toByteArray(Charsets.US_ASCII))
    }

    fun open(key: CodeIssueKey, binding: CodeIssueBinding, id: String, expiry: Long, payload: ByteArray): PairingCodeView {
        check(payload.size == 37 && payload[0] == 1.toByte()) { "Cannot restore pairing receipt" }
        val raw = try {
            val cipher = cipher(Cipher.DECRYPT_MODE, payload.copyOfRange(1, 13))
            cipher.updateAAD(context(key, binding, id, expiry))
            cipher.doFinal(payload.copyOfRange(13, payload.size)).toString(Charsets.US_ASCII)
        } catch (_: GeneralSecurityException) {
            throw IllegalStateException("Cannot restore pairing receipt")
        }
        check(raw.matches(Regex("[0-9]{8}"))) { "Cannot restore pairing receipt" }
        return PairingCodeView(id, raw, expiry)
    }

    private fun cipher(mode: Int, nonce: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(128, nonce))
    }

    private fun context(key: CodeIssueKey, binding: CodeIssueBinding, id: String, expiry: Long): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            listOf("pairing-receipt-v1", key.actorId, key.operationId.toString(), binding.installationId,
                binding.targetElderId, binding.fingerprint, id).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeLong(expiry)
        }
        return buffer.toByteArray()
    }

    override fun toString() = "PairingReceiptCipher(REDACTED)"
}
