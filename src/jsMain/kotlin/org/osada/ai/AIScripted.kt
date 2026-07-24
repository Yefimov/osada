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
    internal val actions: MutableList<dynamic> = mutableListOf()

    init {
        buildTutorialActions()
    }

    @JsName("buildActions")
    fun buildActions() = buildTutorialActions()

    @JsName("getAction")
    fun getAction(): dynamic? {
        if (actions.isEmpty()) return null
        return actions.removeAt(0)
    }

    @Suppress("MagicNumber")
    private fun buildTutorialActions() {
        actions.clear()
        when (map.turn) {
            1 -> buildKhalkhinGolTurn1()
            2 -> buildKhalkhinGolTurn2()
            3 -> buildKhalkhinGolTurn3()
            else -> buildDefaultTurnActions()
        }
    }

    internal fun message(
        text: String,
        row: Int,
        col: Int,
    ) {
        addAction(ActionType.MESSAGE, arrayOf(text, Cell(row, col)))
    }

    internal fun modalMessage(
        title: String,
        body: String,
    ) {
        addAction(ActionType.MODAL_MESSAGE, arrayOf(title, body))
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
