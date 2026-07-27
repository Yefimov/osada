@file:Suppress("MaxLineLength")

package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.canUndoMove
import org.osada.rules.GameRules
import org.osada.rules.SupplyRules
import org.osada.rules.calculateUnitCostPerStrength
import org.osada.rules.canDisembark
import org.osada.rules.canEmbark
import org.osada.rules.canMount
import org.osada.rules.canReinforce
import org.osada.rules.canResupply
import org.osada.rules.getReinforceValue
import org.osada.rules.getResupplyValue
import org.w3c.dom.events.MouseEvent

/**
 * [UnitContextMenu]'s action-chip strip (Mount/Embark/Resupply/Reinforce/Undo/Sleep). Split
 * out purely to keep [UnitContextMenu] within the project's function-count/class-size limits --
 * not expected to be called from elsewhere.
 */
internal class UnitContextButtons(
    private val ui: UI,
    private val onAction: (String, GameUnit) -> Unit,
) {
    private val contextActionLabels =
        mapOf(
            "mount" to "Mount",
            "embark" to "Embark",
            "resupply" to "Resupply",
            "reinforce" to "Reinforce",
            "overstrength" to "Overstr.",
            "undo" to "Undo",
            "sleep" to "Sleep",
        )

    fun build(
        unit: GameUnit,
        map: GameMap,
        currentPlayer: Player,
    ): Int {
        var count = 0
        if (addMountButton(unit)) count++
        if (addEmbarkButton(map, unit)) count++
        if (addResupplyButton(map, unit)) count++
        count += addReinforceButtons(map, unit, currentPlayer)
        if (addUndoButton(map, unit)) count++
        if (addSleepButton(unit)) count++
        return count
    }

    private fun addMountButton(unit: GameUnit): Boolean {
        if (!GameRules.canMount(unit)) return false
        // Mount/Dismount is ONE button; its label reflects the unit's current state (spec).
        val label = if (unit.isMounted) "Dismount" else "Mount"
        addButton(
            unit,
            "mount",
            UIBuilder.unitContextButtons["mount"] ?: "[",
            if (unit.isMounted) {
                "Dismount from organic ground transport and use the unit's own combat and movement statistics."
            } else {
                "Mount in organic ground transport. The transport's movement and vulnerability apply until dismounted."
            },
            label,
        )
        return true
    }

    private fun addEmbarkButton(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (!(GameRules.canEmbark(map, unit) || GameRules.canDisembark(map, unit))) return false
        addButton(
            unit,
            "embark",
            UIBuilder.unitContextButtons["embark"] ?: "2",
            if (unit.carrier > 0) {
                "Disembark from air or naval transport onto this valid hex."
            } else {
                "Embark into available air or naval transport. The unit cannot fight normally while transported."
            },
        )
        return true
    }

    private fun addResupplyButton(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (!GameRules.canResupply(map, unit)) return false
        val supply = GameRules.getResupplyValue(map, unit)
        val context = SupplyRules.getSupplyContext(map, unit)
        addButton(
            unit,
            "resupply",
            UIBuilder.unitContextButtons["resupply"] ?: "!",
            "Restore up to +${supply.ammo} ammo and +${supply.fuel} fuel now " +
                "(${context.label}, ${context.efficiencyPercent}% efficiency). City: 100%; outside city: 77%; " +
                "1-2 adjacent enemies: x0.67; 3 or more: x0.33.",
        )
        return true
    }

    private fun addReinforceButtons(
        map: GameMap,
        unit: GameUnit,
        currentPlayer: Player,
    ): Int {
        if (currentPlayer.prestige < GameRules.calculateUnitCostPerStrength(unit)) return 0
        var count = 0
        if (GameRules.canReinforce(map, unit, false) && GameRules.getReinforceValue(map, unit, false) > 0) {
            val strength = GameRules.getReinforceValue(map, unit, false)
            val context = SupplyRules.getSupplyContext(map, unit)
            addButton(
                unit,
                "reinforce",
                UIBuilder.unitContextButtons["reinforce"] ?: "#",
                "Restore up to +$strength strength at ${context.efficiencyPercent}% efficiency (${context.label}) " +
                    "and resupply ammo/fuel. Costs prestige and uses the unit's action.",
            )
            count++
        }
        if (GameRules.canReinforce(map, unit, true) && GameRules.getReinforceValue(map, unit, true) > 0) {
            addButton(
                unit,
                "overstrength",
                UIBuilder.unitContextButtons["overstrength"] ?: "J",
                "Raise this experienced unit above normal strength. Costs prestige, uses its action and is limited by experience.",
            )
            count++
        }
        return count
    }

    private fun addUndoButton(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (!map.canUndoMove(unit)) return false
        // The single rescue action — styled distinctly (brass border, spec).
        addButton(
            unit,
            "undo",
            UIBuilder.unitContextButtons["undo"] ?: "_",
            "Undo this unit's most recent move and restore its previous position, fuel and movement state. Unavailable after combat or another irreversible action.",
            extraClass = "osada-action--undo",
        )
        return true
    }

    /** Removes the unit from the ready-unit navigator/its count for the rest of this turn (still
     *  counted by the End Turn nag, so it can't be silently forgotten) — see TurnSleep. Offered
     *  whenever the unit could still act, whether or not it already has (a moved-but-not-fired
     *  unit can still be put to sleep). */
    private fun addSleepButton(unit: GameUnit): Boolean {
        if (!ui.hasAnyAction(unit)) return false
        val asleep = ui.isUnitAsleep(unit)
        addButton(
            unit,
            "sleep",
            UIBuilder.unitContextButtons["sleep"] ?: "t",
            if (asleep) {
                "Wake this unit so it returns to ready-unit navigation if it can still act."
            } else {
                "Skip this unit in ready-unit navigation for the rest of the turn. It is still counted by the end-turn warning."
            },
            labelOverride = if (asleep) "Wake" else "Sleep",
            extraClass = if (asleep) "osada-action--active" else "",
        )
        return true
    }

    // Labeled action chip (glyph + text) instead of a bare floating glyph button:
    // the player must never guess what a context action does.
    private fun addButton(
        unit: GameUnit,
        action: String,
        glyph: String,
        title: String,
        labelOverride: String? = null,
        extraClass: String = "",
    ) {
        val button = addTag("unit-context", "div")
        val labelText = labelOverride ?: contextActionLabels[action] ?: action
        button.className = "osada-action" + if (extraClass.isNotEmpty()) " $extraClass" else ""
        button.setAttribute("data-action", action)
        button.setAttribute("data-action-variant", labelText.lowercase())
        button.title = title
        button.setAttribute("role", "button")
        button.setAttribute("tabindex", "0")
        button.setAttribute("aria-label", labelText)
        val g = addTag(button, "span")
        g.className = "osada-action__glyph"
        g.innerHTML = glyph
        val label = addTag(button, "span")
        label.className = "osada-action__label"
        label.textContent = labelText
        button.onclick = { _: MouseEvent -> onAction(action, unit) }
        button.onkeydown = { event ->
            val key = event.asDynamic().key as? String
            if (key == "Enter" || key == " ") {
                event.preventDefault()
                onAction(action, unit)
            }
        }
    }
}
