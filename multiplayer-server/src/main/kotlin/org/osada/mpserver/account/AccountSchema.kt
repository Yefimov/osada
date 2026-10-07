package org.osada.mpserver.account

/**
 * Ordered, append-only schema migrations, tracked in SQLite's `user_version`.
 *
 * Never edit a released step; add the next one. Times are UTC epoch milliseconds from the server
 * clock, never from a client.
 */
object AccountSchema {
    private val steps: List<List<String>> =
        listOf(
            // 1 — accounts, provider identities, browser sessions and pending sign-ins (Stage C).
            listOf(
                """
                CREATE TABLE accounts (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    last_active_at INTEGER NOT NULL,
                    data_epoch INTEGER NOT NULL DEFAULT 1,
                    quota_bytes INTEGER NOT NULL,
                    used_bytes INTEGER NOT NULL DEFAULT 0
                )
                """,
                // Google: issuer + `sub`; GitHub: the numeric user id. Never an email or a login name.
                """
                CREATE TABLE identities (
                    provider TEXT NOT NULL,
                    subject TEXT NOT NULL,
                    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    linked_at INTEGER NOT NULL,
                    PRIMARY KEY (provider, subject)
                )
                """,
                "CREATE INDEX identities_by_account ON identities(account_id)",
                // Only a hash of the cookie token is stored: a leaked database cannot sign anybody in.
                """
                CREATE TABLE sessions (
                    token_hash TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    csrf_token TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    revoked_at INTEGER
                )
                """,
                "CREATE INDEX sessions_by_account ON sessions(account_id)",
                "CREATE INDEX sessions_by_expiry ON sessions(expires_at)",
                """
                CREATE TABLE oauth_states (
                    state_hash TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    browser_binding_hash TEXT NOT NULL,
                    code_verifier TEXT NOT NULL,
                    nonce TEXT NOT NULL,
                    link_account_id TEXT REFERENCES accounts(id) ON DELETE CASCADE,
                    expires_at INTEGER NOT NULL
                )
                """,
            ),
            // 2 — online saves (Stage D): one row per playthrough, its retained snapshots and the
            // idempotency receipts that make a retried upload return its original answer.
            listOf(
                // `state` is live, completed or deleted. A deleted run keeps its row without payloads
                // as a tombstone, so an offline device cannot silently recreate it under the same id.
                """
                CREATE TABLE runs (
                    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    run_instance_id TEXT NOT NULL,
                    campaign_file TEXT NOT NULL,
                    data_epoch INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    parent_revision INTEGER NOT NULL,
                    content_hash TEXT NOT NULL,
                    state TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    client_played_at INTEGER,
                    server_saved_at INTEGER NOT NULL,
                    PRIMARY KEY (account_id, run_instance_id)
                )
                """,
                // Roles: latest (resume), previous (last good before it) and mission-start (immutable
                // opening of the current mission). Payloads are gzip-compressed save JSON.
                """
                CREATE TABLE snapshots (
                    account_id TEXT NOT NULL,
                    run_instance_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    content_hash TEXT NOT NULL,
                    mission_key TEXT,
                    payload BLOB NOT NULL,
                    decoded_bytes INTEGER NOT NULL,
                    stored_bytes INTEGER NOT NULL,
                    client_played_at INTEGER,
                    server_saved_at INTEGER NOT NULL,
                    PRIMARY KEY (account_id, run_instance_id, role),
                    FOREIGN KEY (account_id, run_instance_id)
                        REFERENCES runs(account_id, run_instance_id) ON DELETE CASCADE
                )
                """,
                """
                CREATE TABLE receipts (
                    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                    request_id TEXT NOT NULL,
                    request_hash TEXT NOT NULL,
                    status INTEGER NOT NULL,
                    response TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (account_id, request_id)
                )
                """,
                "CREATE INDEX receipts_by_expiry ON receipts(expires_at)",
            ),
        )

    val CURRENT_VERSION: Int get() = steps.size

    /** Applies every step above the database's version, each in its own transaction. */
    fun migrate(database: AccountDatabase): Int {
        val from = database.schemaVersion()
        require(from <= CURRENT_VERSION) {
            "Database schema $from is newer than this server ($CURRENT_VERSION); refusing to touch it."
        }
        for (version in from until CURRENT_VERSION) {
            database.transactionBlocking {
                steps[version].forEach { execute(it.trimIndent()) }
                execute("PRAGMA user_version = ${version + 1}")
            }
        }
        return CURRENT_VERSION - from
    }
}
