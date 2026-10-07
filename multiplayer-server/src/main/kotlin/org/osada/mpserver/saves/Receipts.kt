package org.osada.mpserver.saves

import org.osada.mpserver.account.Tokens
import org.osada.mpserver.account.Tx

/** The client's idempotency key plus a hash of what that request asked for. */
class ReceiptKey(
    val requestId: String,
    val requestHash: String,
) {
    companion object {
        fun of(
            requestId: String,
            vararg parts: Any?,
        ): ReceiptKey = ReceiptKey(requestId, Tokens.sha256(parts.joinToString("\u0000") { it?.toString() ?: "" }))
    }
}

/** An HTTP answer as stored for replay: status plus the exact JSON body first sent. */
class RenderedReply(
    val status: Int,
    val body: String,
)

/**
 * Accepted writes keep their reply for [LIFETIME_MILLIS] (§4: bounded retry lifetime). Reusing a key
 * for a different request is a client bug and is refused rather than answered with someone else's receipt.
 */
internal object Receipts {
    const val LIFETIME_MILLIS = 86_400_000L
    const val KEY_REUSED_STATUS = 422

    fun find(
        tx: Tx,
        accountId: String,
        key: ReceiptKey,
    ): RenderedReply? =
        tx.single(
            "SELECT * FROM receipts WHERE account_id = ? AND request_id = ?",
            accountId,
            key.requestId,
        ) {
            if (it.getString("request_hash") == key.requestHash) {
                RenderedReply(it.getInt("status"), it.getString("response"))
            } else {
                RenderedReply(KEY_REUSED_STATUS, """{"error":"request-id-reused"}""")
            }
        }

    fun store(
        tx: Tx,
        accountId: String,
        key: ReceiptKey,
        reply: RenderedReply,
        now: Long,
    ) {
        tx.update(
            """INSERT INTO receipts(account_id, request_id, request_hash, status, response, expires_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            accountId,
            key.requestId,
            key.requestHash,
            reply.status,
            reply.body,
            now + LIFETIME_MILLIS,
        )
    }
}
