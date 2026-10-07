package org.osada.account

import kotlinx.browser.window

/**
 * Session-wide knowledge of the optional account: whether the server offers accounts, who (if
 * anyone) is signed in, and the result of a provider round trip that just returned to the page.
 *
 * Nothing here gates play. Until [refresh] answers, and whenever it fails, the player is a guest.
 */
object OnlineAccount {
    var config: AccountServerConfig = AccountServerConfig.OFF
        private set
    var profile: AccountProfile? = null
        private set

    /** A provider callback's result code, kept until the account panel has shown it once. */
    var pendingResult: String? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    val signedIn: Boolean get() = profile != null

    fun onChange(listener: () -> Unit) {
        listeners += listener
    }

    /**
     * Reads the provider callback's `#osada-account=` fragment once at boot and removes it from the
     * address bar, so a reload or a shared link does not repeat "signed in".
     */
    fun consumeRedirect() {
        val code = AccountRedirect.resultCode(window.location.hash) ?: return
        pendingResult = code
        runCatching {
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
    }

    fun takePendingResult(): String? = pendingResult.also { pendingResult = null }

    /** Asks the server what it offers and who this browser is. Failures leave a guest. */
    fun refresh(done: () -> Unit = {}) {
        AccountApi.config { reply ->
            config = if (reply.ok) AccountJson.config(reply.json) else AccountServerConfig.OFF
            if (!config.enabled) {
                update(null)
                done()
                return@config
            }
            AccountApi.me { me ->
                update(if (me.ok) AccountJson.profile(me.json) else profile.takeIf { me.status == 0 })
                done()
            }
        }
    }

    /** Sets both halves at once; also the seam DOM tests use instead of a server. */
    internal fun apply(
        nextConfig: AccountServerConfig,
        nextProfile: AccountProfile?,
    ) {
        config = nextConfig
        update(nextProfile)
    }

    /** Applies an account-changing reply (`/me`, unlink) or a signed-out answer. */
    fun update(next: AccountProfile?) {
        profile = next
        listeners.toList().forEach { it() }
    }
}
