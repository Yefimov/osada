package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.i18n.I18n

/**
 * The portrait rotation prompt (spec §10.2) and the optional fullscreen/orientation-lock
 * enhancement (§10.4, §47).
 *
 * Landscape is the primary phone mode because the map is the work surface and the HUD's top and
 * bottom command areas already fit that shape. Portrait is not *blocked*, though: the player may
 * explicitly continue, and that choice lasts for the session only — persisting it would leave
 * someone stuck in a layout they picked once by accident.
 *
 * Orientation lock is best effort. A rejected promise is normal (it needs fullscreen, and several
 * browsers refuse outright), so failure is swallowed and the ordinary gate keeps working.
 */
internal object OrientationGate {
    private const val GATE_ID = "osadaOrientationGate"

    private var dismissedForSession = false

    fun update(mode: LayoutMode) {
        val shouldShow = mode == LayoutMode.PHONE_PORTRAIT && !dismissedForSession
        val gate = if (shouldShow) ensureGate() else document.getElementById(GATE_ID)?.asDynamic()
        gate?.style?.display = if (shouldShow) "flex" else "none"
    }

    private fun ensureGate(): dynamic {
        document.getElementById(GATE_ID)?.let { return it.asDynamic() }
        val gate = addTag("mainbody", "div")
        gate.id = GATE_ID
        gate.className = "osada-orientation-gate"
        gate.setAttribute("role", "dialog")
        gate.setAttribute("aria-modal", "true")

        val card = addTag(gate, "div")
        card.className = "osada-orientation-gate__card"
        val icon = addTag(card, "div")
        icon.className = "osada-orientation-gate__icon"
        icon.setAttribute("aria-hidden", "true")
        icon.textContent = "↻"
        val title = addTag(card, "div")
        title.className = "osada-orientation-gate__title"
        title.textContent = I18n.t("mobile.orientation.title")
        val body = addTag(card, "div")
        body.className = "osada-orientation-gate__body"
        body.textContent = I18n.t("mobile.orientation.body")

        val actions = addTag(card, "div")
        actions.className = "osada-orientation-gate__actions"
        if (fullscreenSupported()) {
            val full = addTag(actions, "div")
            full.className = "osada-btn osada-btn--secondary"
            full.textContent = I18n.t("mobile.orientation.fullscreen")
            full.asButton(I18n.t("mobile.orientation.fullscreen")) { enterFullscreen() }
        }
        val proceed = addTag(actions, "div")
        proceed.className = "osada-btn osada-btn--primary"
        proceed.textContent = I18n.t("mobile.orientation.continue")
        proceed.asButton(I18n.t("mobile.orientation.continue")) {
            dismissedForSession = true
            MobileLayoutController.applyNow()
        }
        return gate.asDynamic()
    }

    fun fullscreenSupported(): Boolean {
        val el = document.documentElement?.asDynamic() ?: return false
        return el.requestFullscreen != null && el.requestFullscreen != undefined
    }

    /**
     * Must be called straight from a user gesture. Both promises are swallowed on rejection —
     * neither fullscreen nor orientation lock is allowed to be load-bearing.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun enterFullscreen() {
        val el = document.documentElement?.asDynamic() ?: return
        try {
            val promise = el.requestFullscreen()
            val lock = { lockLandscape() }
            if (promise != null && promise != undefined) promise.then(lock, { Unit }) else lockLandscape()
        } catch (_: Throwable) {
            // Fullscreen refused (permissions policy, unsupported element): keep the normal gate.
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun lockLandscape() {
        try {
            val orientation = window.asDynamic().screen?.orientation ?: return
            if (orientation.lock == null || orientation.lock == undefined) return
            val promise = orientation.lock("landscape")
            if (promise != null && promise != undefined) promise.then({ Unit }, { Unit })
        } catch (_: Throwable) {
            // Orientation lock is best effort by design (spec §10.4).
        }
    }
}
