package com.group33.medicationreminder.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.group33.medicationreminder.core.data.contract.AuthReadiness
import com.group33.medicationreminder.core.data.contract.SessionView
import com.group33.shared.contract.ContractProtocol
import com.group33.shared.contract.UserView
import com.group33.shared.model.User
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** Deliberately not a data class: diagnostics must never print stored credentials. */
internal class SessionDiskRecord(
    val installationId: String,
    val generation: String,
    val user: UserView? = null,
    val token: String? = null,
    val issuedAtMillis: Long = 0,
    val expiresAtMillis: Long = 0,
) {
    fun cleared() = SessionDiskRecord(installationId, UUID.randomUUID().toString())
}

/** Call read/write only inside locked. No unencrypted fallback or corruption reset. */
internal class EncryptedSessionFile(context: Context, private val environmentId: String, namespace: String) {
    init {
        require(environmentId.isNotBlank())
        require(namespace.matches(Regex("[A-Za-z0-9_-]+")))
    }
    private val directory = File(context.noBackupFilesDir, "sessions")
    private val digest = MessageDigest.getInstance("SHA-256").digest("$namespace\u0000$environmentId".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val base = File(directory, "$digest.bin")
    private val atomic = AtomicFile(base)
    private val keyAlias = "group33.session.$digest"
    private val runtime = runtimes.getOrPut(base.absolutePath) { Runtime() }
    val state get() = runtime.state
    val epoch get() = runtime.epoch
    val clearRequested get() = runtime.clearRequested

    fun requestClear() = synchronized(runtime.monitor) {
        runtime.clearRequested = true
        storageFailure()
    }

    fun acknowledgeClear() { runtime.clearRequested = false }

    fun storageFailure() = synchronized(runtime.monitor) {
        runtime.epoch = UUID.randomUUID().toString()
        state.value = SessionView(AuthReadiness.SIGNED_OUT, null)
    }

    fun <T> locked(block: () -> T): T = synchronized(runtime.monitor) {
        check(directory.isDirectory || directory.mkdirs())
        RandomAccessFile(File(directory, "$digest.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { block() }
        }
    }

    fun readOrCreate(): SessionDiskRecord {
        // openRead recovers an interrupted AtomicFile write before interpreting absence.
        val bytes = try {
            atomic.openRead().use { input ->
                val bytes = input.readBytesBounded()
                check(bytes.isNotEmpty())
                bytes
            }
        } catch (missing: java.io.FileNotFoundException) {
            check(!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists())
            return SessionDiskRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString()).also(::write)
        }
        check(bytes.size >= 2 + 12 + 16 && bytes[0].toInt() == 1 && bytes[1].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
        cipher.updateAAD(environmentId.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(bytes, 14, bytes.size - 14).toString(Charsets.UTF_8)
        val json = ContractProtocol.json.parseToJsonElement(plain).jsonObject
        check(json.keys == setOf("version", "installationId", "generation", "user", "token", "issuedAt", "expiresAt"))
        check(json.getValue("version").jsonPrimitive.int == 1)
        val installation = json.getValue("installationId").jsonPrimitive.content
        val generation = json.getValue("generation").jsonPrimitive.content
        check(UUID.fromString(installation).toString() == installation && UUID.fromString(generation).toString() == generation)
        val user = json.getValue("user").takeUnless { it == JsonNull }?.let {
            ContractProtocol.json.decodeFromJsonElement(User.serializer(), it)
        }
        val token = json.getValue("token").jsonPrimitive.contentOrNull
        val issued = json.getValue("issuedAt").jsonPrimitive.long
        val expires = json.getValue("expiresAt").jsonPrimitive.long
        check(if (user == null) token == null && issued == 0L && expires == 0L
            else user.id.isNotBlank() && user.displayName.isNotBlank() && !token.isNullOrBlank() && issued >= 0 && expires > issued)
        return SessionDiskRecord(installation, generation, user, token, issued, expires)
    }

    fun write(record: SessionDiskRecord) {
        val json = buildJsonObject {
            put("version", 1); put("installationId", record.installationId); put("generation", record.generation)
            put("user", record.user?.let { ContractProtocol.json.encodeToJsonElement(User.serializer(), it) } ?: JsonNull)
            put("token", record.token?.let(::JsonPrimitive) ?: JsonNull)
            put("issuedAt", record.issuedAtMillis); put("expiresAt", record.expiresAtMillis)
        }.toString().toByteArray(Charsets.UTF_8)
        check(json.size <= 65_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        cipher.updateAAD(environmentId.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(json)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            check(atomic.openRead().use { it.readBytesBounded() }.contentEquals(bytes))
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(keyAlias)) return checkNotNull(store.getKey(keyAlias, null) as? SecretKey)
        check(create) { "Session key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val n = read(buffer)
            if (n == -1) return result.toByteArray()
            check(result.size() + n <= 65_536)
            result.write(buffer, 0, n)
        }
    }

    companion object {
        private class Runtime {
            val monitor = Any()
            val state = MutableStateFlow(SessionView(AuthReadiness.RESTORING, null))
            var epoch = UUID.randomUUID().toString()
            var clearRequested = false
        }
        private val runtimes = ConcurrentHashMap<String, Runtime>()
    }
}
