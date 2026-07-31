package org.osada.rules

import org.osada.LeaderType
import org.osada.SURRENDER_ON_FAILED_RETREAT
import org.osada.TerrainType
import org.osada.UNIT_RETREAT_THRESHOLD
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.hasNoSurrender
import org.osada.model.ignoresEntrenchment
import org.osada.rules.CombatResolver.DEFAULT_HIT_THRESHOLD
import org.osada.rules.CombatResolver.SPECIAL_TARGET_HIT_THRESHOLD
import org.osada.rules.CombatResolver.isEntrenchmentIntact
import org.osada.rules.CombatResolver.shouldDefenderRetreat
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

    // equip.cfg's own comment: "Default all flak-type actions are limited to range 1." Absent for
    // 5 of our 10 shipped efiles (including KAISER, 8 campaigns) -- see docs/design/aa-interception.md §1.1.
    private const val DEFAULT_FLAK_RANGE = 1

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
            repeat(attacker.strength) {
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

    /**
     * [attackerIsFiringSupport] marks the exchange as a Fire Support shot, i.e. this call resolves an
     * adjacent unit firing in support of someone else's defence rather than its own attack. OG does
     * not let a unit collect Combat Support for the exchange in which it is itself providing Fire
     * Support, so the attacker's support bars are suppressed (`DEFERRED.md` §4.6, second divergence).
     * The *defender* of a support shot -- the original attacker -- keeps its own bars: it is being
     * shot at, which is exactly the case Combat Support exists to cushion.
     */
    fun calculateAttackResults(
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
        units: List<GameUnit> = emptyList(),
        attackerIsFiringSupport: Boolean = false,
    ): CombatResults {
        val result = CombatResults()
        val context = AttackCalculation.resolveCombatContext(attacker, defender) ?: return result

        val stats = AttackCalculation.resolveCrossIndexedStats(context)
        AttackCalculation.applyAttachmentBonuses(stats, attacker, defender, context)
        val closeCombat = AttackCalculation.applyCloseCombat(stats, context)
        AttackCalculation.applyLeaderBonuses(stats, attacker, defender, context)
        AttackCalculation.applyTerrainBonuses(stats, context)
        AttackCalculation.applyEntrenchment(stats, attacker, defender, context, closeCombat)
        AttackCalculation.applyExperienceAndHitsPenalty(
            stats,
            attacker,
            defender,
            context,
            if (attackerIsFiringSupport) 0 else UnitCapabilities.combatSupportBars(units, attacker),
            UnitCapabilities.combatSupportBars(units, defender),
        )
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
        val attackerSide = attacker.player?.side ?: return result
        val supportUnits = if (!attacker.isSurprised) getSupportFireUnits(units, attacker, defender) else emptyList()
        var totalKills = 0
        var totalLosses = 0
        var visibleKills = 0
        var visibleLosses = 0

        supportUnits.forEach { support ->
            val supportHex = support.getHex() ?: return@forEach
            val supportResult =
                calculateAttackResults(support, attacker, useRandom, units, attackerIsFiringSupport = true)
            if (supportHex.isSpotted(attackerSide) || support.tempSpotted) {
                visibleKills += supportResult.kills
                visibleLosses += supportResult.losses
            }
            totalKills += supportResult.kills
            totalLosses += supportResult.losses
        }

        val mainResult = calculateAttackResults(attacker, defender, useRandom, units)
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
        return if (aPos != null && dPos != null && defenderSide != null) {
            if (HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= 1) {
                units.filter { support -> isSupportFireEligible(support, attacker, defender, defenderSide, aPos) }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
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
        // OG's `flak_range` governs ALL flak-type actions -- interception AND this air-defense
        // support fire -- and applies flat, regardless of the unit's own gunrange (DEFERRED.md
        // §1.1, docs/design/aa-interception.md §1.1/§2). Ground support fire (artillery) is
        // unaffected and keeps using its own gunrange.
        val range =
            if (UnitPredicates.isAir(attacker)) {
                EfileConfig.intKey("flak_range", DEFAULT_FLAK_RANGE)
            } else if (sData.gunrange == 0) {
                1
            } else {
                sData.gunrange
            }
        val inRange = HexGeometry.distance(sPos.row, sPos.col, aPos.row, aPos.col) <= range
        // Class eligibility lives in UnitCapabilities so the rule and the unit card's SUP/AA
        // badges read the same predicate and cannot drift apart (the §4.6 mistake).
        val classEligible =
            if (UnitPredicates.isAir(attacker)) {
                UnitCapabilities.hasAirDefenceFire(sData)
            } else {
                UnitCapabilities.hasSupportFire(sData)
            }
        // g2a_intercept_mode bit 1: an AA unit that has already intercepted a moving aircraft
        // this turn cannot also air-defend one (AAInterception.applyInterception sets the flag).
        val notSpentOnInterception = !UnitPredicates.isAir(attacker) || !support.hasInterceptedThisTurn
        return inRange &&
            classEligible &&
            notSpentOnInterception &&
            AttackEligibility.canInitiateAttack(support, attacker)
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

    /**
     * Whether [defender] surrenders (is destroyed) because a retreat it is required to make has
     * no legal destination — i.e. it is encircled or backed against impassable terrain.
     *
     * Callers must establish the precondition themselves: [shouldDefenderRetreat] is true AND
     * `getRetreatPosition` returned null. See [SURRENDER_ON_FAILED_RETREAT] for why this diverges
     * from PM, which leaves such a unit in place unharmed.
     *
     * Ferocious Defense exempts the unit, mirroring OG. Note this ADDS a meaning to the trait:
     * in PM it only makes entrenchment un-ignorable ([isEntrenchmentIntact]), and that existing
     * behaviour is untouched.
     *
     * **OG's other exemption, `No Surrender`, is now honoured too** ([SURRENDER_ON_FAILED_RETREAT]'s
     * own comment named it from the start; it was unimplemented only because the `attr` bit had not
     * been identified — see [Equipment.hasNoSurrender], bit 23, pinned down 2026-07-27). This
     * mattered most for exactly the units that carry the flag: a bunker or fort has `movpoints == 0`
     * and so can NEVER complete a legal retreat, which made every forced retreat an automatic
     * destruction. 1,252 records (2.7% of `eqp-united`) gain the exemption, 56% of the whole
     * Fortification class among them.
     *
     * [blockedByOwnUnitsOnly] also exempts it: being pinned against the map edge, water, mountains
     * or enemies is encirclement and should kill the unit, but being crowded out by your own stack
     * is a traffic-jam and must not. See [CombatPositioning.isRetreatBlockedByOwnUnitsOnly].
     */
    fun shouldDefenderSurrender(
        defender: GameUnit,
        blockedByOwnUnitsOnly: Boolean = false,
    ): Boolean =
        SURRENDER_ON_FAILED_RETREAT &&
            !blockedByOwnUnitsOnly &&
            !Equipment.hasNoSurrender(defender.getEqid(true)) &&
            !Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)

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
        // Bunker Buster (Attachments.SLOT_BUNKER_BUSTER, DEFERRED.md §1.4 Tier 2) is modelled on
        // the same "Ignore trench" attr bit Equipment.ignoresEntrenchment reads -- an unconditional
        // bypass, not restricted to fortification-class defenders, exactly like that bit's own
        // grant (`OG_ABILITY_AUDIT.md` §4).
        val bypassed =
            Equipment.ignoresEntrenchment(attacker.eqid) ||
                Attachments.has(attacker, Attachments.SLOT_BUNKER_BUSTER) ||
                (hasEntrenchmentBypassLeader && isEntrenchmentVulnerableTerrain)
        return !bypassed
    }
}
