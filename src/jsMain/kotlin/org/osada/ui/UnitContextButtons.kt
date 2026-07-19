package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.canUndoMove
import org.osada.rules.GameRules
import org.osada.rules.calculateUnitCostPerStrength
import org.osada.rules.canDisembark
import org.osada.rules.canEmbark
import org.osada.rules.canMount
import org.osada.rules.canReinforce
import org.osada.rules.canResupply
import org.w3c.dom.events.MouseEvent

/**
 * [UnitContextMenu]'s action-chip strip (Mount/Embark/Resupply/Reinforce/Undo/Sleep). Split out
 * purely to keep [UnitContextMenu] within the project's function-count/class-size limits -- not
 * expected to be called from elsewhere.
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
            "Mount/Umount this unit in/from a transport",
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
            "Embark/DisEmbark this unit in/from a air/naval transport",
        )
        return true
    }

    private fun addResupplyButton(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (!GameRules.canResupply(map, unit)) return false
        addButton(
            unit,
            "resupply",
            UIBuilder.unitContextButtons["resupply"] ?: "!",
            "Resupply Ammo and Fuel for this unit",
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
        if (GameRules.canReinforce(map, unit, false)) {
            addButton(unit, "reinforce", UIBuilder.unitContextButtons["reinforce"] ?: "#", "Reinforce unit strength")
            count++
        }
        if (GameRules.canReinforce(map, unit, true)) {
            addButton(unit, "overstrength", UIBuilder.unitContextButtons["overstrength"] ?: "J", "Overstrength unit")
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
            "Undo last move",
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
            if (asleep) "Wake this unit" else "Put this unit to sleep for the rest of the turn",
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
        button.className = "osada-action" + if (extraClass.isNotEmpty()) " $extraClass" else ""
        button.title = title
        val g = addTag(button, "span")
        g.className = "osada-action__glyph"
        g.innerHTML = glyph
        val label = addTag(button, "span")
        label.className = "osada-action__label"
        label.textContent = labelOverride ?: contextActionLabels[action] ?: action
        button.onclick = { _: MouseEvent -> onAction(action, unit) }
    }
}
