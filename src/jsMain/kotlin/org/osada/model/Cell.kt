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

    /** Every AA gun that actually fired on this move, with the strength it took off. Empty unless
     *  [wasIntercepted]. The HUD raises a non-modal event from this; the combat log keeps the
     *  detail. Nothing here exists before the gun fires. */
    @JsExport.Ignore
    var interceptions: MutableList<InterceptionEvent> = mutableListOf()

    /** Set when an `Overwatch` commander fired on this move (`rules/OverwatchFire`). Unlike
     *  [wasIntercepted] it does NOT stop the walk -- overwatch is fire at something passing, not an
     *  interception -- but it must block undo for the same reason: rewinding a move that drew fire
     *  would make probing for overwatching guns free. The events themselves ride in
     *  [interceptions], which is the one channel the banner and HUD log already read. */
    var wasFiredOnWhileMoving: Boolean = false

    /** Set when this move walked into a minefield (`rules/Minefields`). Ends the move exactly as a
     *  detected field's movement cost already would, and blocks undo -- an undoable probe would turn
     *  a minefield into a free map of itself. */
    var hitMinefield: Boolean = false

    /** Strength lost to an UNDETECTED minefield on this move; 0 when the field was already known
     *  (a detected field costs movement, never strength). Reported to the HUD so movement damage is
     *  never unexplained (`DEFERRED.md` §1.1). */
    var minefieldLosses: Int = 0

    /** Set alongside [hitMinefield] when the field was previously undetected, so the HUD can say
     *  "you have walked into a minefield" rather than "you stopped at one you knew about". */
    var minefieldWasHidden: Boolean = false

    /** Set when the walk terminated because the unit became adjacent to an enemy its own side had
     *  not spotted (`MoveExecutor.stoppedByUnseenZoc`, DEFERRED.md §7.32 item 4). The move range is
     *  deliberately optimistic about unseen ZOC so the overlay cannot betray a hidden unit's
     *  position, which means this is the only signal that the unit stopped short of where the player
     *  clicked. Like [wasIntercepted] it must block undo: undoing it would let a player sweep for
     *  hidden enemies and take the probe back. */
    var stoppedByUnseenEnemy: Boolean = false
}

/**
 * One exchange's outcome.
 *
 * **`defSuppress` / `atkSuppress` were deleted 2026-08-18** (`docs/og-fidelity-plan.md` A.6). They
 * were declared, serialized by nothing and read by nothing, and their presence is what made a
 * 2026-08-18 survey conclude OSADA had no suppression at all. It has: suppression is `GameUnit.hits`
 * -- accrued in `GameUnitActions.hit`, spent as a -2 defence per point in `AttackCalculation`, and
 * cleared once per round in `GameUnitLifecycle.unitEndTurn`. Do not reintroduce a second statistic
 * here.
 */
@JsExport
@JsName("CombatResults")
class CombatResults {
    var defExpGained: Int = 0
    var atkExpGained: Int = 0
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
