package org.osada.model

/** Stores a single movable snapshot for undo; cleared on any irreversible action. */
internal class UndoState {
    var unit: GameUnit? = null
    var savedUnit: GameUnit? = null
    var oldOwner: Int? = null
    var oldFlag: Int? = null
    var oldVictorySide: Int? = null
    var prestigeGain: Int? = null
    var scoreGain: Int? = null

    fun clear() {
        unit = null
        savedUnit = null
        oldOwner = null
        oldFlag = null
        oldVictorySide = null
        prestigeGain = null
        scoreGain = null
    }
}
