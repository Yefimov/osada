package org.osada.mpserver.saves

import org.osada.mpserver.account.Tokens
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Payload storage format and the content hash both sides compute over the save's UTF-8 bytes. */
object SaveCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** `sha256-<base64url>`; detects equality and corruption, proves nothing about how the game was played. */
    fun contentHash(decoded: ByteArray): String = "sha256-" + encoder.encodeToString(Tokens.digest(decoded))

    fun compress(decoded: ByteArray): ByteArray =
        ByteArrayOutputStream(decoded.size / COMPRESSION_GUESS + 1)
            .also { out -> GZIPOutputStream(out).use { it.write(decoded) } }
            .toByteArray()

    /** Expansion is bounded separately from the stored size (§5.2), so a hostile row cannot balloon. */
    fun decompress(
        compressed: ByteArray,
        limit: Long,
    ): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val bytes = input.readNBytes((limit + 1).toInt())
            check(bytes.size <= limit) { "Stored payload expands beyond $limit bytes" }
            bytes
        }

    private const val COMPRESSION_GUESS = 6
}
