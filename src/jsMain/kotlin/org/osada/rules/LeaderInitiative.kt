package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitType
import org.osada.model.GameUnit
import org.osada.model.Leaders

/**
 * The leader rules that act on Open General's initiative exchange (OG manual 6.10; the author's
 * combat page), kept out of `AttackCalculation.applyInitiativeBonus` so that function stays a
 * sequence of steps rather than a list of exceptions.
 */
object LeaderInitiative {
    /** OG's Infiltration Tactics beats First Strike only when it wins initiative by MORE than 1. */
    private const val INFILTRATION_MARGIN = 1

    /** OG's Battlefield Intelligence: *"Cannot be surprised, +1 initiative"* -- the +1 is the
     *  attacker's (*"if Attacker has leader BattleFieldIntel, then: AttackerIni is increased by 1"*,
     *  the author's combat page). */
    fun attackerBonus(attacker: GameUnit): Int =
        if (Leaders.unitHasLeader(attacker, LeaderType.BATTLEFIELD_INTELLIGENCE)) 1 else 0

    /**
     * OSADA's Armoured Hunter (a Heroic tank promotion, owner 2026-10-08): +1 initiative for [unit]
     * whenever its opponent is an armoured (HARD) target, attacking or defending. [opponentTarget]
     * is the OTHER side's target type, the same cross-indexing every combat stat uses.
     */
    fun armouredHunterBonus(
        unit: GameUnit,
        opponentTarget: Int,
    ): Int =
        if (opponentTarget == UnitType.HARD.value && Leaders.unitHasLeader(unit, LeaderType.ARMOURED_HUNTER)) 1 else 0

    /**
     * Whether the defender's First Strike reverses the exchange. OG's Infiltration Tactics:
     * *"Ignores entrenchment and First Strike if initiative is won by margin >1"* (`* Leaders`
     * list) -- an infiltrating attacker that out-initiatives the defender by two or more keeps the
     * first shot. [rawDiff] is attacker minus defender, after any attacker First Strike.
     */
    fun defenderFirstStrikeHolds(
        attacker: GameUnit,
        defender: GameUnit,
        rawDiff: Int,
    ): Boolean {
        val infiltrated =
            Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS) && rawDiff > INFILTRATION_MARGIN
        return Leaders.unitHasLeader(defender, LeaderType.FIRST_STRIKE) && !infiltrated
    }
}
