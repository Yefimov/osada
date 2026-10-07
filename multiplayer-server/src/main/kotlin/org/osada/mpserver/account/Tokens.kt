package org.osada.mpserver.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Unguessable identifiers and one-way hashes for everything a browser holds as a bearer value. */
object Tokens {
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** 256 random bits, URL-safe. Used for session cookies, OAuth state and PKCE verifiers. */
    fun secret(): String = urlEncoder.encodeToString(ByteArray(SECRET_BYTES).also(random::nextBytes))

    /** 128 random bits: an opaque account id that reveals nothing about creation order. */
    fun accountId(): String = "a_" + urlEncoder.encodeToString(ByteArray(ID_BYTES).also(random::nextBytes))

    fun sha256(value: String): String = urlEncoder.encodeToString(digest(value.toByteArray(Charsets.UTF_8)))

    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** RFC 7636 S256 challenge for [verifier]. */
    fun pkceChallenge(verifier: String): String =
        urlEncoder.encodeToString(digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Constant-time comparison for values an attacker may probe one character at a time. */
    fun sameSecret(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private const val SECRET_BYTES = 32
    private const val ID_BYTES = 16
}
