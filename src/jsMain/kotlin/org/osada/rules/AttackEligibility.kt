package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.canInitiateAttackOnUnitType
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

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

    /**
     * Air units cannot INITIATE attacks in bad weather (Overcast/Rain/Snow); they may still defend.
     * Per the osada manual: Overcast/Raining/Snowing → "Air units can't attack". Used by the UI
     * layer too, to explain a silently-empty attack range instead of leaving the player to guess
     * why a plane "can't shoot" (surfaced via [GameRules.airGroundedByWeather]).
     *
     * **[LeaderType.ALL_WEATHER_COMBAT] is the exception**, matching Open General's own wording:
     * "the impossibility of air units to attack in bad weather, *except for units with the All
     * weather leader*" (`Manual_OG-en.pdf`). Until 2026-08-17 the trait was description-only while
     * this rule was not, so a legendary commander whose signature trait reads "the air unit is not
     * affected by weather conditions" was grounded exactly like everyone else.
     *
     * The rule itself is switchable through the ruleset (`weather_grounds_aircraft`); it ships on.
     *
     * OG also grants the exception through an EQUIPMENT special (`All weather`, `equip.xeqp` bit
     * 60.2). That half stays unimplemented and is not faked here: the bit lives in
     * `Special4`/`SpecialEx`, which the importer's 24-bit `attr` cannot carry at all
     * (`DEFERRED.md` §1.5). Granting it from a unit name or class would be an invention.
     */
    fun airGroundedByWeather(attacker: GameUnit): Boolean =
        ActiveRuleset.flag(RuleKey.WEATHER_GROUNDS_AIRCRAFT, true) &&
            UnitPredicates.isAir(attacker) &&
            (GameHolder.instance?.scenario?.atmosferic ?: 0) != 0 &&
            !Leaders.unitHasLeader(attacker, LeaderType.ALL_WEATHER_COMBAT)

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

    /** Diagnostic: names the first eligibility gate that blocks [attacker] from striking
     *  [defender], or null when the attack is actually allowed. Not used by combat resolution —
     *  it exists so the click path can explain a "why can't I attack this?" case in one console
     *  line (see MapClickHandler), instead of leaving it to guesswork (DEFERRED: T-34/ZP-40). */
    fun attackBlockReason(
        attacker: GameUnit,
        defender: GameUnit,
    ): String? =
        when {
            attacker.destroyed || defender.destroyed -> "a unit is destroyed"
            !UnitPredicates.isEnemy(attacker, defender) ->
                "not an enemy (same side ${attacker.player?.side})"

            airGroundedByWeather(attacker) -> "attacker is an air unit grounded by weather"
            attacker.getAmmo() <= 0 -> "attacker is out of ammo"
            UnitPredicates.isAir(defender) && attacker.unitData().airatk <= 0 ->
                "attacker cannot target air (airatk=${attacker.unitData().airatk})"

            !Equipment.canInitiateAttackOnUnitType(attacker.getEqid(), defender.getEqid()) ->
                "target-type matrix (attacker attr=${attacker.unitData().attr}, target=${defender.unitData().target})"

            attacker.hasFired -> "attacker has already fired"
            !isInAttackRange(attacker, defender) ->
                "out of range (range=${getUnitAttackRange(attacker)})"

            else -> null
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
