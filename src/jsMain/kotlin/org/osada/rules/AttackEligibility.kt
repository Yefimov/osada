package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.canInitiateAttackOnUnitType

/**
 * Attack-eligibility predicates (range, ammo, weather-grounding, target-type rules) shared by
 * [CombatResolver], [AttackCalculation], [CombatPositioning] and the UI/AI layers. Split out
 * purely to keep [CombatResolver] within the project's function-count/class-size limits.
 */
internal object AttackEligibility {
    /** Attack range for [unit] (min 1, plus the Marksman leader bonus). */
    fun getUnitAttackRange(unit: GameUnit): Int {
        var range = unit.unitData().gunrange
        if (range == 0) range = 1
        if (Leaders.unitHasLeader(unit, LeaderType.MARKSMAN)) range += 1
        return range
    }

    /** Air units cannot INITIATE attacks in bad weather (Overcast/Rain/Snow); they may still defend.
     *  Per the osada manual: Overcast/Raining/Snowing → "Air units can't attack". Used by the UI
     *  layer too, to explain a silently-empty attack range instead of leaving the player to guess
     *  why a plane "can't shoot" (surfaced via [GameRules.airGroundedByWeather]). */
    fun airGroundedByWeather(attacker: GameUnit): Boolean =
        UnitPredicates.isAir(attacker) && (GameHolder.instance?.scenario?.atmosferic ?: 0) != 0

    fun canInitiateAttack(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        if (attacker.destroyed || defender.destroyed) return false
        val eligible =
            !airGroundedByWeather(attacker) &&
                UnitPredicates.isEnemy(attacker, defender) &&
                Equipment.canInitiateAttackOnUnitType(attacker.getEqid(), defender.getEqid())
        return eligible && canFire(attacker, defender)
    }

    fun canFire(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val unitsUsable = !attacker.destroyed && attacker.getAmmo() > 0 && !defender.destroyed
        if (!unitsUsable) return false
        val canTargetAir = !UnitPredicates.isAir(defender) || attacker.unitData().airatk > 0
        return UnitPredicates.isEnemy(attacker, defender) && canTargetAir
    }

    fun isInAttackRange(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val aPos = attacker.getPos()
        val dPos = defender.getPos()
        if (aPos == null || dPos == null) return false
        return HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= getUnitAttackRange(attacker)
    }
}
