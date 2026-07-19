package org.osada.ai

import org.osada.ActionType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player

class AIScripted(
    internal val player: Player,
    internal val map: GameMap,
) {
    // Internal (not private): AIScriptedHelpers.kt's per-turn extension functions (moved out of
    // buildTutorialActions to keep it under detekt's LongMethod limit) add to this from another file.
    internal val actions: MutableList<dynamic> = mutableListOf()

    companion object {
        // buildTutorialActions() turn numbers -- narrated tutorial steps, not gameplay balance.
        private const val TUTORIAL_TURN_REINFORCE_AND_ADVANCE = 3
        private const val TUTORIAL_TURN_FINAL_ASSAULT = 4
    }

    init {
        buildTutorialActions()
    }

    @JsName("buildActions")
    fun buildActions() {
        buildTutorialActions()
    }

    @JsName("getAction")
    fun getAction(): dynamic? {
        if (actions.isEmpty()) return null
        return actions.removeAt(0)
    }

    private fun buildTutorialActions() {
        actions.clear()

        val axisUnits = mutableMapOf<String, GameUnit?>()
        val alliesUnits = mutableMapOf<String, GameUnit?>()
        axisUnits["recon"] = unitAt(row = 8, col = 12)
        axisUnits["legioninf"] = unitAt(row = 4, col = 2)
        axisUnits["pz2a"] = unitAt(row = 9, col = 6)
        axisUnits["ssinf1"] = unitAt(row = 8, col = 6)
        axisUnits["ssinf2"] = unitAt(row = 9, col = 5)
        axisUnits["ssinf3"] = unitAt(row = 9, col = 4)
        axisUnits["arty"] = unitAt(row = 8, col = 4)
        alliesUnits["inf1"] = unitAt(row = 8, col = 15)

        when (map.turn) {
            1 -> buildTurn1Actions(axisUnits, alliesUnits)
            2 -> buildTurn2Actions(axisUnits, alliesUnits)
            TUTORIAL_TURN_REINFORCE_AND_ADVANCE -> buildTurn3Actions()
            TUTORIAL_TURN_FINAL_ASSAULT -> buildTurn4Actions()
            else -> buildDefaultTurnActions()
        }
    }

    // Internal (not private): AIScriptedHelpers.kt's per-turn extension functions (the
    // buildTutorialActions LongMethod split) call these from another file.
    internal fun message(
        text: String,
        row: Int,
        col: Int,
    ) {
        addAction(ActionType.MESSAGE, arrayOf(text, Cell(row, col)))
    }

    internal fun select(unit: GameUnit?) {
        unit?.let { addAction(ActionType.SELECT, arrayOf(it)) }
    }

    internal fun move(
        unit: GameUnit?,
        row: Int,
        col: Int,
    ) {
        unit?.let { addAction(ActionType.MOVE, arrayOf(it, Cell(row, col))) }
    }

    internal fun attack(
        attacker: GameUnit?,
        defender: GameUnit?,
    ) {
        if (attacker != null && defender != null) {
            addAction(ActionType.ATTACK, arrayOf(attacker, defender))
        }
    }

    internal fun mount(unit: GameUnit?) {
        unit?.let { addAction(ActionType.MOUNT, arrayOf(it)) }
    }

    internal fun reinforce(unit: GameUnit?) {
        unit?.let { addAction(ActionType.REINFORCE, arrayOf(it)) }
    }

    internal fun resupply(unit: GameUnit?) {
        unit?.let { addAction(ActionType.RESUPPLY, arrayOf(it)) }
    }
}
