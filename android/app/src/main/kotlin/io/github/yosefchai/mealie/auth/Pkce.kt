package io.github.yosefchai.mealie.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE (RFC 7636) עם S256, ו-state/nonce אקראיים. */
data class Pkce(val verifier: String, val challenge: String, val state: String, val nonce: String) {
    companion object {
        private val random = SecureRandom()

        fun create(): Pkce {
            val verifier = randomUrlSafe(64)
            return Pkce(verifier, challengeFor(verifier), randomUrlSafe(24), randomUrlSafe(24))
        }

        fun challengeFor(verifier: String): String =
            encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun randomUrlSafe(bytes: Int): String = encode(ByteArray(bytes).also(random::nextBytes))

        private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
