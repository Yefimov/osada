package org.osada.ui

import org.osada.i18n.I18n
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.uiSettings
import org.w3c.dom.HTMLElement

/**
 * Keeps the settings screen's **Observer Mode** section switched off and inert while a multiplayer
 * match is running (2026-08-16 user request).
 *
 * Every toggle in that section is a single-player-only advantage: "Disable Fog of War" and "Show
 * hidden objectives" hand one seat information the other seat does not have, and Stalin Regime
 * multiplies that seat's combat, movement and prestige numbers by ten. A multiplayer match is two
 * humans sharing one host-authoritative game state, so none of them can be allowed on.
 *
 * Two halves, deliberately separate:
 *  - [clearAll] is called once, when a match begins ([OsadaMultiplayer]), so a player who had these
 *    on in single-player does not carry the advantage in with them.
 *  - [refresh] re-applies the *presentation* to the already-built DOM. The settings screen is built
 *    once at startup and only shown/hidden afterwards, so the locked state cannot be baked in at
 *    build time; it is re-applied whenever Settings opens and after a language change.
 *
 * Its own file rather than a [StartMenuSettingsBuilder] member: that object is already at detekt's
 * function ceiling, and this is one self-contained rule.
 */
internal object ObserverModeLock {
    /** The ids this lock owns — the whole `settings.section.observer` block. */
    val lockedIds = listOf("stalinRegime", "noFOW", "showHiddenVictoryHexes")

    /** Set by [StartMenuSettingsBuilder] on the one section header that carries `balanceWarning`. */
    const val HEADER_ID = "osadaObserverSectionHeader"

    /** True while a multiplayer match is running. */
    fun isLocked(): Boolean = OsadaMultiplayer.active

    /** Turns every Observer Mode toggle off. Safe to call when none of them is on. */
    fun clearAll() {
        lockedIds.forEach { uiSettings.setFlag(it, false) }
    }

    /** Re-applies the lock (or lifts it) on the already-built settings DOM. */
    fun refresh() {
        val locked = isLocked()
        if (locked) clearAll()
        refreshHeader(locked)
        lockedIds.forEach { refreshRow(it, locked) }
    }

    private fun refreshHeader(locked: Boolean) {
        val header = byId(HEADER_ID) ?: return
        header.classList.toggle("osada-settings-header--locked", locked)
        val note =
            header.querySelector(".osada-settings-header__lock") as? HTMLElement
                ?: addTag(header, "span").also { it.className = "osada-settings-header__lock" }
        note.textContent = if (locked) I18n.t("settings.section.observer.locked_multiplayer") else ""
    }

    private fun refreshRow(
        id: String,
        locked: Boolean,
    ) {
        val value = byId(id) ?: return
        // The flag is already false by the time we get here (clearAll above), so the checkbox must
        // agree with it — otherwise a toggle left "on" from single-play would keep drawing checked
        // while the setting behind it is off.
        if (locked) value.classList.remove("checked")
        value.classList.toggle("osada-checkbox--locked", locked)
        val row = value.parentElement as? HTMLElement
        row?.classList?.toggle("settingContainer--locked", locked)
        val help =
            if (locked) {
                I18n.t("settings.section.observer.locked_multiplayer.help")
            } else {
                StartMenuSettingsBuilder.settingHelpKeys[id]?.let { I18n.t(it) } ?: ""
            }
        value.title = help
        row?.title = help
    }
}
