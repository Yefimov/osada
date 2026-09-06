package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n
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
 */
internal object UnitCardCollapse {
    private const val COLLAPSE_GLYPH = "−" // − minus, the classic minimise bar
    private const val RESTORE_GLYPH = "☰" // ☰

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
     * Re-showing repaints the card from the live selection rather than trusting whatever it last
     * held: the selection can have changed, or ended, while it was hidden.
     */
    fun setCollapsed(collapsed: Boolean) {
        uiSettings.unitInfoVisibility = !collapsed
        if (collapsed) {
            makeHidden("unit-info")
        } else {
            val unit =
                GameHolder.instance
                    ?.scenario
                    ?.map
                    ?.currentUnit
            if (unit != null) {
                makeVisible("unit-info")
                GameHolder.instance?.ui?.showUnitInfo(unit)
            }
        }
        byId("inspectunit")?.let { toggleButton(it, !collapsed) }
        refresh()
    }

    /** Syncs the chip and the dock button with the flag; safe to call whenever the flag moves. */
    fun refresh() {
        val collapsed = !uiSettings.unitInfoVisibility
        byId("osadaUnitCardRestore")?.style?.display = if (collapsed) "flex" else "none"
        byId("osadaMobileUnitCard")?.let { toggleButton(it, !collapsed) }
    }
}
