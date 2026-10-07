package org.osada.mpserver.account

/**
 * Browser sessions (§6.1): a random cookie token whose hash alone is stored, a per-session CSRF
 * token, a fixed expiry and server-side revocation. The account id of every request comes from here.
 */
class SessionService(
    private val database: AccountDatabase,
    private val sessionLifetimeMillis: Long,
) {
    suspend fun issueSession(
        accountId: String,
        now: Long,
    ): IssuedSession {
        val issued = IssuedSession(Tokens.secret(), Tokens.secret(), now + sessionLifetimeMillis)
        database.transaction {
            update(
                """INSERT INTO sessions(token_hash, account_id, csrf_token, created_at, expires_at)
                   VALUES (?, ?, ?, ?, ?)""",
                Tokens.sha256(issued.token),
                accountId,
                issued.csrfToken,
                now,
                issued.expiresAt,
            )
        }
        return issued
    }

    suspend fun resolveSession(
        token: String,
        now: Long,
    ): ActiveSession? =
        database.transaction {
            single(
                """SELECT * FROM sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?""",
                Tokens.sha256(token),
                now,
            ) {
                ActiveSession(
                    tokenHash = it.getString("token_hash"),
                    accountId = it.getString("account_id"),
                    csrfToken = it.getString("csrf_token"),
                    createdAt = it.getLong("created_at"),
                    expiresAt = it.getLong("expires_at"),
                )
            }
        }

    suspend fun revokeSession(
        tokenHash: String,
        now: Long,
    ) {
        database.transaction {
            update("UPDATE sessions SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, tokenHash)
        }
    }

    suspend fun revokeAllSessions(
        accountId: String,
        now: Long,
    ) {
        database.transaction {
            update("UPDATE sessions SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL", now, accountId)
        }
    }

    /** Housekeeping only: forgets finished sign-ins and sessions nobody can present any more. */
    suspend fun sweep(now: Long): Int =
        database.transaction {
            update("DELETE FROM oauth_states WHERE expires_at <= ?", now) +
                update(
                    "DELETE FROM sessions WHERE expires_at <= ? OR (revoked_at IS NOT NULL AND revoked_at <= ?)",
                    now,
                    now - REVOKED_SESSION_KEEP_MILLIS,
                )
        }

    private companion object {
        const val REVOKED_SESSION_KEEP_MILLIS = AccountConfig.MILLIS_PER_DAY
    }
}
