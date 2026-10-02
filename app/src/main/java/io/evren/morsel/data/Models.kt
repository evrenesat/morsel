package io.evren.morsel.data

import kotlinx.serialization.Serializable

/** Credentials as stored, always encrypted at rest by CredentialVault. */
data class StoredCredentials(
    val email: String,
    val passwordDigest: String,
    val token: String?,
)

/** Lowercase MD5 hex of the UTF-8 password, as the API expects. The digest is
 *  password-equivalent and is itself only ever stored AES-GCM encrypted. */
object PasswordDigest {
    fun digest(password: String): String = java.security.MessageDigest
        .getInstance("MD5")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Persistence for login material. The production implementation encrypts with
 * an Android Keystore key; a corrupted store or invalidated key reads as "no
 * credentials" and never deletes the unresolved operation, which lives in the
 * separate journal.
 */
interface CredentialStore {
    suspend fun save(email: String, passwordDigest: String)

    suspend fun saveToken(token: String?)

    suspend fun read(): StoredCredentials?

    suspend fun clear()
}

@Serializable
internal data class VaultPayload(
    val emailCipher: String,
    val emailNonce: String,
    val digestCipher: String,
    val digestNonce: String,
    val tokenCipher: String? = null,
    val tokenNonce: String? = null,
)
