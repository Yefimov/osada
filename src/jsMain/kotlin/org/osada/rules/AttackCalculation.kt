package org.osada.rules

import org.osada.LeaderType
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UNIT_MAX_EXPERIENCE
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.TerrainEx
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
        if (defender.isMounted && !defender.isSurprised && defender.unitData(true).uclass == UnitClass.INFANTRY.value) {
            defenderData = defender.unitData(true)
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
        stats.defenderAttack += defender.experience / EXPERIENCE_STAT_DIVISOR + defenderSupportBars
        stats.defenderDefense += defender.experience / EXPERIENCE_STAT_DIVISOR + defenderSupportBars

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
    ) {
        val cap = TerrainEx.initiativeCap(context.dTerrain)
        // Attachments.initiativePenalty is already <= 0 (a summed malus-type-2 penalty), so adding
        // it reduces initiative; applied before the terrain cap, matching how the cap is meant to
        // clamp the unit's EFFECTIVE initiative, not just its base equipment stat.
        val attackerInitiative =
            minOf(context.attackerData.initiative + Attachments.initiativePenalty(attacker), cap)
        val defenderInitiative =
            minOf(context.defenderData.initiative + Attachments.initiativePenalty(defender), cap)
        var initiativeDiff = attackerInitiative - defenderInitiative
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
        if (context.distance > 1 && !navalGunneryInRange) {
            result.defcanfire = false
        }
        if (!AttackEligibility.canFire(defender, attacker)) {
            result.defcanfire = false
        }
    }

    /** Tank overrun (a tank attacker deals a killing blow at range 1 while taking at most one
     *  loss and isn't surprised), then the experience gained by each side, scaled by the
     *  opponent's strength and capped at [UNIT_MAX_EXPERIENCE]. */
    fun resolveOverrunAndExperienceGain(
        stats: CombatStats,
        attacker: GameUnit,
        defender: GameUnit,
        context: CombatContext,
        result: CombatResults,
    ) {
        val isOverrun =
            context.attackerData.uclass == UnitClass.TANK.value &&
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

        val maxAtkExp = UNIT_MAX_EXPERIENCE - attacker.experience
        if (result.atkExpGained > maxAtkExp) result.atkExpGained = maxAtkExp
        val maxDefExp = UNIT_MAX_EXPERIENCE - defender.experience
        if (result.defExpGained > maxDefExp) result.defExpGained = maxDefExp
    }
}
