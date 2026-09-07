package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.getUnitById
import org.osada.uiSettings

/**
 * Collapse/restore for the unit card (`#unit-info`), because the card sits over the bottom-left of
 * the map and can hide the very hexes a move is being aimed at (reported 2026-09-06).
 *
 * Three affordances onto ONE piece of state, `uiSettings.unitInfoVisibility` — the flag the
 * Inspect Unit toolbar button and the keyboard `INSPECTOR` command already drive:
 *  - a chevron on the card itself, which is where a reader looks when the card is in the way;
 *  - a small chip pinned to the map's bottom-left corner, the only thing left once the card is
 *    gone, so the state can never be one a player cannot get out of;
 *  - a dock button beside Heroes on a phone, where the card covers proportionally far more map.
 *
 * The flag stays SESSION state, not a saved preference: `GameStateSettingsRestore` deliberately
 * restores it as on, because older builds cleared it as a deselect side effect and persisted a
 * permanently invisible card. Keeping that restore means a reload always brings the card back,
 * while the chip means the choice is still undoable at any moment within the session.
 *
 * Restoring falls back to the LAST unit the card held, because the selection is the first thing a
 * folded card loses: fold it, tap a hex to look under it, and the tap has already deselected --
 * so an unfold that only ever read `currentUnit` opened onto nothing and read as a dead button
 * (reported 2026-09-07). The fallback only DISPLAYS that unit; it does not reselect it, so no
 * move range, ZOC overlay or order context comes back with it -- the card is an information
 * surface, and a tap on a small control must not hand the player a unit under orders they did
 * not ask for.
 */
internal object UnitCardCollapse {
    private const val COLLAPSE_GLYPH = "−" // − minus, the classic minimise bar
    private const val RESTORE_GLYPH = "☰" // ☰

    /**
     * The last unit the card displayed, by id rather than by reference: an id cannot keep a
     * finished battle's object graph alive, and [subject] re-resolves it against the live map,
     * so a unit destroyed or disbanded while the card was folded simply is not there any more.
     * Ids restart with each scenario, which is what [forget] is for.
     */
    private var lastShownUnitId: Int? = null

    /**
     * Builds the card's minimise button: a framed `−` in the card's own top-left corner, above the
     * portrait and left of the name.
     *
     * Absolutely positioned against `#unit-info` (already `position: relative`) rather than
     * inserted into a flex row. Its first home was inline in the name line, where it read as a
     * stray character in the middle of the unit's own title; putting it in the portrait column
     * instead would have pushed the portrait down and grown the card. The corner is free space,
     * costs no reflow, and is directly above where the restore chip appears — so folding and
     * unfolding happen in the same place.
     */
    fun installCardButton(card: org.w3c.dom.HTMLElement) {
        if (byId("ucCollapse") != null) return
        val btn = addTag(card, "div")
        btn.id = "ucCollapse"
        btn.className = "osada-uc-collapse"
        btn.innerHTML = COLLAPSE_GLYPH
        btn.asButton(I18n.t("unit_info.collapse.help")) { setCollapsed(true) }
    }

    /**
     * Builds the restore chip in the viewport's bottom-left corner — where the card itself sits,
     * so the chip reads as what the card folded into.
     *
     * On `document.body` and `position: fixed`, deliberately not inside `#game`: that element is
     * the map's SCROLL container and is only as tall as the map, so a chip positioned within it
     * both scrolled away with the terrain and sat at the map's bottom edge rather than the
     * screen's.
     */
    fun installRestoreChip() {
        if (byId("osadaUnitCardRestore") != null) return
        val host = kotlinx.browser.document.body ?: return
        val chip = addTag(host, "div")
        chip.id = "osadaUnitCardRestore"
        chip.className = "osada-uc-restore"
        chip.innerHTML = RESTORE_GLYPH
        chip.asButton(I18n.t("unit_info.expand.help")) { setCollapsed(false) }
        refresh()
    }

    /** Builds the phone dock's twin of the chevron, beside Heroes. Called once, with the dock. */
    fun installDockButton(dock: org.w3c.dom.HTMLElement) {
        if (byId("osadaMobileUnitCard") != null) return
        val btn = addTag(dock, "div")
        btn.id = "osadaMobileUnitCard"
        btn.className = "osada-mobile-context__card-toggle osada-ico osada-ico--recon"
        btn.asButton(I18n.t("unit_info.collapse.help")) { setCollapsed(uiSettings.unitInfoVisibility) }
    }

    /**
     * Applies [collapsed] to the flag, the card and every affordance.
     *
     * Re-showing repaints the card from [subject] rather than trusting whatever the DOM last
     * held: the selection can have changed, or ended, while the card was hidden.
     */
    fun setCollapsed(collapsed: Boolean) {
        uiSettings.unitInfoVisibility = !collapsed
        if (collapsed) {
            makeHidden("unit-info")
        } else {
            val unit = subject()
            if (unit != null) {
                makeVisible("unit-info")
                // Repaints the whole card, and `UnitStatCard` puts the phone's bottom zone back
                // into its "own unit" state from there -- which is what swaps the context dock out
                // for the card on a phone.
                GameHolder.instance?.ui?.showUnitInfo(unit)
            }
        }
        byId("inspectunit")?.let { toggleButton(it, !collapsed) }
        refresh()
    }

    /** Notes the unit the card is being filled with. Called by [UnitInfoPanel.showUnitInfo]. */
    fun remember(unit: GameUnit?) {
        if (unit != null) lastShownUnitId = unit.id
    }

    /** Drops the memory when a new battle loads -- unit ids restart with it. */
    fun forget() {
        lastShownUnitId = null
    }

    /**
     * What an unfold should open onto: the live selection, or the last unit the card held while
     * it still had one. The remembered unit has to be found on THIS map, undestroyed and the
     * current player's own -- the last because the card being restored is the player card, and
     * `UnitStatCard` will not give the bottom zone its "own unit" state for anyone else's unit.
     */
    private fun subject(): GameUnit? {
        val map =
            GameHolder.instance
                ?.scenario
                ?.map ?: return null
        return map.currentUnit
            ?: lastShownUnitId
                ?.let { map.getUnitById(it) }
                ?.takeIf { !it.destroyed && it.player?.id == map.currentPlayer?.id }
    }

    /** Syncs the chip and the dock button with the flag; safe to call whenever the flag moves. */
    fun refresh() {
        val collapsed = !uiSettings.unitInfoVisibility
        byId("osadaUnitCardRestore")?.style?.display = if (collapsed) "flex" else "none"
        byId("osadaMobileUnitCard")?.let { toggleButton(it, !collapsed) }
    }
}
