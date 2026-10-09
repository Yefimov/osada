package org.osada.rules

import org.osada.LeaderType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.CombatResults
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.ignoresEntrenchment
import org.osada.rules.AttackCalculation.CombatContext
import org.osada.rules.AttackCalculation.CombatStats

/**
 * OG's **Rugged** combat mode, which replaced Panzer Marshal's surprise / rugged-defence penalty on
 * 2026-09-26 (the owner's call: OG's rule is the better-designed one, so it is the rule, not a
 * ruleset option).
 *
 * Source: `luis-guzman.com/OpenGen_Combat.html`, Eval Odds and the combat-sequence routine. Split
 * from [AttackCalculation] and [CombatResolver], whose other steps it plugs into, to keep both
 * within the project's function-count limits.
 */
internal object RuggedCombat {
    /** OG's flat Rugged swing -- the same +4 on every row of [applyRuggedCombat] that has one. */
    private const val RUGGED_BONUS = 4

    /** *"if A is Arty-class and Rugged, then A.EffDef += 3"*. */
    private const val RUGGED_ARTILLERY_DEFENSE_BONUS = 3

    // The rugged-defence chance, [ruggedDefenceChance]'s numbers.
    private const val PERCENT = 100
    private const val WEIGHT_SCALE = 50.0
    private const val CHANCE_MULTIPLIER = 5.0
    private const val LIGHT = 2.0
    private const val HEAVY = 4.0

    /**
     * Whether this exchange is fought as OG's **Rugged** combat. OG has three ways in and one mode
     * -- *"Rugged: Either if CloseCombat, RuggedDefense or Surprised, as all of this affect the same
     * in combat"* (`luis-guzman.com/OpenGen_Combat.html`) -- and so does this engine since
     * 2026-09-26:
     *
     * - **surprised** -- the attacker walked into a hidden enemy (`GameMap.surpriseTarget`);
     * - **close combat** -- *"Attacker is Infantry unit .. and .. Defender is on City, Forest,
     *   Mountain or it's class Fortification"*, which is [closeCombat] as
     *   [AttackCalculation.applyCloseCombat] already decides it;
     * - **rugged defence** -- [ruggedDefenceRaised], rolled by the caller exactly once.
     *
     * Decided BEFORE the modifiers rather than after them, because in OG it is an input to several
     * of them (entrenchment, weather, range defence, initiative), not a penalty applied on top.
     */
    fun isRuggedCombat(
        attacker: GameUnit,
        closeCombat: Boolean,
        ruggedDefence: Boolean,
    ): Boolean = attacker.isSurprised || closeCombat || ruggedDefence

    /**
     * OG's *Rugged defense procedure*, which replaced Panzer Marshal's deterministic
     * experience-and-entrenchment threshold on 2026-09-26:
     *
     * > *"If Attacker is not surprised and both Attacker and Defender are Infantry units, a "rugged"
     * > defense can be triggered. But if Defender has not "Ferocious Defender" leader and: Attacker
     * > has "Ignore entrench" special attribute ... or "Infiltration Tactics" leader ... or "Street
     * > Fighter" leader and defender terrain is City or Port ... then Attacker avoids "rugged"
     * > defense."* -- and the combat order adds *"both are in adjacent hexes and Defender has ammo"*.
     *
     * The chance is [ruggedDefenceChance]. **A die roll, so only on the [committed] call**: the
     * shared stream (`rules/GameRandomSource`) may only advance on the combat that is applied, or two
     * multiplayer peers drift apart. A forecast therefore never predicts a rugged defence -- it is
     * announced when it happens ("Rugged Defense" over the attacker), the same way an evade is.
     */
    fun ruggedDefenceRaised(
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        committed: Boolean,
    ): Boolean {
        val chance = if (committed) ruggedDefenceOdds(attacker, defender, context) ?: 0.0 else 0.0
        // No draw when the defence cannot rise at all (not committed, not eligible, or no
        // entrenchment): both peers skip it alike.
        return chance > 0 && GameRandomSource.nextInt(PERCENT) + 1 <= chance
    }

    /**
     * The percentage chance this exchange raises a rugged defence, or `null` when OG's conditions
     * rule it out entirely -- the part of [ruggedDefenceRaised] that draws nothing, so the unit card
     * can state the same odds combat will roll against (`ui/EntrenchmentTooltip`).
     */
    fun ruggedDefenceOdds(
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
    ): Double? {
        val eligible =
            !attacker.isSurprised &&
                context.attackerData.uclass == UnitClass.INFANTRY.value &&
                context.defenderData.uclass == UnitClass.INFANTRY.value &&
                context.distance == 1 &&
                defender.getAmmo() > 0 &&
                !avoidsRuggedDefence(attacker, defender, context)
        return if (eligible) ruggedDefenceChance(attacker, defender) else null
    }

    /**
     * OG's formula, verbatim: *"Attacker weight is 4 if Attacker has "Tenacious Defense" leader, else
     * is 2; Defender weight [likewise]; Weight factor is = 50 * (Defender weight) / (Attacker
     * weight); TrenchRate = Infantry's Entrench rate + 1; Chance = ( 5 * Weight factor * TrenchRate *
     * Defender's entrench) / (50 * TrenchRate); if Dice(1, 100) <= Chance then "rugged" defense is
     * raised"*. TrenchRate cancels out, so it is not read: the chance is 5% per entrenchment level,
     * doubled by a Tenacious defender and halved by a Tenacious attacker.
     */
    fun ruggedDefenceChance(
        attacker: GameUnit,
        defender: GameUnit,
    ): Double {
        val attackerWeight = if (Leaders.unitHasLeader(attacker, LeaderType.TENACIOUS_DEFENSE)) HEAVY else LIGHT
        val defenderWeight = if (Leaders.unitHasLeader(defender, LeaderType.TENACIOUS_DEFENSE)) HEAVY else LIGHT
        val weightFactor = WEIGHT_SCALE * defenderWeight / attackerWeight
        return CHANCE_MULTIPLIER * weightFactor * defender.entrenchment / WEIGHT_SCALE
    }

    /** OG's list of attackers that do not meet a rugged defence -- the same three it names for
     *  ignoring entrenchment -- unless the defender carries `Ferocious Defense`. Bunker Buster
     *  counts only against a bunker (`rules/BunkerBuster`). */
    private fun avoidsRuggedDefence(
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
    ): Boolean {
        if (Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)) return false
        val cityOrPort =
            context.dTerrain == TerrainType.CITY.value || context.dTerrain == TerrainType.PORT.value
        return Equipment.ignoresEntrenchment(attacker.eqid) ||
            BunkerBuster.busts(attacker, defender, context.dTerrain) ||
            Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS) ||
            (cityOrPort && Leaders.unitHasLeader(attacker, LeaderType.STREET_FIGHTER))
    }

    /**
     * OG's Eval Odds rows for a Rugged exchange, quoted from the author's combat page with A the
     * attacker and P the defender:
     *
     * - *"If P is Inf-class and Rugged, A.EffDef = A close defense stat"*, and *"If A is Inf-class
     *   and P is (Fort-class or Rugged) then P.EffDef = P close defense stat"*;
     * - *"If Rugged and P is not Inf-class, A.EffAtk += 4"* and *"If Rugged and A is not
     *   Inf-class, P.EffAtk += 4"*;
     * - *"if A is Arty-class and Rugged, then A.EffDef += 3"*;
     * - *"If Rugged ... then P.EffAtk +=4 and P.EffDef +=4"*.
     *
     * Replaces Panzer Marshal's rule (attacker defence zeroed, attack halved), which wiped out the
     * whole defence of a surprised formation rather than handing the defender the advantage.
     * Applied where the base stats are chosen, right after [AttackCalculation.applyCloseCombat], so every later bonus
     * builds on the close-defence value exactly as OG's `+= bars` rows do. Where close combat has
     * already given an attacking infantryman its +4 against a non-infantry target, it is not given
     * twice -- in OG that bonus IS this row.
     */
    fun applyRuggedCombat(
        stats: CombatStats,
        context: CombatContext,
        closeCombat: Boolean,
    ) {
        val attackerIsInfantry = context.attackerData.uclass == UnitClass.INFANTRY.value
        val defenderIsInfantry = context.defenderData.uclass == UnitClass.INFANTRY.value
        if (defenderIsInfantry) stats.attackerDefense = context.attackerData.closedef
        if (attackerIsInfantry) stats.defenderDefense = context.defenderData.closedef
        if (!defenderIsInfantry && !closeCombat) stats.attackerAttack += RUGGED_BONUS
        if (!attackerIsInfantry) stats.defenderAttack += RUGGED_BONUS
        if (context.attackerData.uclass == UnitClass.ARTILLERY.value) {
            stats.attackerDefense += RUGGED_ARTILLERY_DEFENSE_BONUS
        }
        stats.defenderAttack += RUGGED_BONUS
        stats.defenderDefense += RUGGED_BONUS
    }

    /**
     * Both shots of the main exchange. Simultaneous by default, as in Panzer Marshal.
     *
     * [defenderFirst] is OG's DEFENDER-FIRST sequence, which a Rugged exchange always gets: the
     * defender's shot lands before the attacker's, so the attacker answers only with the strength
     * it has left. A defender that cannot fire back leaves the sequence simultaneous in effect,
     * since nothing lands first.
     */
    @Suppress("LongParameterList")
    fun resolveExchange(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
        halveStrength: Boolean,
        result: CombatResults,
        defenderFirst: Boolean,
    ) {
        if (result.defcanfire) {
            result.losses =
                CombatResolver.attackValue(stats.defenderAttack, stats.attackerDefense, defender, attacker, useRandom)
        }
        val lostBeforeFiring = if (defenderFirst) result.losses else 0
        // A formation that cannot fire at this target at all -- a column on its trucks that drove
        // into a hidden enemy -- answers with nothing. OG keeps the combat going for exactly this
        // case: *"If resultant P.EffAtk is zero and it is Rugged, no more stats are calculated"* is
        // the only zero-attack abort, and a surprise is Rugged, so the defender still gets its
        // shot. Every ordinary attack has already passed `canInitiateAttack`, so this changes
        // nothing for them.
        // A surprised mover answers an ambush only if it can REACH the ambusher -- an Overwatch gun
        // may have opened fire from three hexes away. Every ordinary attack is in range already.
        result.attackerFired =
            AttackEligibility.canFire(attacker, defender) &&
            (!attacker.isSurprised || AttackEligibility.isInAttackRange(attacker, defender))
        if (!result.attackerFired) return
        result.kills =
            CombatResolver.attackValue(
                stats.attackerAttack,
                stats.defenderDefense,
                attacker,
                defender,
                useRandom,
                halveStrength,
                strengthLost = lostBeforeFiring,
            )
    }
}
