package io.evren.morsel.data

import io.evren.morsel.domain.FeederException

/**
 * Token lifecycle. The HTTP client reads the token via [currentToken]; the
 * cache is warmed from the credential store at startup and updated by sign-in
 * and read refreshes. The write path never refreshes — a stale-token write
 * fails and stays UNKNOWN, and the user is asked to check status / re-sign-in.
 */
class AuthManager(
    private val store: CredentialStore,
    private val loginCall: suspend (country: String, email: String, passwordDigest: String) -> String,
) {
    @Volatile
    private var cachedToken: String? = null

    fun currentToken(): String? = cachedToken

    /** Loads the stored token into the cache if present. Call once at startup. */
    suspend fun warmCache() {
        if (cachedToken == null) {
            cachedToken = store.read()?.token
        }
    }

    suspend fun hasSession(): Boolean {
        warmCache()
        return cachedToken != null
    }

    /** Re-login with stored credentials. Throws login failures from the client;
     *  [CredentialsMissing] when signed out. Read path only. */
    suspend fun refresh(country: String = "US"): String {
        val credentials = store.read() ?: throw CredentialsMissing()
        val token = loginCall(country, credentials.email, credentials.passwordDigest)
        cachedToken = token
        store.saveToken(token)
        return token
    }

    suspend fun storeCredentials(email: String, passwordDigest: String, token: String?) {
        store.save(email, passwordDigest)
        cachedToken = token
        if (token != null) store.saveToken(token)
    }

    /** Sign-out clears credentials; the journal is untouched by design. */
    suspend fun signOut() {
        cachedToken = null
        store.clear()
    }

    class CredentialsMissing : Exception("no stored credentials")
}
