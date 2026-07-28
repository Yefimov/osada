package org.osada.model

@JsExport
@JsName("Cell")
open class Cell(
    open var row: Int = 0,
    open var col: Int = 0,
) {
    fun getPos(): Cell = Cell(row, col)
}

@JsExport
@JsName("ExtendedCell")
class ExtendedCell(
    row: Int,
    col: Int,
) : Cell(row, col) {
    var cost: Int = 0
    var cout: Int = 0
    var cin: Int = 0
    var range: Int = 0
    var canPass: Boolean = false
    var canMove: Boolean = false
    var isVisible: Boolean = false
}

class PathCell(
    row: Int,
    col: Int,
) : Cell(row, col) {
    var cost: Int = 1
    var prev: PathCell? = null
    var dist: Double = Double.POSITIVE_INFINITY
}

@JsExport
@JsName("MovementResults")
class MovementResults {
    var isVisible: Boolean = false
    var surpriseCell: MutableList<Cell> = mutableListOf()
    var isVictorySide: Int = -1
    var passedCells: MutableList<Cell> = mutableListOf()
    var isCapture: Boolean = false

    /** Prestige awarded by the capture this move triggered, so the HUD can report the amount
     *  the way OG does ("You gain 40 prestige"). 0 when the move captured nothing. */
    var capturePrestige: Int = 0

    /** Set when AA fired on this move (`AAInterception`), whether or not the unit survived. An
     *  intercepted move must never be undoable -- see `MoveExecutor.isUndoable` -- allowing undo
     *  would let a player probe for hidden AA for free and take the probe back, destroying the
     *  entire point of hidden AA (docs/design/aa-interception.md §3.4). */
    var wasIntercepted: Boolean = false

    /** Set when the walk terminated because the unit became adjacent to an enemy its own side had
     *  not spotted (`MoveExecutor.stoppedByUnseenZoc`, DEFERRED.md §7.32 item 4). The move range is
     *  deliberately optimistic about unseen ZOC so the overlay cannot betray a hidden unit's
     *  position, which means this is the only signal that the unit stopped short of where the player
     *  clicked. Like [wasIntercepted] it must block undo: undoing it would let a player sweep for
     *  hidden enemies and take the probe back. */
    var stoppedByUnseenEnemy: Boolean = false
}

@JsExport
@JsName("CombatResults")
class CombatResults {
    var defExpGained: Int = 0
    var atkExpGained: Int = 0
    var defSuppress: Int = 0
    var atkSuppress: Int = 0
    var losses: Int = 0
    var kills: Int = 0
    var defcanfire: Boolean = true
    var isRugged: Boolean = false
    var isOverrun: Boolean = false
    var defLeaderGain: Boolean = false
    var atkLeaderGain: Boolean = false
}

@JsExport
@JsName("Supply")
class Supply(
    var ammo: Int = 0,
    var fuel: Int = 0,
    var transportAmmo: Int = 0,
    var transportFuel: Int = 0,
)

@JsExport
@JsName("ScreenPos")
class ScreenPos(
    var x: Double = 0.0,
    var y: Double = 0.0,
)
