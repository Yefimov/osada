package org.osada.save

import kotlinx.browser.window
import org.osada.storage.BrowserStorage
import kotlin.js.Promise

/**
 * One writer per browser profile: only the tab holding this lock may write saves, run an import,
 * or recover an interrupted one (`docs/design/online-saves-and-player-profile.md` §4, Stage B).
 *
 * Two tabs used to autosave over each other's campaigns, and a second tab opened during an import
 * would have run bootstrap recovery and rolled back the first tab's import while it was being
 * written. The lock is therefore taken BEFORE recovery and before the game exists.
 *
 * Uses the Web Locks API (held for the page's lifetime, released by the browser when the tab
 * closes or crashes -- no stale lease to expire). A tab that loses the lock to "Play here" in
 * another tab suspends every storage write at once, so nothing it still does can overwrite the
 * new owner. Browsers without Web Locks run uncoordinated, as before; [enforced] says which.
 */
internal object ProfileWriterLock {
    const val NAME = "osada-profile-writer-v1"

    /** False on browsers without Web Locks: no coordination is possible, so none is claimed. */
    var enforced = false
        private set

    /** Whether this tab may write. True until proven otherwise on an uncoordinated browser. */
    var held = false
        private set

    private val locks: dynamic get() = window.navigator.asDynamic().locks

    /**
     * Calls [onHolder] once this tab owns the profile, or [onBusy] when another tab does.
     * [onLost] runs if a later "Play here" elsewhere takes the lock away.
     */
    fun acquire(
        onHolder: () -> Unit,
        onBusy: () -> Unit,
        onLost: () -> Unit,
    ) {
        val api = locks
        if (api == null || api.request == null) {
            held = true
            onHolder()
            return
        }
        enforced = true
        request(api, js("({ ifAvailable: true })"), onHolder, onBusy, onLost)
    }

    /** "Play here": take the profile from the other tab, which stops writing immediately. */
    fun takeOver(
        onHolder: () -> Unit,
        onLost: () -> Unit,
    ) {
        val api = locks ?: return onHolder()
        request(api, js("({ steal: true })"), onHolder, {}, onLost)
    }

    private fun request(
        api: dynamic,
        options: dynamic,
        onHolder: () -> Unit,
        onBusy: () -> Unit,
        onLost: () -> Unit,
    ) {
        val promise: Promise<dynamic> =
            api
                .request(NAME, options) { lock: dynamic ->
                    if (lock == null) {
                        onBusy()
                        Promise.resolve(Unit)
                    } else {
                        held = true
                        onHolder()
                        // Never settles: the lock is held until the page goes away.
                        Promise<Unit> { _, _ -> }
                    }
                }.unsafeCast<Promise<dynamic>>()
        promise.catch { error ->
            // A steal rejects the holder's request with AbortError: the profile now belongs elsewhere.
            if (held) {
                held = false
                BrowserStorage.suspendWrites()
                console.warn("[osada] profile taken over by another tab; this tab stopped saving", error)
                onLost()
            }
        }
    }
}
