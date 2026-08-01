package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.ReserveRefit
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The reserve tray's refit controls (2026-08-01): a bulk "refit all" bar above the strip and a
 * per-card button on any unit that needs one.
 *
 * Both drive [ReserveRefit], which owns the rules; this file only decides what is on screen and
 * what it says. Strength is priced, ammo and fuel are free, and a unit in the tray refits at the
 * full city rate — see that class for why.
 *
 * The two controls exist because they answer different questions. "Refit all" is the one the player
 * asks almost every battle ("put my army back together"), and making them click through fifteen
 * cards for it would be busywork. The per-card button is for the battle where prestige is short and
 * which formation gets it is the actual decision.
 */
internal object ReserveRefitPresenter {
    /** Refreshes the bulk bar. Called from the equipment-window update, so it tracks every change
     *  to prestige or unit state without needing to be poked from each of them. */
    fun refreshBar(
        ui: UI,
        player: Player,
    ) {
        val bar = byId("eqRefitBar")
        val button = byId("eqRefitAllBut")
        // Reserve tab only. The pane it lives in is shared with Upgrade, which lists the same
        // undeployed pool for a different purpose; a bulk spend button has no business appearing
        // while the player is shopping for a replacement model.
        val onReserveTab = byId("equipment")?.classList?.contains("osada-eq--reserve") == true
        val quotes =
            if (!onReserveTab) {
                emptyList()
            } else {
                ReserveRefit.refittable(player).map { ReserveRefit.quote(it) }.filter { it.isNeeded }
            }
        val totalCost = quotes.sumOf { it.strengthCost }
        val totalStrength = quotes.sumOf { it.strengthPoints }
        // Hidden rather than disabled when the tray is already at full readiness: a control that can
        // never do anything is noise, and this one sits directly above the unit strip.
        bar?.style?.display = if (quotes.isEmpty()) "none" else "flex"
        if (button == null || quotes.isEmpty()) return

        val affordable = totalCost <= player.prestige
        button.textContent =
            I18n.t(
                "equipment.refit.all.label",
                mapOf("units" to quotes.size, "cost" to totalCost),
            )
        button.title =
            I18n.t(
                if (affordable) "equipment.refit.all.help" else "equipment.refit.all.partial.help",
                mapOf("strength" to totalStrength, "cost" to totalCost, "prestige" to player.prestige),
            )
        button.classList.toggle("osada-btn--disabled", totalCost > 0 && player.prestige <= 0)
        button.asButton(ariaLabel = button.title) { refitAll(ui, player) }
    }

    /** Adds the per-card refit button to a reserve card that needs one. */
    fun addCardButton(
        ui: UI,
        container: HTMLElement,
        unit: GameUnit,
    ) {
        val player = unit.player ?: return
        val quote = ReserveRefit.quote(unit)
        if (unit.isDeployed || !quote.isNeeded) return
        val button = addTag(container, "span")
        button.className = "osada-refit-btn"
        // The price IS the label. A bare icon would make the player open something to find out what
        // the click costs, and this is the screen where prestige is being budgeted.
        button.textContent = if (quote.strengthCost > 0) "+${quote.strengthCost}" else "↻"
        button.title =
            I18n.t(
                "equipment.refit.unit.help",
                mapOf(
                    "strength" to quote.strengthPoints,
                    "cost" to quote.strengthCost,
                    "supply" to if (quote.needsSupply) I18n.t("equipment.refit.unit.supply") else "",
                ),
            )
        button.setAttribute("aria-label", button.title)
        button.onclick = { e: MouseEvent ->
            e.stopPropagation() // must not select the card out from under the rebuild below
            val applied = ReserveRefit.refit(player, unit)
            announce(applied.strengthPoints, applied.strengthCost, unaffordable = 0)
            refresh(ui, unit.unitData(true).uclass)
        }
    }

    private fun refitAll(
        ui: UI,
        player: Player,
    ) {
        val summary = ReserveRefit.refitAll(player)
        announce(summary.strengthRestored, summary.prestigeSpent, summary.unitsUnaffordable)
        refresh(ui, byId("eqUserSel")?.asDynamic()?.eqclass as? Int ?: 0)
    }

    /** One HUD line per refit. Spending prestige silently is the kind of thing players later
     *  report as "my prestige vanished". */
    private fun announce(
        strength: Int,
        cost: Int,
        unaffordable: Int,
    ) {
        if (strength <= 0 && cost <= 0) return
        HudLog.add(
            HudLog.Segment(
                I18n.t("equipment.refit.done", mapOf("strength" to strength, "cost" to cost)),
            ),
        )
        if (unaffordable > 0) {
            HudLog.add(
                HudLog.Segment(I18n.t("equipment.refit.short", mapOf("units" to unaffordable))),
            )
        }
    }

    private fun refresh(
        ui: UI,
        unitClass: Int,
    ) {
        ui.updateEquipmentWindow(unitClass)
        ui.updateStatusBar()
    }
}
