package org.osada.mpserver.account

import java.io.File

/**
 * Optional player accounts (online-saves-and-player-profile.md §6, Stage C).
 *
 * Everything is off unless `OSADA_ACCOUNTS_ENABLED=true` and a database path is configured, so the
 * room server keeps running unchanged on a host that has no provider applications yet. Sign-up can
 * be switched off on its own: existing accounts keep signing in, new identities are refused.
 *
 * Provider secrets come from the environment (on the VPS an `EnvironmentFile` readable only by
 * root); they are never logged and never sent to the browser.
 */
data class AccountConfig(
    val enabled: Boolean,
    val signupEnabled: Boolean,
    val databaseFile: File?,
    /** Public origin used to build OAuth redirect URIs, e.g. `https://osada.fun`. */
    val publicOrigin: String,
    val cookieSecure: Boolean,
    val sessionLifetimeMillis: Long,
    val providers: List<ProviderCredentials>,
    /** Uploads can be stopped on their own (§8) while manifests, downloads and exports keep working. */
    val uploadsEnabled: Boolean = true,
    /** §5.2: one solo snapshot, measured as decoded save JSON. Provisional until real saves are measured. */
    val maxSnapshotBytes: Long = DEFAULT_MAX_SNAPSHOT_BYTES,
    /** Global free-space floor on the database volume; below it new payload writes are refused. */
    val diskReserveBytes: Long = DEFAULT_DISK_RESERVE_BYTES,
) {
    /** Accounts are offered only when there is somewhere to keep them and someone to sign in with. */
    val active: Boolean get() = enabled && databaseFile != null && providers.isNotEmpty() && publicOrigin.isNotEmpty()

    fun redirectUri(provider: String): String = "$publicOrigin/api/auth/$provider/callback"

    fun credentials(provider: String): ProviderCredentials? = providers.firstOrNull { it.provider == provider }

    companion object {
        val DISABLED =
            AccountConfig(
                enabled = false,
                signupEnabled = false,
                databaseFile = null,
                publicOrigin = "",
                cookieSecure = true,
                sessionLifetimeMillis = DEFAULT_SESSION_DAYS * MILLIS_PER_DAY,
                providers = emptyList(),
            )

        fun fromEnvironment(env: (String) -> String? = System::getenv): AccountConfig {
            fun flag(
                name: String,
                default: Boolean,
            ): Boolean = env(name)?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: default

            fun value(name: String): String? = env(name)?.trim()?.takeIf { it.isNotEmpty() }

            val providers =
                listOfNotNull(
                    credentialsOf(GOOGLE, value("OSADA_GOOGLE_CLIENT_ID"), value("OSADA_GOOGLE_CLIENT_SECRET")),
                    credentialsOf(GITHUB, value("OSADA_GITHUB_CLIENT_ID"), value("OSADA_GITHUB_CLIENT_SECRET")),
                )
            return AccountConfig(
                enabled = flag("OSADA_ACCOUNTS_ENABLED", default = false),
                signupEnabled = flag("OSADA_SIGNUP_ENABLED", default = true),
                databaseFile = value("OSADA_DB_PATH")?.let(::File),
                publicOrigin = value("OSADA_PUBLIC_ORIGIN")?.trimEnd('/').orEmpty(),
                // Plain-HTTP localhost development cannot hold a Secure cookie.
                cookieSecure = flag("OSADA_COOKIE_SECURE", default = true),
                sessionLifetimeMillis =
                    (value("OSADA_SESSION_DAYS")?.toLongOrNull() ?: DEFAULT_SESSION_DAYS) * MILLIS_PER_DAY,
                providers = providers,
                uploadsEnabled = flag("OSADA_UPLOADS_ENABLED", default = true),
                maxSnapshotBytes = value("OSADA_MAX_SNAPSHOT_BYTES")?.toLongOrNull() ?: DEFAULT_MAX_SNAPSHOT_BYTES,
                diskReserveBytes = value("OSADA_DISK_RESERVE_BYTES")?.toLongOrNull() ?: DEFAULT_DISK_RESERVE_BYTES,
            )
        }

        private fun credentialsOf(
            provider: String,
            clientId: String?,
            secret: String?,
        ): ProviderCredentials? =
            if (clientId != null && secret != null) ProviderCredentials(provider, clientId, secret) else null

        const val GOOGLE = "google"
        const val GITHUB = "github"
        const val MILLIS_PER_DAY = 86_400_000L
        private const val DEFAULT_SESSION_DAYS = 30L
        const val DEFAULT_MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_DISK_RESERVE_BYTES = 512L * 1024 * 1024
    }
}

class ProviderCredentials(
    val provider: String,
    val clientId: String,
    val clientSecret: String,
) {
    // The secret must never reach a log line through an accidental string template.
    override fun toString(): String = "ProviderCredentials($provider, clientId=$clientId)"
}
