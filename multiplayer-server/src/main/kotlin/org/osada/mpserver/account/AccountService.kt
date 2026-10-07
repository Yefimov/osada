package org.osada.mpserver.account

/** A refusal the browser is shown as a reason code; never carries provider or database detail. */
class AccountRefusal(
    val code: String,
) : Exception(code)

class PendingSignIn(
    val provider: String,
    val codeVerifier: String,
    val nonce: String,
    val linkAccountId: String?,
)

class StartedSignIn(
    val state: String,
    val browserBinding: String,
    val codeVerifier: String,
    val nonce: String,
)

class IssuedSession(
    val token: String,
    val csrfToken: String,
    val expiresAt: Long,
)

class ActiveSession(
    val tokenHash: String,
    val accountId: String,
    val csrfToken: String,
    val createdAt: Long,
    val expiresAt: Long,
)

class LinkedIdentity(
    val provider: String,
    val linkedAt: Long,
)

class AccountView(
    val id: String,
    val createdAt: Long,
    val lastActiveAt: Long,
    val dataEpoch: Long,
    val quotaBytes: Long,
    val usedBytes: Long,
    val identities: List<LinkedIdentity>,
)

/**
 * Accounts, identities and sessions (§6.1). Pure database rules: no HTTP, no provider calls, and
 * every time comes in as an argument so the retention boundaries can be tested exactly.
 */
class AccountService(
    val database: AccountDatabase,
    private val config: AccountConfig,
) {
    suspend fun beginSignIn(
        provider: String,
        linkAccountId: String?,
        now: Long,
    ): StartedSignIn {
        val started = StartedSignIn(Tokens.secret(), Tokens.secret(), Tokens.secret(), Tokens.secret())
        database.transaction {
            update(
                """INSERT INTO oauth_states(state_hash, provider, browser_binding_hash, code_verifier, nonce,
                   link_account_id, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)""",
                Tokens.sha256(started.state),
                provider,
                Tokens.sha256(started.browserBinding),
                started.codeVerifier,
                started.nonce,
                linkAccountId,
                now + SIGN_IN_WINDOW_MILLIS,
            )
        }
        return started
    }

    /**
     * Single use: the state row is deleted whether or not it matches, so a replayed callback fails.
     * The browser binding cookie ties the callback to the browser that began the sign-in (login CSRF).
     */
    suspend fun consumeSignIn(
        provider: String,
        state: String,
        browserBinding: String?,
        now: Long,
    ): PendingSignIn {
        // Read and delete commit together before any check, so a refusal cannot roll the delete back.
        val row =
            database.transaction {
                val stateHash = Tokens.sha256(state)
                single("SELECT * FROM oauth_states WHERE state_hash = ?", stateHash) {
                    Triple(
                        PendingSignIn(
                            provider = it.getString("provider"),
                            codeVerifier = it.getString("code_verifier"),
                            nonce = it.getString("nonce"),
                            linkAccountId = it.getString("link_account_id"),
                        ),
                        it.getString("browser_binding_hash"),
                        it.getLong("expires_at"),
                    )
                }.also { update("DELETE FROM oauth_states WHERE state_hash = ?", stateHash) }
            }
        val refusal =
            when {
                row == null || row.third <= now -> "sign-in-expired"
                row.first.provider != provider -> "sign-in-mismatch"
                browserBinding == null ||
                    !Tokens.sameSecret(
                        Tokens.sha256(browserBinding),
                        row.second,
                    ) -> "sign-in-mismatch"
                else -> null
            }
        if (refusal != null || row == null) throw AccountRefusal(refusal ?: "sign-in-expired")
        return row.first
    }

    /** Returns the account the identity now belongs to. Accounts are never merged on matching emails. */
    suspend fun completeSignIn(
        identity: ProviderIdentity,
        linkAccountId: String?,
        now: Long,
    ): String =
        database.transaction {
            val owner =
                single(
                    "SELECT account_id FROM identities WHERE provider = ? AND subject = ?",
                    identity.provider,
                    identity.subject,
                ) { it.getString(1) }
            val accountId =
                when {
                    linkAccountId != null -> link(owner, identity, linkAccountId, now)
                    owner != null -> owner
                    !config.signupEnabled -> throw AccountRefusal("signup-disabled")
                    else -> createAccount(identity, now)
                }
            touchActivity(accountId, now)
            accountId
        }

    private fun Tx.link(
        owner: String?,
        identity: ProviderIdentity,
        accountId: String,
        now: Long,
    ): String {
        if (owner != null && owner != accountId) throw AccountRefusal("identity-in-use")
        if (owner == null) {
            val providerTaken =
                single(
                    "SELECT 1 FROM identities WHERE account_id = ? AND provider = ?",
                    accountId,
                    identity.provider,
                ) { true } ?: false
            if (providerTaken) throw AccountRefusal("provider-already-linked")
            update(
                "INSERT INTO identities(provider, subject, account_id, linked_at) VALUES (?, ?, ?, ?)",
                identity.provider,
                identity.subject,
                accountId,
                now,
            )
        }
        return accountId
    }

    private fun Tx.createAccount(
        identity: ProviderIdentity,
        now: Long,
    ): String {
        val accountId = Tokens.accountId()
        update(
            "INSERT INTO accounts(id, created_at, last_active_at, quota_bytes) VALUES (?, ?, ?, ?)",
            accountId,
            now,
            now,
            DEFAULT_QUOTA_BYTES,
        )
        update(
            "INSERT INTO identities(provider, subject, account_id, linked_at) VALUES (?, ?, ?, ?)",
            identity.provider,
            identity.subject,
            accountId,
            now,
        )
        return accountId
    }

    /**
     * Meaningful activity renews retention (§5.1): an interactive sign-in or an accepted game commit.
     * Writes are coalesced to one per day; background polling must never call this.
     */
    suspend fun recordActivity(
        accountId: String,
        now: Long,
    ) {
        database.transaction { touchActivity(accountId, now) }
    }

    private fun Tx.touchActivity(
        accountId: String,
        now: Long,
    ) {
        update(
            "UPDATE accounts SET last_active_at = ? WHERE id = ? AND last_active_at <= ?",
            now,
            accountId,
            now - ACTIVITY_COALESCE_MILLIS,
        )
    }

    suspend fun view(accountId: String): AccountView? =
        database.transaction {
            val identities =
                query("SELECT provider, linked_at FROM identities WHERE account_id = ? ORDER BY linked_at", accountId) {
                    LinkedIdentity(it.getString("provider"), it.getLong("linked_at"))
                }
            single("SELECT * FROM accounts WHERE id = ?", accountId) {
                AccountView(
                    id = it.getString("id"),
                    createdAt = it.getLong("created_at"),
                    lastActiveAt = it.getLong("last_active_at"),
                    dataEpoch = it.getLong("data_epoch"),
                    quotaBytes = it.getLong("quota_bytes"),
                    usedBytes = it.getLong("used_bytes"),
                    identities = identities,
                )
            }
        }

    /** Refuses to remove the last provider: that would lock the player out of their own saves. */
    suspend fun unlink(
        accountId: String,
        provider: String,
    ) {
        database.transaction {
            val providers = query("SELECT provider FROM identities WHERE account_id = ?", accountId) { it.getString(1) }
            when {
                provider !in providers -> throw AccountRefusal("not-linked")
                providers.size <= 1 -> throw AccountRefusal("last-identity")
            }
            update("DELETE FROM identities WHERE account_id = ? AND provider = ?", accountId, provider)
        }
    }

    /** Explicit deletion: access ends at once; identities, sessions and (later) saves cascade. */
    suspend fun deleteAccount(accountId: String) {
        database.transaction { update("DELETE FROM accounts WHERE id = ?", accountId) }
    }

    companion object {
        const val SIGN_IN_WINDOW_MILLIS = 10 * 60_000L
        const val ACTIVITY_COALESCE_MILLIS = AccountConfig.MILLIS_PER_DAY

        /** §5.2 provisional per-account budget for stored compressed payloads. */
        const val DEFAULT_QUOTA_BYTES = 64L * 1024 * 1024
    }
}
