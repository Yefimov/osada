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

    /** Set when this move ended a bridge-capable formation on a river, where it starts laying a
     *  crossing that opens next turn (`rules/PontoonBridges`), so the UI can say so on the hex. */
    var startedBridge: Boolean = false

    /** Set when AA fired on this move (`AAInterception`), whether or not the unit survived. An
     *  intercepted move must never be undoable -- see `MoveExecutor.isUndoable` -- allowing undo
     *  would let a player probe for hidden AA for free and take the probe back, destroying the
     *  entire point of hidden AA (`docs/design/efile-driven-rules.md` §3.4). */
    var wasIntercepted: Boolean = false

    /** Every AA gun that actually fired on this move, with the strength it took off. Empty unless
     *  [wasIntercepted]. The HUD raises a non-modal event from this; the combat log keeps the
     *  detail. Nothing here exists before the gun fires. */
    @JsExport.Ignore
    var interceptions: MutableList<InterceptionEvent> = mutableListOf()

    /** The enemy `Overwatch` or Camouflage Expert commander that sprang an ambush on this move
     *  (`rules/MoveAmbush`). The walk stopped there and the mover is surprised; the combat itself
     *  is fought after the move, the same way walking into a hidden unit is
     *  (`GameMap.surpriseTarget`). Undo is already refused for a surprised mover. */
    @JsExport.Ignore
    var ambusher: GameUnit? = null

    /** Set when this move walked into a minefield (`rules/Minefields`). Ends the move exactly as a
     *  detected field's movement cost already would, and blocks undo -- an undoable probe would turn
     *  a minefield into a free map of itself. */
    var hitMinefield: Boolean = false

    /** OG's airborne misdrop roll for this drop (`rules/AirborneLanding`), or null when the drop did
     *  not roll. Non-null makes the move final and drives the HUD notice. */
    @JsExport.Ignore
    var airborneLanding: org.osada.rules.AirborneLanding.Outcome? = null

    /** Strength lost to an UNDETECTED minefield on this move; 0 when the field was already known
     *  (a detected field costs movement, never strength). Reported to the HUD so movement damage is
     *  never unexplained (`docs/design/action-affordances-and-objectives.md` §1.1). */
    var minefieldLosses: Int = 0

    /** Set when the walk stopped short because it met the zone of control of an enemy its side
     *  had NOT seen (`rules/ZoneOfControlContact`) -- a contact, reported as such, with no combat. */
    var stoppedByContact: Boolean = false

    /** Set alongside [hitMinefield] when the field was previously undetected, so the HUD can say
     *  "you have walked into a minefield" rather than "you stopped at one you knew about". */
    var minefieldWasHidden: Boolean = false
}

/**
 * One exchange's outcome.
 *
 * **`defSuppress` / `atkSuppress` were deleted 2026-08-18**. They
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
     * Formation ids of the core units that fired in SUPPORT of the defender in this exchange.
     *
     * Carried on the result because the biography design's §6.3 refuses to invent an endorsement
     * from proximity: an officer may only be endorsed by one whose formation is recorded as having
     * "enabled the attack or relief", and the only place that fact exists is the support-fire list
     * [org.osada.rules.CombatResolver] built while resolving the shot. Extending this payload is
     * what §6.3 explicitly asks for in preference to searching the roster for a plausible name.
     *
     * Empty for every exchange with no support fire, and for a forecast rather than a committed
     * result — nothing downstream may treat emptiness as anything but "nobody helped".
     */
    var supportingFormationIds: List<String> = emptyList()

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

    /** The defender's RETURN fire sank the attacker outright -- the other half of `critical_hit`
     *  (*"either attacking or defending"*), which [isCriticalHit] cannot say. */
    var isAttackerCriticalHit: Boolean = false

    /**
     * Support fire hurt the attacker past the retreat threshold, so its own attack never went in --
     * Panzer Marshal's "Attack Stopped". Set by the UI orchestrator, which is where that check runs.
     * [kills] and [losses] are then zero: no exchange happened, and the loss the attacker did take
     * is [supportFireLosses].
     */
    var isAttackStopped: Boolean = false

    /**
     * Strength the attacker lost to the defender's supporting units BEFORE the exchange. Not part of
     * [losses], which is the main exchange alone; the UI adds the two for what the attacker lost in
     * total, as Panzer Marshal's `oldstr - newstr` did.
     */
    var supportFireLosses: Int = 0

    /** False when the attacker could not fire at this target at all, so it spends no shot
     *  (`RuggedCombat.resolveExchange`) -- the surprised unarmed column. */
    var attackerFired: Boolean = true
    var defLeaderGain: Boolean = false
    var atkLeaderGain: Boolean = false

    /**
     * A LEGACY leader (Panzer Marshal's bare trait on a unit outside the campaign hero system) was
     * gained by the defender / attacker in this exchange. Kept apart from [defLeaderGain] and
     * [atkLeaderGain], which mean "a hero emerged" and nothing else, so the map can say "New Leader"
     * without ever claiming a hero (`CombatLeaderAcquisition`).
     */
    var defLegacyLeaderGain: Boolean = false
    var atkLegacyLeaderGain: Boolean = false

    /** Where an evading defender went ([isEvaded]), for the map notice and the log. */
    @JsExport.Ignore
    var evasion: ForcedMove? = null
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
