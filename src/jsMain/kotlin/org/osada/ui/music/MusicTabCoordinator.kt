package org.osada.ui.music

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window

/** Ownership is local to this origin, never a multiplayer command. Hidden timers cannot steal it. */
internal object MusicTabCoordinator {
    private const val KEY = "osada-music-owner-v1"
    private val id = "${kotlin.js.Date.now()}-${kotlin.random.Random.nextDouble()}"
    private var channel: dynamic = null
    private var releaseLock: (() -> Unit)? = null
    private var generation = 0
    private var onTransfer: (() -> Unit)? = null
    var owner = false
        private set
    var available = false
        private set

    fun install(transferred: () -> Unit) {
        onTransfer = transferred
        try {
            localStorage.setItem("$KEY-test", id)
            localStorage.removeItem("$KEY-test")
            available = true
        } catch (_: Throwable) {
            available = false
        }
        if (js("typeof BroadcastChannel !== 'undefined'").unsafeCast<Boolean>()) {
            channel = js("new BroadcastChannel('osada-music-v1')")
            channel.onmessage = { event: dynamic -> receive(event.data as? String) }
        }
        window.addEventListener("storage", { event ->
            val raw = event.asDynamic()
            if (raw.key == KEY) receive(raw.newValue as? String)
        })
        window.addEventListener("pagehide", { release() })
    }

    fun claim(
        explicit: Boolean,
        ready: () -> Unit,
    ) {
        if (owner) {
            ready()
            return
        }
        if (!explicit && (!available || document.asDynamic().visibilityState != "visible")) return
        val locks = window.navigator.asDynamic().locks
        val token = ++generation
        // An automatic attempt cannot revoke a live lock/owner. Explicit Play can transfer it.
        if (explicit) announce()
        if (locks != null && locks.request != null) {
            claimLock(locks, explicit, token, ready)
        } else if (available) {
            claimFallback(explicit, token, ready)
        } else if (explicit) {
            // Coordination is unavailable: manual playback works, automatic resume is forbidden.
            owner = true
            ready()
        }
    }

    private fun claimLock(
        locks: dynamic,
        explicit: Boolean,
        token: Int,
        ready: () -> Unit,
    ) {
        val options = js("({mode: 'exclusive'})")
        if (explicit) options.steal = true else options.ifAvailable = true
        val result =
            locks.request("osada-music-v1", options, { lock: dynamic ->
                if (lock == null || token != generation) {
                    if (token == generation) onTransfer?.invoke()
                    null
                } else {
                    kotlin.js.Promise<Unit> { resolve, _ ->
                        releaseLock = { resolve(Unit) }
                        owner = true
                        if (explicit) announce()
                        ready()
                    }
                }
            })
        result.catch { _: dynamic -> if (owner && token == generation) lost() }
    }

    private fun claimFallback(
        explicit: Boolean,
        token: Int,
        ready: () -> Unit,
    ) {
        if (!explicit && readOwner() != null) {
            onTransfer?.invoke()
            return
        }
        announce()
        window.setTimeout({
            if (token == generation && readOwner() == id) {
                owner = true
                ready()
            }
        }, ARBITRATION_MS)
    }

    fun cancelClaim() {
        if (!owner) generation++
    }

    fun release() {
        cancelClaim()
        owner = false
        releaseLock?.invoke()
        releaseLock = null
        try {
            if (readOwner() == id) localStorage.removeItem(KEY)
        } catch (_: Throwable) {
        }
    }

    private fun announce() {
        try {
            localStorage.setItem(KEY, id)
        } catch (_: Throwable) {
        }
        channel?.postMessage(id)
    }

    private fun readOwner(): String? =
        try {
            localStorage.getItem(KEY)
        } catch (_: Throwable) {
            null
        }

    private fun receive(other: String?) {
        if (other == null || other == id) return
        // A last writer wins the fallback race; stale messages cannot revoke the final claimant.
        if (available && readOwner() != other) return
        cancelClaim()
        if (owner) lost() else onTransfer?.invoke()
    }

    private fun lost() {
        onTransfer?.invoke()
        release()
    }

    private const val ARBITRATION_MS = 120
}
