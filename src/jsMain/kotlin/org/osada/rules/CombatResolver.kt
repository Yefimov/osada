package org.osada.rules

import org.osada.LeaderType
import org.osada.TerrainType
import org.osada.UNIT_RETREAT_THRESHOLD
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.ignoresEntrenchment
import org.osada.unitEntrenchRate
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Combat resolution: damage rolls, the full cross-indexed attack/defence calculation,
 * support fire, retreat and the firing/range predicates.
 *
 * The heart of the rules engine, extracted from the former `GameRules` god-object.
 * Depends on [HexGeometry] (distance/rings), [UnitPredicates] (unit classification),
 * [Leaders] and [Equipment]. Faithful port of `osada.js`
 * (`calculateAttackResults` ~line 2333 and the `f`/`attackValue` roll ~line 2029);
 * the cross-indexing and rounding subtleties are documented inline.
 */
object CombatResolver {
    // attackValue's compressed-net-attack curve: net attack power beyond COMPRESSION_PIVOT is
    // flattened onto a shallower slope (COMPRESSION_SLOPE_NUMERATOR/COMPRESSION_SLOPE_DIVISOR),
    // offset by COMPRESSION_OFFSET, so overwhelming attack/defense mismatches don't runaway.
    private const val COMPRESSION_PIVOT = 4.0
    private const val COMPRESSION_SLOPE_NUMERATOR = 2
    private const val COMPRESSION_OFFSET = 8
    private const val COMPRESSION_SLOPE_DIVISOR = 5

    // Default d20 hit threshold; raised to SPECIAL_TARGET_HIT_THRESHOLD for the
    // artillery/bomber/fortification/naval "target=19" rule (see calculateAttackResults doc).
    private const val DEFAULT_HIT_THRESHOLD = 15
    private const val SPECIAL_TARGET_HIT_THRESHOLD = 19

    // Expected-value (useRandom) path: q is p re-based onto the d20 roll scale, clamped to
    // [1, DICE_MAX_ROLL - 1], then kills = (EV_MULTIPLIER * q * strength + EV_ROUNDING_OFFSET)
    // / EV_SCALE mirrors the JS half-up-rounded expected value over `strength` d20 rolls.
    private const val ROLL_TO_HIT_OFFSET = 21
    private const val EV_ROLL_MIN = 1.0
    private const val EV_ROLL_MAX = 19.0
    private const val EV_MULTIPLIER = 5
    private const val EV_ROUNDING_OFFSET = 50
    private const val EV_SCALE = 100

    // Dice-roll path: a d20 roll of exactly 1 or DICE_MAX_ROLL is a fixed miss/hit (no modifier).
    private const val DICE_MIN_ROLL = 1.0
    private const val DICE_MAX_ROLL = 20.0

    // internal (not private): also used by AttackCalculation's experience-gain step.
    internal const val FULL_STRENGTH = 10.0
    private const val FULL_STRENGTH_INT = 10

    // isRuggedDefense's entrenchment-ratio trigger threshold and scale (both conceptually the
    // same "50" from the JS reference, kept as separate typed constants since one side of the
    // comparison is Int and the other Double).
    private const val RUGGED_DEFENSE_THRESHOLD = 50
    private const val RUGGED_DEFENSE_SCALE = 50.0
    private const val RUGGED_DEFENSE_MULTIPLIER = 5.0

    /**
     * Core damage roll. Mirrors the legacy `f(p, r, b, m, k)` combat function:
     * the attack power has the target's defense subtracted from it, the result is
     * compressed, then either an expected value (useRandom) or per-strength dice
     * rolls are computed against a hit threshold. [attacker]'s strength and class
     * drive the roll; [defender] supplies the resilience leader check.
     */
    internal fun attackValue(
        attackPower: Int,
        defense: Int,
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
    ): Int {
        val attackerClass = attacker.unitData().uclass
        var p = (attackPower - defense).toDouble()
        var target = DEFAULT_HIT_THRESHOLD
        if (p > COMPRESSION_PIVOT) {
            p = COMPRESSION_PIVOT + (COMPRESSION_SLOPE_NUMERATOR * p - COMPRESSION_OFFSET) / COMPRESSION_SLOPE_DIVISOR
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.OVERWHELMING_ATTACK)) p += 2
        if (Leaders.unitHasLeader(defender, LeaderType.RESILIENCE)) p -= 2
        if (usesSpecialHitThreshold(attackerClass, attacker, defender)) {
            target = SPECIAL_TARGET_HIT_THRESHOLD
        }
        var kills = 0.0
        if (useRandom) {
            var q = p + ROLL_TO_HIT_OFFSET - target
            if (q < EV_ROLL_MIN) q = EV_ROLL_MIN
            if (q > EV_ROLL_MAX) q = EV_ROLL_MAX
            kills = (EV_MULTIPLIER * q * attacker.strength + EV_ROUNDING_OFFSET) / EV_SCALE
        } else {
            for (i in 0 until attacker.strength) {
                var roll = ((Random.nextDouble() * DICE_MAX_ROLL).toInt() + 1).toDouble()
                if (roll > DICE_MIN_ROLL && roll < DICE_MAX_ROLL) roll += p
                if (roll >= target) kills += 1
            }
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.OVERWHELMING_ATTACK)) kills += 1
        // roundToInt() rounds ties toward +infinity, matching JS Math.round
        // (kotlin.math.round uses banker's/half-to-even rounding — wrong here).
        return kills.roundToInt()
    }

    /** Artillery/bomber/fortification, or a naval attacker vs a non-naval defender, roll against
     *  the higher [SPECIAL_TARGET_HIT_THRESHOLD] instead of [DEFAULT_HIT_THRESHOLD]. */
    private fun usesSpecialHitThreshold(
        attackerClass: Int,
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean =
        attackerClass == UnitClass.ARTILLERY.value ||
            attackerClass == UnitClass.LEVEL_BOMBER.value ||
            attackerClass == UnitClass.FORTIFICATION.value ||
            (UnitPredicates.isSea(attacker) && !UnitPredicates.isSea(defender))

    fun calculateAttackResults(
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
    ): CombatResults {
        val result = CombatResults()
        val context =
            if (attacker == null || defender == null) {
                null
            } else {
                AttackCalculation.resolveCombatContext(attacker, defender)
            }
        if (context == null) return result

        val stats = AttackCalculation.resolveCrossIndexedStats(context)
        val closeCombat = AttackCalculation.applyCloseCombat(stats, context)
        AttackCalculation.applyLeaderBonuses(stats, attacker, defender, context)
        AttackCalculation.applyTerrainBonuses(stats, context)
        AttackCalculation.applyEntrenchment(stats, attacker, defender, context, closeCombat)
        AttackCalculation.applyExperienceAndHitsPenalty(stats, attacker, defender, context)
        AttackCalculation.applyRangeDefenseModifier(stats, attacker, defender, context, closeCombat)
        AttackCalculation.applyInitiativeBonus(stats, attacker, defender, context)
        AttackCalculation.resolveRuggedSurpriseAndFireEligibility(stats, attacker, defender, context, result)

        result.kills = attackValue(stats.attackerAttack, stats.defenderDefense, attacker, defender, useRandom)
        if (result.defcanfire) {
            result.losses = attackValue(stats.defenderAttack, stats.attackerDefense, defender, attacker, useRandom)
        }

        AttackCalculation.resolveOverrunAndExperienceGain(stats, attacker, defender, context, result)
        return result
    }

    /**
     * Aggregates the main attack with any defender support fire into a single result.
     * [full] returns the true totals; otherwise only casualties from units visible to
     * the attacking side are reported (fog of war).
     */
    fun calculateCombatResults(
        attacker: GameUnit,
        defender: GameUnit,
        units: List<GameUnit>,
        full: Boolean,
        useRandom: Boolean,
    ): CombatResults {
        val result = CombatResults()
        val attackerSide = if (attacker == null || defender == null) null else attacker.player?.side
        if (attackerSide == null) return result
        val supportUnits = if (!attacker.isSurprised) getSupportFireUnits(units, attacker, defender) else emptyList()
        var totalKills = 0
        var totalLosses = 0
        var visibleKills = 0
        var visibleLosses = 0

        supportUnits.forEach { support ->
            val supportHex = support.getHex() ?: return@forEach
            val supportResult = calculateAttackResults(support, attacker, useRandom)
            if (supportHex.isSpotted(attackerSide) || support.tempSpotted) {
                visibleKills += supportResult.kills
                visibleLosses += supportResult.losses
            }
            totalKills += supportResult.kills
            totalLosses += supportResult.losses
        }

        val mainResult = calculateAttackResults(attacker, defender, useRandom)
        totalKills += mainResult.losses
        totalLosses += mainResult.kills
        visibleKills += mainResult.losses
        visibleLosses += mainResult.kills

        if (full) {
            result.losses = totalKills
            result.kills = totalLosses
        } else {
            result.losses = minOf(visibleKills, attacker.strength)
            result.kills = minOf(visibleLosses, defender.strength)
        }
        result.defcanfire = mainResult.defcanfire
        result.isOverrun = mainResult.isOverrun
        result.isRugged = mainResult.isRugged
        return result
    }

    /** Adjacent friendly units that fire in support of [defender] against [attacker]. */
    fun getSupportFireUnits(
        units: List<GameUnit>,
        attacker: GameUnit,
        defender: GameUnit,
    ): List<GameUnit> {
        val aPos = attacker.getPos()
        val dPos = defender.getPos()
        val defenderSide = defender.player?.side
        val eligible =
            aPos != null &&
                dPos != null &&
                defenderSide != null &&
                HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= 1
        if (!eligible || aPos == null || dPos == null) return emptyList()
        return units.filter { support -> isSupportFireEligible(support, attacker, defender, defenderSide, aPos) }
    }

    /** Whether [support] is a friendly, in-range, class-appropriate (Flak/AD/Fighter vs an air
     *  attacker, Artillery vs a ground one) unit that can itself fire on [attacker]. */
    private fun isSupportFireEligible(
        support: GameUnit,
        attacker: GameUnit,
        defender: GameUnit,
        defenderSide: Int?,
        aPos: Cell,
    ): Boolean {
        val isFriendlyNotDefender = support.player?.side == defenderSide && support.id != defender.id
        val sPos = support.getPos()
        if (!isFriendlyNotDefender || sPos == null) return false
        val sData = support.unitData()
        val range = if (sData.gunrange == 0) 1 else sData.gunrange
        val inRange = HexGeometry.distance(sPos.row, sPos.col, aPos.row, aPos.col) <= range
        val classEligible =
            if (UnitPredicates.isAir(attacker)) {
                sData.uclass == UnitClass.FLAK.value ||
                    sData.uclass == UnitClass.AIR_DEFENCE.value ||
                    sData.uclass == UnitClass.FIGHTER.value
            } else {
                sData.uclass == UnitClass.ARTILLERY.value
            }
        return inRange && classEligible && AttackEligibility.canInitiateAttack(support, attacker)
    }

    /** Whether a unit losing [current] of [original] strength is past its retreat threshold. */
    fun isLossOverRetreatThreshold(
        current: Int,
        original: Int,
    ): Boolean =
        (original - current).toDouble() / original >=
            UNIT_RETREAT_THRESHOLD - (FULL_STRENGTH_INT - original) / 2.0 / FULL_STRENGTH

    /** Whether [defender] should retreat after taking losses (ground-vs-ground only). */
    fun shouldDefenderRetreat(
        attacker: GameUnit,
        defender: GameUnit,
        originalStrength: Int,
    ): Boolean {
        val bothGround = UnitPredicates.isGround(attacker) && UnitPredicates.isGround(defender)
        if (!bothGround) return false
        val defenderClass = defender.unitData().uclass
        val immuneToRetreat =
            defenderClass == UnitClass.ARTILLERY.value ||
                defenderClass == UnitClass.TACTICAL_BOMBER.value ||
                defenderClass == UnitClass.FORTIFICATION.value
        return !immuneToRetreat && isLossOverRetreatThreshold(defender.strength, originalStrength)
    }

    /** Whether the defender's entrenchment is strong enough to trigger a rugged defense. */
    fun isRuggedDefense(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        var aExp = attacker.experience / 100 + 2
        var dExp = defender.experience / 100 + 2
        if (Leaders.unitHasLeader(attacker, LeaderType.TENACIOUS_DEFENSE)) aExp += 2
        if (Leaders.unitHasLeader(defender, LeaderType.TENACIOUS_DEFENSE)) dExp += 2
        val aRate = unitEntrenchRate[attacker.unitData().uclass] + 1
        val dRate = unitEntrenchRate[defender.unitData().uclass] + 1
        // Floating-point throughout, as in the JS reference (integer division here
        // would truncate intermediate results and change the trigger threshold).
        return RUGGED_DEFENSE_THRESHOLD <
            RUGGED_DEFENSE_MULTIPLIER * (RUGGED_DEFENSE_SCALE * dExp / aExp) * dRate * defender.entrenchment /
            (RUGGED_DEFENSE_THRESHOLD * aRate)
    }

    /** Whether [defender]'s entrenchment still applies against [attacker] in [terrain]. */
    fun isEntrenchmentIntact(
        attacker: GameUnit,
        defender: GameUnit,
        terrain: Int,
    ): Boolean {
        if (Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)) return true
        val hasEntrenchmentBypassLeader =
            Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS) ||
                Leaders.unitHasLeader(attacker, LeaderType.STREET_FIGHTER)
        val isEntrenchmentVulnerableTerrain =
            terrain == TerrainType.CITY.value ||
                terrain == TerrainType.ROUGH.value ||
                terrain == TerrainType.PORT.value
        val bypassed =
            Equipment.ignoresEntrenchment(attacker.eqid) ||
                (hasEntrenchmentBypassLeader && isEntrenchmentVulnerableTerrain)
        return !bypassed
    }
}
