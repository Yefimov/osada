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

    /** The authored message of an OG trigger hex this move set off (`rules/TriggerHexes`), or null.
     *  Only 33 of the corpus's 850 triggers carry text, so a silent trigger is the normal case and
     *  leaves this null. */
    var triggerMessage: String? = null

    /** Prestige actually awarded by an OG prestige trigger on the destination hex. Kept separate
     *  from [capturePrestige]: an authored trigger replaces the legacy flag-capture award, while a
     *  victory objective may still carry its own capture award. */
    var triggerPrestige: Int = 0

    /** Set when this move fired a trigger hex at all, message or not. Like [wasIntercepted] it
     *  must make the move final: every trigger action is a one-off GIFT, and undo would otherwise
     *  let the player keep the prestige, the leader or the free formation and take the move back. */
    var firedTrigger: Boolean = false

    /** Set when this move took the formation OFF the map through an OG escape hex
     *  (`rules/ExtendedVictory`). The unit no longer exists on the board, so the UI must not try
     *  to select it, scroll to it or show its action row afterwards. */
    var withdrew: Boolean = false

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

    /**
     * The defender slipped the attack entirely — OG's `Evade` (`rules/Evade`, 2026-08-27).
     *
     * Set only on the COMMITTED result, never on a forecast: the roll draws from the shared random
     * stream, which a preview may not advance. So the player sees the ordinary prediction and this
     * flag is how the outcome is announced afterwards, exactly as [isRugged] is.
     */
    var isEvaded: Boolean = false

    /**
     * The attacker kept its movement after clearing the hex — OG's `Exploit Success`
     * (`CombatApplication.applyExploitSuccess`, 2026-08-27).
     *
     * Reported rather than inferred from the unit's flags, so the UI can say why a formation that
     * has just attacked may still walk. Unlike [isOverrun] the attack itself is still spent.
     */
    var isExploit: Boolean = false

    /**
     * The attacker's saboteurs got in and the battle never happened — OG's `Saboteur`
     * (`CombatApplication.applySabotage`, 2026-08-27).
     *
     * Like [isEvaded] this is set only on the COMMITTED result: the attempt draws from the shared
     * random stream, which a forecast may not advance.
     */
    var isSabotage: Boolean = false

    /**
     * A naval shot sank its target outright — OG's `critical_hit` (`rules/CriticalHit`, 2026-08-28).
     *
     * Set only on the COMMITTED result, for the same reason [isEvaded] and [isSabotage] are: the
     * roll draws from the shared random stream, which a forecast may not advance. It is reported
     * rather than inferred from the strength loss, because "the last two points happened to be
     * enough" and "this ship was sunk by a magazine hit" are different sentences to show a player.
     */
    var isCriticalHit: Boolean = false
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
