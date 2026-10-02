package io.evren.morsel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption of login material with an Android Keystore key. The
 * ciphertext and nonce live in a private no-backup file; the key never leaves
 * the keystore. A corrupted file or an invalidated key reads as "no
 * credentials" (sign-in flow) and never deletes the unresolved operation,
 * which is kept in a separate journal file.
 */
class CredentialVault(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    private val file = File(context.noBackupFilesDir, "morsel.vault.bin")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun save(email: String, passwordDigest: String): Unit = withContext(ioDispatcher) {
        val key = obtainKey()
        val emailSealed = seal(key, email)
        val digestSealed = seal(key, passwordDigest)
        val payload = VaultPayload(
            emailCipher = emailSealed.first,
            emailNonce = emailSealed.second,
            digestCipher = digestSealed.first,
            digestNonce = digestSealed.second,
        )
        writePayload(payload)
    }

    override suspend fun saveToken(token: String?): Unit = withContext(ioDispatcher) {
        val current = readPayload() ?: return@withContext
        val key = obtainKey()
        val sealed = token?.let { seal(key, it) }
        writePayload(
            current.copy(
                tokenCipher = sealed?.first,
                tokenNonce = sealed?.second,
            ),
        )
    }

    override suspend fun read(): StoredCredentials? = withContext(ioDispatcher) {
        val payload = readPayload() ?: return@withContext null
        try {
            val key = obtainKey()
            StoredCredentials(
                email = unseal(key, payload.emailCipher, payload.emailNonce),
                passwordDigest = unseal(key, payload.digestCipher, payload.digestNonce),
                token = payload.tokenCipher?.let { cipher ->
                    unseal(key, cipher, payload.tokenNonce ?: return@withContext null)
                },
            )
        } catch (_: Exception) {
            // Invalidated key or corrupt data: force sign-in, keep the journal.
            null
        }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        runCatching { file.delete() }
    }

    private fun readPayload(): VaultPayload? = if (!file.exists()) {
        null
    } else {
        runCatching {
            json.decodeFromString(VaultPayload.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writePayload(payload: VaultPayload) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(VaultPayload.serializer(), payload))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("vault write failed")
            }
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(key: SecretKey, plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return base64(sealed) to base64(cipher.iv)
    }

    private fun unseal(key: SecretKey, cipherB64: String, nonceB64: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(nonceB64)))
        return String(cipher.doFinal(decode(cipherB64)), Charsets.UTF_8)
    }

    private fun base64(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "morsel_vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
