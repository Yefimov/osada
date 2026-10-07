package org.osada.account

/**
 * What the server says about optional accounts (online-saves-and-player-profile.md §6.1).
 * `enabled = false` hides every account control: guest play is the unchanged default.
 */
data class AccountServerConfig(
    val enabled: Boolean,
    val signupEnabled: Boolean,
    val providers: List<String>,
) {
    companion object {
        val OFF = AccountServerConfig(enabled = false, signupEnabled = false, providers = emptyList())
    }
}

data class LinkedProvider(
    val provider: String,
    val linkedAt: Double,
)

/** The signed-in account as `/api/account/me` describes it. No email or provider name is ever sent. */
data class AccountProfile(
    val accountId: String,
    val csrf: String,
    val createdAt: Double,
    val lastActiveAt: Double,
    val dataEpoch: Int,
    val usedBytes: Double,
    val quotaBytes: Double,
    val identities: List<LinkedProvider>,
) {
    /** §5.1: online saves may be removed after 12 months without play or sign-in. */
    val retentionDeadline: Double get() = lastActiveAt + RETENTION_MILLIS

    fun hasProvider(provider: String): Boolean = identities.any { it.provider == provider }

    companion object {
        const val RETENTION_MILLIS = 365.0 * 86_400_000.0
    }
}

/** Parsers for the server's JSON; a missing or mistyped field degrades, it never throws. */
object AccountJson {
    fun config(json: dynamic): AccountServerConfig {
        if (json == null || json.enabled != true) return AccountServerConfig.OFF
        val providers =
            (json.providers as? Array<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it in KNOWN_PROVIDERS }
                .orEmpty()
        return AccountServerConfig(
            enabled = providers.isNotEmpty(),
            signupEnabled = json.signupEnabled != false,
            providers = providers,
        )
    }

    fun profile(json: dynamic): AccountProfile? {
        val signedIn = json != null && json.signedIn == true
        val accountId = json?.accountId as? String
        val csrf = json?.csrf as? String
        if (!signedIn || accountId == null || csrf == null) return null
        val identities =
            (json.identities as? Array<*>)
                ?.mapNotNull { entry ->
                    val item = entry.asDynamic()
                    (item.provider as? String)?.let { LinkedProvider(it, number(item.linkedAt)) }
                }.orEmpty()
        return AccountProfile(
            accountId = accountId,
            csrf = csrf,
            createdAt = number(json.createdAt),
            lastActiveAt = number(json.lastActiveAt),
            dataEpoch = number(json.dataEpoch).toInt(),
            usedBytes = number(json.usedBytes),
            quotaBytes = number(json.quotaBytes),
            identities = identities,
        )
    }

    private fun number(value: dynamic): Double = (value as? Number)?.toDouble() ?: 0.0

    val KNOWN_PROVIDERS = listOf("google", "github")
}

/**
 * The provider callback lands on `/#osada-account=<code>`. The code is a short machine word chosen
 * by the server; anything else in the fragment is ignored rather than displayed.
 */
object AccountRedirect {
    const val FRAGMENT_PREFIX = "#osada-account="
    private val CODE = Regex("^[a-z-]{1,40}$")

    fun resultCode(hash: String): String? =
        hash
            .takeIf { it.startsWith(FRAGMENT_PREFIX) }
            ?.removePrefix(FRAGMENT_PREFIX)
            ?.takeIf(CODE::matches)

    /** Translation key for a result; unknown refusals share one generic message. */
    fun messageKey(code: String): String =
        if (code in KNOWN_RESULTS) "account.result.$code" else "account.result.failed"

    private val KNOWN_RESULTS =
        setOf(
            "signed-in",
            "linked",
            "cancelled",
            "signup-disabled",
            "identity-in-use",
            "provider-already-linked",
            "sign-in-expired",
            "sign-in-mismatch",
            "rate-limited",
        )
}
