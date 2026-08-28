package org.osada.rules

import org.osada.LeaderType
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.TerrainEx
import org.osada.rules.AttackCalculation.resolveCombatContext
import kotlin.math.abs

/**
 * The per-step attack/defense calculation pipeline behind [CombatResolver.calculateAttackResults].
 * Split out into its own object purely to keep [CombatResolver] within the project's
 * function-count/class-size limits -- these functions are effectively private implementation
 * detail of `calculateAttackResults` and are not expected to be called from elsewhere.
 */
internal object AttackCalculation {
    // calculateAttackResults' flat +/- modifiers. Several distinct rules share the same +4
    // swing by design (matches the legacy JS exactly) -- named separately per rule so each
    // reads at its call site, even where the underlying number happens to coincide.
    private const val CLOSE_COMBAT_ATTACK_BONUS = 4
    private const val AGGRESSIVE_ATTACK_BONUS = 4

    // Aggressive Attack's minor +2 swing: applied to the bearer's own defense when it's
    // attacking, and to BOTH of the defender's stats when the bearer is defending instead
    // (asymmetric with AGGRESSIVE_ATTACK_BONUS, which only applies to the attacking side).
    private const val AGGRESSIVE_ATTACK_MINOR_BONUS = 2
    private const val DETERMINED_DEFENSE_ATTACK_BONUS = 2
    private const val DETERMINED_DEFENSE_DEFENSE_BONUS = 4
    private const val TENACIOUS_DEFENSE_BONUS = 4
    private const val SKILLED_GROUND_ATTACK_BONUS = 4
    private const val ARTILLERY_INNATE_DEFENSE_BONUS = 3
    private const val CITY_DEFENSE_BONUS = 4
    private const val RIVER_ASSAULT_BONUS = 4
    private const val EXPERIENCE_STAT_DIVISOR = 100

    /** OG's penalty on a sabotaged formation: -2 attack and -2 defence. */
    private const val SABOTAGE_PENALTY = 2
    private const val INITIATIVE_DEFENSE_BONUS = 4
    private const val INITIATIVE_ATTACK_BONUS_CAP = 4

    /** Cross-indexed geometry/terrain/equipment context shared by every step of
     *  [CombatResolver.calculateAttackResults]. [defenderData] is `var`: mounted-infantry
     *  dismount (see [resolveCombatContext]) swaps it after construction. */
    class CombatContext(
        val attackerData: EquipmentData,
        var defenderData: EquipmentData,
        val attackerTarget: Int,
        val defenderTarget: Int,
        val aTerrain: Int,
        val dTerrain: Int,
        val attackerHex: Hex,
        val defenderHex: Hex,
        val distance: Int,
        val entrenchmentIntact: Boolean,
        val attackerEntrenchmentIntact: Boolean,
    )

    /** Mutable attack/defense accumulator threaded through the bonus/penalty steps below. */
    class CombatStats(
        var attackerAttack: Int = 0,
        var attackerDefense: Int = 0,
        var defenderAttack: Int = 0,
        var defenderDefense: Int = 0,
    )

    private class CombatLocations(
        val aPos: Cell,
        val dPos: Cell,
        val attackerHex: Hex,
        val defenderHex: Hex,
    )

    /** Resolves positions, terrain, mounted-dismount and entrenchment-intact context for
     *  [attacker]/[defender], or null if either lacks a position/hex (can't fight). An
     *  expression-bodied `?.let` chain resolves the four positions/hexes with zero explicit
     *  `return` statements, satisfying the project's max-return-statements rule without an
     *  artificial combined condition. */
    fun resolveCombatContext(
        attacker: GameUnit,
        defender: GameUnit,
    ): CombatContext? {
        val locations =
            attacker.getPos()?.let { aPos ->
                defender.getPos()?.let { dPos ->
                    attacker.getHex()?.let { attackerHex ->
                        defender.getHex()?.let { defenderHex ->
                            CombatLocations(aPos, dPos, attackerHex, defenderHex)
                        }
                    }
                }
            } ?: return null
        val aPos = locations.aPos
        val dPos = locations.dPos
        val attackerHex = locations.attackerHex
        val defenderHex = locations.defenderHex

        val attackerData = attacker.unitData()
        var defenderData = defender.unitData()
        val distance = HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col)

        var aTerrain = attackerHex.terrain
        var dTerrain = defenderHex.terrain
        if (UnitPredicates.isAir(attacker)) aTerrain = TerrainType.CLEAR.value
        if (UnitPredicates.isAir(defender)) dTerrain = TerrainType.CLEAR.value

        // Mounted infantry that is not surprised dismounts and fights with its own
        // (base) stats instead of the transport's. Mirrors JS: n = Equipment.equipment[k.eqid].
        // defenderTarget below keeps the original (transport) target type, as in JS.
        //
        // The Infantry-class test is OG's DEFAULT, not the whole rule: `Dismount` (attr bit 11) is
        // one of OG's three paired toggles and REVERSES it per record, which
        // `UnitCapabilities.dismountsWhenAttacked` applies. Wired 2026-08-25; before that the JS's
        // plain class test stood alone and the 2,628 records carrying the bit were ignored.
        val ownData = defender.unitData(true)
        if (defender.isMounted && !defender.isSurprised && UnitCapabilities.dismountsWhenAttacked(ownData)) {
            defenderData = ownData
        }

        return CombatContext(
            attackerData = attackerData,
            defenderData = defenderData,
            attackerTarget = attackerData.target,
            defenderTarget = defenderData.target,
            aTerrain = aTerrain,
            dTerrain = dTerrain,
            attackerHex = attackerHex,
            defenderHex = defenderHex,
            distance = distance,
            entrenchmentIntact = CombatResolver.isEntrenchmentIntact(attacker, defender, dTerrain),
            attackerEntrenchmentIntact = CombatResolver.isEntrenchmentIntact(defender, attacker, aTerrain),
        )
    }

    /** Base attack/defense values. CROSS-INDEXED (mirrors JS): the defender's attack/defense
     *  are selected by the ATTACKER's target type, and the attacker's by the DEFENDER's target
     *  type (e.g. a tank attacking infantry uses its softatk, not its hardatk). */
    fun resolveCrossIndexedStats(context: CombatContext): CombatStats {
        val stats = CombatStats()
        val attackerData = context.attackerData
        val defenderData = context.defenderData
        when (context.attackerTarget) {
            UnitType.AIR.value -> {
                stats.defenderAttack = defenderData.airatk
                stats.defenderDefense = defenderData.airdef
            }

            UnitType.SOFT.value -> {
                stats.defenderAttack = defenderData.softatk
                stats.defenderDefense = defenderData.grounddef
            }

            UnitType.HARD.value -> {
                stats.defenderAttack = defenderData.hardatk
                stats.defenderDefense = defenderData.grounddef
            }

            UnitType.SEA.value -> {
                stats.defenderAttack = defenderData.navalatk
                stats.defenderDefense = defenderData.grounddef
                if (defenderData.uclass == UnitClass.SUBMARINE.value) stats.attackerDefense = attackerData.closedef
            }
        }
        when (context.defenderTarget) {
            UnitType.AIR.value -> {
                stats.attackerAttack = attackerData.airatk
                stats.attackerDefense = attackerData.airdef
            }

            UnitType.SOFT.value -> {
                stats.attackerAttack = attackerData.softatk
                stats.attackerDefense = attackerData.grounddef
            }

            UnitType.HARD.value -> {
                stats.attackerAttack = attackerData.hardatk
                stats.attackerDefense = attackerData.grounddef
            }

            UnitType.SEA.value -> {
                stats.attackerAttack = attackerData.navalatk
                stats.attackerDefense = attackerData.grounddef
                if (attackerData.uclass == UnitClass.SUBMARINE.value) stats.defenderDefense = defenderData.closedef
            }
        }
        return stats
    }

    /** Whether [context] describes an infantry-vs-close-terrain close-combat engagement;
     *  overwrites [stats]' defense values with close-combat stats when it does. */
    fun applyCloseCombat(
        stats: CombatStats,
        context: CombatContext,
    ): Boolean {
        val defenderData = context.defenderData
        val closeCombat =
            (
                UnitPredicates.isCloseCombatTerrain(context.dTerrain) ||
                    defenderData.uclass == UnitClass.FORTIFICATION.value
            ) &&
                context.attackerData.uclass == UnitClass.INFANTRY.value
        if (closeCombat) {
            stats.defenderDefense = defenderData.closedef
            if (defenderData.uclass == UnitClass.INFANTRY.value) {
                // infantry vs infantry: attacker also defends with its close-combat value
                stats.attackerDefense = context.attackerData.closedef
            } else {
                stats.attackerAttack += CLOSE_COMBAT_ATTACK_BONUS
            }
        }
        return closeCombat
    }

    fun applyLeaderBonuses(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
    ) {
        if (Leaders.unitHasLeader(attacker, LeaderType.AGGRESSIVE_ATTACK)) {
            stats.attackerAttack += AGGRESSIVE_ATTACK_BONUS
            stats.attackerDefense += AGGRESSIVE_ATTACK_MINOR_BONUS
        }
        if (Leaders.unitHasLeader(defender, LeaderType.AGGRESSIVE_ATTACK)) {
            stats.defenderAttack += AGGRESSIVE_ATTACK_MINOR_BONUS
            stats.defenderDefense += AGGRESSIVE_ATTACK_MINOR_BONUS
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.DETERMINED_DEFENSE)) {
            stats.attackerDefense += DETERMINED_DEFENSE_ATTACK_BONUS
        }
        if (Leaders.unitHasLeader(defender, LeaderType.DETERMINED_DEFENSE)) {
            stats.defenderAttack += DETERMINED_DEFENSE_ATTACK_BONUS
            stats.defenderDefense += DETERMINED_DEFENSE_DEFENSE_BONUS
        }
        if (Leaders.unitHasLeader(attacker, LeaderType.TENACIOUS_DEFENSE) &&
            context.defenderTarget != UnitType.AIR.value
        ) {
            stats.attackerDefense += TENACIOUS_DEFENSE_BONUS
        }
        if (Leaders.unitHasLeader(defender, LeaderType.TENACIOUS_DEFENSE) &&
            context.attackerTarget != UnitType.AIR.value
        ) {
            stats.defenderAttack += TENACIOUS_DEFENSE_BONUS
        }
        if (UnitPredicates.isAir(attacker) &&
            Leaders.unitHasLeader(attacker, LeaderType.SKILLED_GROUND_ATTACK) &&
            UnitPredicates.isGround(defender)
        ) {
            stats.attackerAttack += SKILLED_GROUND_ATTACK_BONUS
        }
    }

    /** Artillery innate defense, city defense, and the river/stream-without-a-road assault
     *  bonus (applied to whichever side is attacking across the crossing). */
    fun applyTerrainBonuses(
        stats: CombatStats,
        context: CombatContext,
    ) {
        if (context.defenderData.uclass == UnitClass.ARTILLERY.value) {
            stats.defenderDefense += ARTILLERY_INNATE_DEFENSE_BONUS
        }
        if (context.dTerrain == TerrainType.CITY.value) stats.defenderDefense += CITY_DEFENSE_BONUS
        if ((context.dTerrain == TerrainType.RIVER.value || context.dTerrain == TerrainType.STREAM.value) &&
            context.defenderHex.road == RoadType.NONE.value
        ) {
            stats.attackerAttack += RIVER_ASSAULT_BONUS
            stats.attackerDefense += RIVER_ASSAULT_BONUS
        }
        if ((context.aTerrain == TerrainType.RIVER.value || context.aTerrain == TerrainType.STREAM.value) &&
            context.attackerHex.road == RoadType.NONE.value
        ) {
            stats.defenderAttack += RIVER_ASSAULT_BONUS
            stats.defenderDefense += RIVER_ASSAULT_BONUS
        }
    }

    /** Entrenchment defense bonus, bypassed by Infiltration Tactics (unless the defender has
     *  Ferocious Defense) and double-counted for a vehicle attacking entrenched infantry in
     *  close terrain. */
    fun applyEntrenchment(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        closeCombat: Boolean,
    ) {
        var attackerEntrenchment = 0
        var defenderEntrenchment = 0
        if (context.attackerEntrenchmentIntact && !closeCombat) attackerEntrenchment = attacker.entrenchment
        if (context.entrenchmentIntact) defenderEntrenchment = defender.entrenchment

        if (Leaders.unitHasLeader(attacker, LeaderType.INFILTRATION_TACTICS) &&
            !Leaders.unitHasLeader(defender, LeaderType.FEROCIOUS_DEFENSE)
        ) {
            defenderEntrenchment = 0
        }
        if (Leaders.unitHasLeader(defender, LeaderType.INFILTRATION_TACTICS) &&
            !Leaders.unitHasLeader(attacker, LeaderType.FEROCIOUS_DEFENSE)
        ) {
            attackerEntrenchment = 0
        }

        stats.attackerDefense += attackerEntrenchment
        stats.defenderDefense += defenderEntrenchment

        val isVehicleAttackingEntrenchedInfantry =
            context.defenderData.uclass == UnitClass.INFANTRY.value &&
                UnitPredicates.isCloseCombatTerrain(context.dTerrain) &&
                !closeCombat &&
                context.attackerData.uclass > UnitClass.INFANTRY.value &&
                context.attackerData.uclass < UnitClass.GROUND_TRANSPORT.value
        if (isVehicleAttackingEntrenchedInfantry) {
            // infantry in close terrain attacked by a vehicle: its entrenchment counts twice
            stats.defenderDefense += defenderEntrenchment
        }
    }

    /** Flat +1-per-100-experience stat bonus, then the hits-taken defense penalty (waived for
     *  artillery/fortification/most naval classes). */
    fun applyExperienceAndHitsPenalty(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        attackerSupportBars: Int = 0,
        defenderSupportBars: Int = 0,
    ) {
        stats.attackerAttack += attacker.experience / EXPERIENCE_STAT_DIVISOR + attackerSupportBars
        stats.attackerDefense += attacker.experience / EXPERIENCE_STAT_DIVISOR + attackerSupportBars
        // OG's `Saboteur` leaves its victim at -2 attack and -2 defence until it recovers
        // (`rules/Sabotage`, `GameUnit.sabotaged`). Applied to whichever side is sabotaged.
        if (attacker.sabotaged) {
            stats.attackerAttack -= SABOTAGE_PENALTY
            stats.attackerDefense -= SABOTAGE_PENALTY
        }
        if (defender.sabotaged) {
            stats.defenderAttack -= SABOTAGE_PENALTY
            stats.defenderDefense -= SABOTAGE_PENALTY
        }
        stats.defenderAttack += defender.experience / EXPERIENCE_STAT_DIVISOR + defenderSupportBars
        stats.defenderDefense += defender.experience / EXPERIENCE_STAT_DIVISOR + defenderSupportBars

        // Suppression (`GameUnit.hits`) is spent here, and the gate is on the ATTACKER's class --
        // those classes do not EXPLOIT a suppressed defender, they are not immune to being
        // suppressed themselves. Verified 2026-08-18 against `openpanzer.js:2424`, where the same
        // test reads `s.uclass` (the attacker's equipment) while the penalty reads `k.hits` (the
        // defender's counter): `docs/og-fidelity-plan.md` A.7. The player-facing string said the
        // opposite and was corrected, not the code.
        val attackerData = context.attackerData
        val hitsPenaltyApplies =
            attackerData.uclass != UnitClass.ARTILLERY.value &&
                attackerData.uclass != UnitClass.FORTIFICATION.value &&
                (attackerData.uclass < UnitClass.SUBMARINE.value || attackerData.uclass > UnitClass.LIGHT_CRUISER.value)
        if (hitsPenaltyApplies) {
            val hitsPenalty = 2 * defender.hits
            stats.defenderDefense = if (stats.defenderDefense <= hitsPenalty) 0 else stats.defenderDefense - hitsPenalty
        }
    }

    /** Ranged-fire defense bonus for ground-vs-ground combat outside close combat (halved at
     *  range 1 / within the defender's own attack range), waived for a moved Anti-Tank unit
     *  without the Tank Killer leader. */
    fun applyRangeDefenseModifier(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        closeCombat: Boolean,
    ) {
        if (!UnitPredicates.isGround(attacker) || !UnitPredicates.isGround(defender) || closeCombat) return
        val antiTankMoved =
            context.attackerData.uclass == UnitClass.ANTI_TANK.value &&
                attacker.hasMoved &&
                !Leaders.unitHasLeader(attacker, LeaderType.TANK_KILLER)
        val attackerData = context.attackerData
        val defenderData = context.defenderData
        if (context.distance <= 1 || AttackEligibility.getUnitAttackRange(defender) < context.distance) {
            if (!antiTankMoved) stats.attackerDefense += attackerData.rangedefmod / 2
            stats.defenderDefense += defenderData.rangedefmod / 2
        } else {
            if (!antiTankMoved) stats.attackerDefense += attackerData.rangedefmod
            stats.defenderDefense += defenderData.rangedefmod
        }
    }

    /** Initiative-difference bonus: the faster side gets +defense and a capped +attack,
     *  reversed by either side's First Strike leader.
     *
     *  Both combatants' initiative is first clamped to the defender's terrain
     *  ([TerrainEx.initiativeCap]) -- OG's terrain data throttles initiative in close terrain
     *  (town, mountain, ...), and PM's own AI already scores moves by the identical
     *  [org.osada.terrainInitiative] table (`AIPositionEvaluation`) without the combat resolver
     *  ever having applied it. Capping by the DEFENDER's hex, not each side's own, is deliberate:
     *  the alternative would make assaulting a city better for a fast attacker, which inverts the
     *  point of the cap (see `docs/design/terrain-supply-and-initiative.md` §4.2). Applied before
     *  the First Strike reversal below, which takes `abs()` of the difference. */
    fun applyInitiativeBonus(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        committed: Boolean = false,
    ) {
        val cap = TerrainEx.initiativeCap(context.dTerrain)
        // Attachments.initiativePenalty is already <= 0 (a summed malus-type-2 penalty), so adding
        // it reduces initiative; applied before the terrain cap, matching how the cap is meant to
        // clamp the unit's EFFECTIVE initiative, not just its base equipment stat.
        // OG 6.10 names experience as a third input; OSADA's own model does not read it. Added
        // before the terrain cap for the same reason the attachment penalty is: the cap clamps the
        // initiative a formation EFFECTIVELY brings into the hex. Behind `initiative_model`.
        val attackerBase =
            context.attackerData.initiative + Attachments.initiativePenalty(attacker) +
                InitiativeModel.experienceBonus(attacker)
        val defenderBase =
            context.defenderData.initiative + Attachments.initiativePenalty(defender) +
                InitiativeModel.experienceBonus(defender)
        // OG 6.23 halves an empty unit's initiative, behind `dry_unit_penalties`; a no-op with the
        // key off. Applied after the terrain cap, on the initiative the unit would actually have had.
        val attackerInitiative = UnitConditionPenalties.dryInitiative(attacker, minOf(attackerBase, cap))
        val defenderInitiative = UnitConditionPenalties.dryInitiative(defender, minOf(defenderBase, cap))
        // OG 6.10's other half: *"adjusted by a random value, to simulate combat uncertainty"*,
        // rolled independently per side and AFTER the cap, because chance is not a property of the
        // terrain. Drawn from the shared seeded stream and only on the COMMITTED path, so both
        // multiplayer peers roll the same swing and no preview or repaint can move the cursor
        // ([InitiativeModel], `rules/GameRandomSource`). Zero on both counts with the key off.
        var initiativeDiff =
            (attackerInitiative + InitiativeModel.randomAdjustment(committed)) -
                (defenderInitiative + InitiativeModel.randomAdjustment(committed))
        if (Leaders.unitHasLeader(attacker, LeaderType.FIRST_STRIKE)) initiativeDiff = abs(initiativeDiff)
        if (Leaders.unitHasLeader(defender, LeaderType.FIRST_STRIKE)) initiativeDiff = -abs(initiativeDiff)
        if (initiativeDiff >= 0) {
            stats.attackerDefense += INITIATIVE_DEFENSE_BONUS
            stats.attackerAttack += minOf(INITIATIVE_ATTACK_BONUS_CAP, initiativeDiff)
        } else {
            stats.defenderDefense += INITIATIVE_DEFENSE_BONUS
            stats.defenderAttack += minOf(INITIATIVE_ATTACK_BONUS_CAP, -initiativeDiff)
        }
    }

    /** Rugged-defense / surprise checks that zero the attacker's defense and halve its attack,
     *  then whether the defender is in range and able to fire back at all. */
    fun resolveRuggedSurpriseAndFireEligibility(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        result: CombatResults,
    ) {
        if (context.distance == 1 && context.entrenchmentIntact && CombatResolver.isRuggedDefense(attacker, defender)) {
            result.isRugged = true
        }
        if (attacker.isSurprised || result.isRugged) {
            stats.attackerDefense = 0
            stats.attackerAttack /= 2
        }
        val navalGunneryInRange =
            UnitPredicates.isSea(attacker) &&
                UnitPredicates.isSea(defender) &&
                context.defenderData.gunrange >= context.distance
        // OG §9.6's first bullet -- "Ships return fire to artillery and forts" -- is the second way
        // a ranged attack can draw an answer, and the only one of the four extended-naval rules
        // that GIVES rather than restricts. Without the optional rule a shore battery shells a
        // fleet with complete impunity. See `ExtendedNaval.shipReturnsFireToShoreBattery`.
        val shoreBatteryAnswered =
            ExtendedNaval.shipReturnsFireToShoreBattery(attacker, defender, context.distance)
        if (context.distance > 1 && !navalGunneryInRange && !shoreBatteryAnswered) {
            result.defcanfire = false
        }
        if (!AttackEligibility.canFire(defender, attacker)) {
            result.defcanfire = false
        }
    }

    /** Tank overrun (an overrun-capable attacker deals a killing blow at range 1 while taking at
     *  most one loss and isn't surprised), then the experience gained by each side, scaled by the
     *  opponent's strength and capped at [UnitExperience.cap].
     *
     *  The eligibility test moved onto [UnitCapabilities.canOverrun] on 2026-08-25 so that this
     *  rule and the OVR badge read the same function -- until then the badge was `classDefault xor
     *  bit` and the rule was the bare Tank class, so 211 tanks wore a mark OG switches OFF for
     *  them and 145 non-tanks did not wear one OG switches ON. With `equipment_toggles` off this
     *  is the identical Tank test it has always been. */
    fun resolveOverrunAndExperienceGain(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        result: CombatResults,
    ) {
        val isOverrun =
            UnitCapabilities.canOverrun(context.attackerData) &&
                result.losses <= 1 &&
                result.kills >= defender.strength &&
                context.distance == 1 &&
                !attacker.isSurprised
        if (isOverrun) {
            result.isOverrun = true
        }

        val attackerKillExp = (stats.defenderAttack + 6 - stats.attackerDefense).coerceAtLeast(1)
        val attackerSurviveExp = (stats.defenderDefense + 6 - stats.attackerAttack).coerceAtLeast(1)
        val defenderKillExp = (stats.attackerAttack + 6 - stats.defenderDefense).coerceAtLeast(1)
        val defenderSurviveExp = (stats.attackerDefense + 6 - stats.defenderAttack).coerceAtLeast(1)

        result.atkExpGained =
            (
                (attackerKillExp * (defender.strength / CombatResolver.FULL_STRENGTH) + attackerSurviveExp) *
                    result.kills
            ).toInt()
        result.defExpGained = 2 * result.kills
        if (result.defcanfire) {
            result.atkExpGained += 2 * result.losses
            result.defExpGained +=
                (
                    (defenderKillExp * (attacker.strength / CombatResolver.FULL_STRENGTH) + defenderSurviveExp) *
                        result.losses
                ).toInt()
        }

        val cap = UnitExperience.cap()
        val maxAtkExp = (cap - attacker.experience).coerceAtLeast(0)
        if (result.atkExpGained > maxAtkExp) result.atkExpGained = maxAtkExp
        val maxDefExp = (cap - defender.experience).coerceAtLeast(0)
        if (result.defExpGained > maxDefExp) result.defExpGained = maxDefExp
    }
}

/**
 * The attacker's raw attack value against a defender of this target type — the cross-indexed
 * selection [resolveCrossIndexedStats] makes, on its own.
 *
 * Added 2026-08-27 for `rules/Sabotage`, whose chance is *"3 x attack value against the
 * defender"*. It reads the same four fields by the same rule, so a saboteur's odds can never
 * disagree with the attack it is trying to avoid making.
 */
internal fun attackValueAgainst(
    attackerData: EquipmentData,
    defenderData: EquipmentData,
): Int =
    when (defenderData.target) {
        UnitType.AIR.value -> attackerData.airatk
        UnitType.HARD.value -> attackerData.hardatk
        UnitType.SEA.value -> attackerData.navalatk
        else -> attackerData.softatk
    }
