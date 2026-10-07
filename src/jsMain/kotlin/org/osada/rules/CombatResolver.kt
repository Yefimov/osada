package org.osada.rules

import org.osada.LeaderType
import org.osada.SURRENDER_ON_FAILED_RETREAT
import org.osada.TerrainType
import org.osada.UNIT_RETREAT_THRESHOLD
import org.osada.UnitClass
import org.osada.hero.FormationIdentity
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.hasNoSurrender
import org.osada.model.ignoresEntrenchment
import org.osada.rules.CombatResolver.DEFAULT_HIT_THRESHOLD
import org.osada.rules.CombatResolver.SPECIAL_TARGET_HIT_THRESHOLD
import org.osada.rules.CombatResolver.isEntrenchmentIntact
import org.osada.rules.CombatResolver.shouldDefenderRetreat
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import kotlin.math.roundToInt

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
        halveStrength: Boolean = false,
        strengthLost: Int = 0,
    ): Int {
        val attackerClass = attacker.unitData().uclass
        // OG: bad weather halves the strength points an air<->ground exchange brings to bear.
        // Applied here rather than to the attack VALUE because the manual says "the number of
        // strength points used for attack", and this is the one place that number is read.
        //
        // [halveStrength] is OG 6.24's support-fire distance rule arriving by the same road, for the
        // same reason: it too halves STRENGTH POINTS, not the attack value. Rounded up, matching
        // `WeatherCombatRules.firingStrength` -- a supporter reduced to firing zero points would be
        // a silent no-op, and the two halvings compound rather than either one winning.
        //
        // [strengthLost] is what already came off this unit earlier in the SAME exchange -- OG's
        // DEFENDER-FIRST sequence ([resolveExchange]). A unit that has nothing left does not fire.
        val weatherStrength = WeatherCombatRules.firingStrength(attacker, defender, strengthLost)
        if (weatherStrength <= 0) return 0
        val firingStrength = if (halveStrength) (weatherStrength + 1) / 2 else weatherStrength
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
        // Special Munition (slot 7, `docs/design/attachments.md` Tier 3), on the unit FIRING: OG's
        // *"If Active has SpecialAmmoAttachment threshold is reduced according the value defined in
        // it: DiceToSuppress is reduced by attach-bonus, DiceToKill is reduced by attach-bonus"*.
        // [target] IS OG's DiceToKill (15, or 19 for the special-target rule). OSADA has no separate
        // DiceToSuppress roll -- suppression follows the kills (`CombatApplication`) -- so lowering
        // the kill threshold is the whole of the rule here, and suppression rises with it.
        target -= Attachments.bonus(attacker, Attachments.SLOT_SPECIAL_MUNITION)
        var kills = 0.0
        if (useRandom) {
            var q = p + ROLL_TO_HIT_OFFSET - target
            if (q < EV_ROLL_MIN) q = EV_ROLL_MIN
            if (q > EV_ROLL_MAX) q = EV_ROLL_MAX
            kills = (EV_MULTIPLIER * q * firingStrength + EV_ROUNDING_OFFSET) / EV_SCALE
        } else {
            repeat(firingStrength) {
                var roll = ((GameRandomSource.nextDouble() * DICE_MAX_ROLL).toInt() + 1).toDouble()
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
     * Support, so the attacker's support bars are suppressed (second divergence).
     * The *defender* of a support shot -- the original attacker -- keeps its own bars: it is being
     * shot at, which is exactly the case Combat Support exists to cushion.
     */
    fun calculateAttackResults(
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
        units: List<GameUnit> = emptyList(),
        attackerIsFiringSupport: Boolean = false,
        halveStrength: Boolean = false,
        committed: Boolean = false,
    ): CombatResults {
        val result = CombatResults()
        val context = AttackCalculation.resolveCombatContext(attacker, defender) ?: return result

        val stats = AttackCalculation.resolveCrossIndexedStats(context)
        AttackCalculation.applyAttachmentBonuses(stats, attacker, defender, context, units)
        val closeCombat = AttackCalculation.applyCloseCombat(stats, context)
        // OG's Rugged mode (surprise, close combat or a raised rugged defence) is an INPUT to the
        // modifiers below, not a penalty on their total -- see [RuggedCombat.isRuggedCombat]. The
        // rugged-defence roll happens HERE and only here, once per committed combat.
        val ruggedDefence = RuggedCombat.ruggedDefenceRaised(attacker, defender, context, committed)
        val rugged = RuggedCombat.isRuggedCombat(attacker, closeCombat, ruggedDefence)
        if (rugged) RuggedCombat.applyRuggedCombat(stats, context, closeCombat)
        AttackCalculation.applyLeaderBonuses(stats, attacker, defender, context)
        AttackCalculation.applyTerrainBonuses(stats, context)
        // OG: *"If not Rugged and Bad weather ..."* -- the weather defence bonus is a Rugged casualty.
        if (!rugged) WeatherCombatRules.applyDefenseBonus(stats)
        AttackCalculation.applyEntrenchment(stats, attacker, defender, context, closeCombat, rugged)
        AttackCalculation.applyExperienceAndHitsPenalty(
            stats,
            attacker,
            defender,
            context,
            if (attackerIsFiringSupport) 0 else UnitCapabilities.combatSupportBars(units, attacker),
            UnitCapabilities.combatSupportBars(units, defender),
        )
        // OG: the Range Defense Modifier *"applies when ... not Rugged combat"* -- the same waiver
        // close combat already had.
        AttackCalculation.applyRangeDefenseModifier(stats, attacker, defender, context, closeCombat || rugged)
        // Last of the defence modifiers, so it halves what the unit would actually have defended
        // with (OG 6.23, behind `dry_unit_penalties`; a no-op with the key off).
        UnitConditionPenalties.applyMinefieldPenalty(stats, attacker, defender)
        UnitConditionPenalties.applyDryUnitPenalties(stats, attacker, defender)
        // [committed] and `useRandom` are different questions and must not be confused. `useRandom`
        // selects the DAMAGE formula (its `true` branch is the expected-value one; its dice branch is
        // a dead port artifact). [committed] says whether this call is the one that actually applies
        // the result, and it is the only thing allowed to draw from the shared random stream --
        // a preview that drew would advance one peer's cursor and not the other's
        // (`rules/GameRandomSource`).
        //
        // A Rugged exchange skips it: OG's sequence routine RETURNS "DEFENDER-FIRST" for *"rugged
        // combat"* before it ever computes the initiative difference that pays these bonuses.
        if (!rugged) AttackCalculation.applyInitiativeBonus(stats, attacker, defender, context, committed)
        AttackCalculation.resolveRuggedSurpriseAndFireEligibility(attacker, defender, context, result, ruggedDefence)
        RuggedCombat.resolveExchange(
            stats,
            attacker,
            defender,
            useRandom,
            halveStrength,
            result,
            defenderFirst = rugged,
        )

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

        val defenderPos = defender.getPos()
        // §6.3 of the biography design: an endorsement may only name a formation the engine can
        // PROVE took part, and this list is that proof. Recorded before the loop consumes it.
        result.supportingFormationIds = supportUnits.mapNotNull { FormationIdentity.of(it) }.map { it.value }
        supportUnits.forEach { support ->
            val supportHex = support.getHex() ?: return@forEach
            val supportResult =
                calculateAttackResults(
                    support,
                    attacker,
                    useRandom,
                    units,
                    attackerIsFiringSupport = true,
                    halveStrength = supportFiresAtHalfStrength(support, defenderPos),
                )
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

    /**
     * Friendly units that fire in support of [defender] against [attacker]. OG's ground Fire
     * Support procedure asks whether the gun can reach the attacker, even for a ranged attack on
     * the defender. Distance to the defender determines full or half strength under the authored
     * falloff rule; it is not a gate on whether the gun answers at all.
     *
     * **The candidate list is widened by OG's `ground_carrier` bit 2** — *"enables these options:
     * Combat Support, AirDefense and FireSupport"* — which lets a formation riding inside a
     * container answer from the container's hex without leaving it. Those passengers are not in
     * `GameMap.units`, so no caller can pass them in; [CarrierHangars.supportingPassengers] is the
     * one place they are added, and it returns nothing whenever the bit is off.
     */
    fun getSupportFireUnits(
        units: List<GameUnit>,
        attacker: GameUnit,
        defender: GameUnit,
    ): List<GameUnit> {
        val aPos = attacker.getPos()
        val dPos = defender.getPos()
        val defenderSide = defender.player?.side
        val adjacentAttack =
            aPos != null && dPos != null && HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= 1
        val groundAttack = UnitPredicates.isGround(attacker) && UnitPredicates.isGround(defender)
        // Keep the existing proximity rule for air/naval support; OG's ground Fire Support is the
        // case established by the author's combat procedure and fixed here.
        val reachable = adjacentAttack || groundAttack
        if (aPos == null || defenderSide == null || !reachable) return emptyList()
        val candidates = units + CarrierHangars.supportingPassengers(units)
        val eligible =
            candidates.filter { support ->
                isSupportFireEligible(support, attacker, defender, defenderSide, aPos)
            }
        // OG 9.6's escort is ONE destroyer -- its procedure "selects the FIRST destroyer
        // adjacent to the defender" -- so a convoy screened by four answers once, not four
        // times. Ordinary support fire is untouched. List order is `map.units` order and
        // therefore identical on every peer. Added 2026-08-27; the first build let them all
        // fire (`ExtendedNaval.escortsNavalTransport`).
        val firstEscort =
            eligible.firstOrNull { ExtendedNaval.escortsNavalTransport(it, attacker, defender) }
        return eligible.filter {
            it === firstEscort || !ExtendedNaval.escortsNavalTransport(it, attacker, defender)
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
        // A contained supporter fires from its container's hex -- `ground_carrier` bit 2. Having no
        // hex of its own is exactly what makes the container's hex its effective position, so this
        // is the one accessor every range test here goes through.
        val sPos = CarrierHangars.supportPosition(support)
        if (!isFriendlyNotDefender || sPos == null) return false
        val sData = support.unitData()
        // Against an aircraft, who answers and from how far is OG's Air-Interception / Air Defense
        // procedure, owned by [AirDefenceSupport] (2026-09-26 -- it used to be one flat `flak_range`
        // disc for every kind of air defence, fighters included). Ground support fire (artillery)
        // keeps using the supporter's own gunrange.
        val reaches =
            if (UnitPredicates.isAir(attacker)) {
                AirDefenceSupport.answers(support, defender, sPos, aPos)
            } else {
                val range = if (sData.gunrange == 0) 1 else sData.gunrange
                HexGeometry.distance(sPos.row, sPos.col, aPos.row, aPos.col) <= range
            }
        // Class eligibility lives in UnitCapabilities so the rule and the unit card's SUP/AA
        // badges read the same predicate and cannot drift apart (the §4.6 mistake).
        // OG §9.6's third bullet -- "Destroyers can escort naval transports against submarine
        // attacks" -- is built as a third class-eligibility branch rather than as a mechanic of its
        // own, because OG defines it by an analogy ("just like fighters escort bombers") to
        // something this engine does not have. `ExtendedNaval.escortsNavalTransport` carries the
        // full reasoning; here it is simply one more kind of unit that may answer for a neighbour.
        // Against an aircraft the class question is already part of [AirDefenceSupport.answers].
        val classEligible =
            UnitPredicates.isAir(attacker) ||
                UnitCapabilities.hasSupportFire(sData) ||
                grantedBySomethingElse(support, attacker, defender, sPos)
        // g2a_intercept_mode bit 1: an AA unit that has already intercepted a moving aircraft
        // this turn cannot also air-defend one (AAInterception.applyInterception sets the flag).
        val notSpentOnInterception = !UnitPredicates.isAir(attacker) || !support.hasInterceptedThisTurn
        // OG's `SingleFireSup.`: this gun answers once a turn and has already done so. Read on the
        // supporter's REAL record -- the restriction belongs to the battery, not to a truck towing
        // it. See `UnitCapabilities.supportsOnlyOncePerTurn`.
        val notSpentOnSupport =
            !UnitCapabilities.supportsOnlyOncePerTurn(support.unitData(true)) || !support.hasSupportedThisTurn
        return reaches &&
            classEligible &&
            notSpentOnInterception &&
            notSpentOnSupport &&
            AttackEligibility.canInitiateAttack(support, attacker, asActiveAttack = false)
    }

    /** Support fire granted by something other than the supporter's class or equipment: OG's
     *  destroyer escort ([ExtendedNaval]) and OG's Extended Front / Flak Support leaders
     *  ([LeaderSupportFire]). */
    private fun grantedBySomethingElse(
        support: GameUnit,
        attacker: GameUnit,
        defender: GameUnit,
        sPos: Cell,
    ): Boolean =
        LeaderSupportFire.grants(support, defender, sPos) ||
            ExtendedNaval.escortsNavalTransport(support, attacker, defender)

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
        // The ATTACKER's class, as Panzer Marshal reads it (`shouldDefenderRetreat(g, b, k)` takes
        // `g.unitData().uclass`, and `g` is the attacker): a bombardment does not push a unit out of
        // its hex. The port read the defender's class until 2026-09-27, so artillery fire forced
        // retreats and surrenders while an attacked artillery unit could never be pushed back.
        // Deliberately still PM's list -- OG's retreat is a different, suppression-based procedure
        // (it names AD rather than Tactical Bomber), and adopting it is a separate ruleset decision.
        val attackerClass = attacker.unitData(true).uclass
        val cannotForceRetreat =
            attackerClass == UnitClass.ARTILLERY.value ||
                attackerClass == UnitClass.TACTICAL_BOMBER.value ||
                attackerClass == UnitClass.FORTIFICATION.value
        return !cannotForceRetreat && isLossOverRetreatThreshold(defender.strength, originalStrength)
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
            // A TRAIN is exempt for the same reason the `No Surrender` fortifications above are:
            // its legal retreat set is bounded by TRACK, not by the enemy, so "could not retreat"
            // is not evidence of encirclement. `CombatPositioning.getRetreatPosition` requires rail
            // of a train, which means one standing off the line — or on a stretch whose neighbours
            // are occupied — can never pay a forced retreat, and without this it would be destroyed
            // by the first attack that owed one. It stays on its hex and keeps firing instead,
            // which is what a train cut off from its track was actually used for.
            !UnitPredicates.isTrain(defender) &&
            !Equipment.hasNoSurrender(defender.getEqid(true)) &&
            !Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)

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
        // Bunker Buster (Attachments.SLOT_BUNKER_BUSTER, `docs/design/attachments.md` Tier 2) is modelled on
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

/**
 * OG 6.24: *"units adjacent to the attacked unit give full strength support fire, while others
 * do it with halved strength."*
 *
 * The distance that matters is to the ATTACKED unit, not to the attacker -- an artillery piece
 * two hexes behind the line supports at half, however close the assault comes to its own hex.
 * Behind `support_fire_range_falloff`, off by default: OSADA fired every eligible supporter at
 * full strength before 2026-08-18, and turning that on universally would re-tune every shipped
 * scenario that fields artillery in depth.
 *
 * Shared by the forecast and by the shot that is applied (`model/AttackTransaction`), so the two
 * cannot disagree -- until 2026-09-27 the applied shot always fired at full strength.
 */
internal fun supportFiresAtHalfStrength(
    support: GameUnit,
    defenderPos: Cell?,
): Boolean {
    val sPos = support.getPos()
    if (sPos == null || defenderPos == null || !ActiveRuleset.flag(RuleKey.SUPPORT_FIRE_FALLOFF, false)) {
        return false
    }
    return HexGeometry.distance(sPos.row, sPos.col, defenderPos.row, defenderPos.col) > ADJACENT_DISTANCE
}

/** One hex away. Named because "> 1" in the falloff test reads as a magic number otherwise. */
private const val ADJACENT_DISTANCE = 1
