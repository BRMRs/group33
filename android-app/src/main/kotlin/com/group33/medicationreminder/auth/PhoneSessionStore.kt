package com.group33.medicationreminder.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.group33.shared.model.UserRole
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The app JWT stays encrypted in no-backup private storage and is never returned to the UI. */
internal class PhoneSessionStore(context: Context, baseUrl: String) {
    private val environment = MessageDigest.getInstance("SHA-256")
        .digest(baseUrl.trimEnd('/').toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone-auth-session-$environment"))

    @Synchronized
    fun save(session: AppSession) {
        val body = JSONObject().put("id", session.account.id).put("role", session.account.role.name)
            .put("name", session.account.displayName).put("token", session.token).put("expiresAt", session.expiresAt)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(body)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun restore(): AppSession? {
        if (!file.baseFile.exists()) return null
        return try {
            require(file.baseFile.length() in 29..32_768)
            val bytes = file.readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            val expiresAt = json.getLong("expiresAt")
            if (expiresAt <= System.currentTimeMillis()) {
                clear()
                null
            } else {
                AppSession(
                    PhoneAccount(json.getString("id"), UserRole.valueOf(json.getString("role")), json.getString("name")),
                    json.getString("token"), expiresAt,
                )
            }
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() = file.delete()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "group33.phone-auth.session.v1" }
}
