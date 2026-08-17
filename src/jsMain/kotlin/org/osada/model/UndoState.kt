package org.osada.model

/** Why a recorded move stopped being undoable. Kept after the record itself is dropped so the
 *  action strip can explain the disappearance instead of silently removing Undo
 *  (`docs/design/action-affordances-and-objectives.md` §2). */
internal enum class UndoInvalidation {
    /** The move revealed something the player did not previously know. */
    NEW_INTELLIGENCE,

    /** The unit was caught by surprise during the move. */
    SURPRISED,

    /** Anti-air fire intercepted the move. */
    INTERCEPTED,

    /** A hidden enemy stopped the move. */
    STOPPED_BY_HIDDEN_ENEMY,

    /** Combat followed the move. */
    COMBAT,

    /** Another irreversible command followed the move. */
    IRREVERSIBLE_ACTION,
}

/** Stores a single movable snapshot for undo; cleared on any irreversible action. */
internal class UndoState {
    var unit: GameUnit? = null
    var savedUnit: GameUnit? = null
    var oldOwner: Int? = null
    var oldFlag: Int? = null
    var oldVictorySide: Int? = null
    var prestigeGain: Int? = null
    var scoreGain: Int? = null

    /** Id of the unit whose move [invalidation] explains, or -1. Survives [invalidate] on purpose:
     *  the reason is only useful once the record it describes is already gone. */
    var invalidatedUnitId: Int = -1
        private set
    var invalidation: UndoInvalidation? = null
        private set

    /** Drops the undo record for [unit] and records [reason] so the UI can say why. */
    fun invalidate(
        unit: GameUnit?,
        reason: UndoInvalidation,
    ) {
        val target = unit ?: this.unit
        if (target != null) {
            invalidatedUnitId = target.id
            invalidation = reason
        }
        this.unit = null
    }

    fun clear() {
        unit = null
        savedUnit = null
        oldOwner = null
        oldFlag = null
        oldVictorySide = null
        prestigeGain = null
        scoreGain = null
        invalidatedUnitId = -1
        invalidation = null
    }
}
