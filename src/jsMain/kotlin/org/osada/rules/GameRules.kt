package org.osada.rules

import org.osada.model.Cell
import org.osada.model.CombatResults
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Supply
import org.osada.model.Transport

/**
 * Backwards-compatibility facade over the focused rule objects.
 *
 * The combat/movement/supply/cost/geometry logic used to live here as one ~1000-line
 * god-object. It has been split (Single Responsibility) into [CombatResolver],
 * [MovementRules], [SupplyRules], [CostCalculator], [UnitPredicates], [HexGeometry] and
 * [Dice]. This object now only forwards calls, so existing Kotlin call sites and the
 * `window.GameRules` global keep working while the real logic lives in cohesive units.
 *
 * Prefer calling the specific rule object directly in new code; this facade exists to
 * keep the migration incremental and reviewable.
 */
object GameRules {

    // --- Combat (CombatResolver) ---

    internal fun attackValue(
        attackPower: Int,
        defense: Int,
        attacker: GameUnit,
        defender: GameUnit,
        useRandom: Boolean,
    ): Int = CombatResolver.attackValue(attackPower, defense, attacker, defender, useRandom)

    fun calculateAttackResults(attacker: GameUnit, defender: GameUnit, useRandom: Boolean): CombatResults =
        CombatResolver.calculateAttackResults(attacker, defender, useRandom)

    fun calculateCombatResults(
        attacker: GameUnit,
        defender: GameUnit,
        units: List<GameUnit>,
        full: Boolean,
        useRandom: Boolean,
    ): CombatResults = CombatResolver.calculateCombatResults(attacker, defender, units, full, useRandom)

    fun getSupportFireUnits(units: List<GameUnit>, attacker: GameUnit, defender: GameUnit): List<GameUnit> =
        CombatResolver.getSupportFireUnits(units, attacker, defender)

    fun getUnitAttackCells(map: Array<Array<Hex>>?, unit: GameUnit, rows: Int, cols: Int): Array<Cell> =
        CombatResolver.getUnitAttackCells(map, unit, rows, cols)

    fun getUnitAttackRange(unit: GameUnit): Int = CombatResolver.getUnitAttackRange(unit)

    fun isLossOverRetreatThreshold(current: Int, original: Int): Boolean =
        CombatResolver.isLossOverRetreatThreshold(current, original)

    fun shouldDefenderRetreat(attacker: GameUnit, defender: GameUnit, originalStrength: Int): Boolean =
        CombatResolver.shouldDefenderRetreat(attacker, defender, originalStrength)

    fun getRetreatPosition(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        rows: Int,
        cols: Int,
        hasRailData: Boolean = false,
    ): Cell? = CombatResolver.getRetreatPosition(map, unit, rows, cols, hasRailData)

    fun isRuggedDefense(attacker: GameUnit, defender: GameUnit): Boolean =
        CombatResolver.isRuggedDefense(attacker, defender)

    fun isEntrenchmentIntact(attacker: GameUnit, defender: GameUnit, terrain: Int): Boolean =
        CombatResolver.isEntrenchmentIntact(attacker, defender, terrain)

    fun canInitiateAttack(attacker: GameUnit, defender: GameUnit): Boolean =
        CombatResolver.canInitiateAttack(attacker, defender)

    fun canFire(attacker: GameUnit, defender: GameUnit): Boolean = CombatResolver.canFire(attacker, defender)

    fun isInAttackRange(attacker: GameUnit, defender: GameUnit): Boolean =
        CombatResolver.isInAttackRange(attacker, defender)

    fun airGroundedByWeather(unit: GameUnit): Boolean = CombatResolver.airGroundedByWeather(unit)

    // --- Movement / spotting / deploy (MovementRules) ---

    fun getMoveRange(map: GameMap, unit: GameUnit): Array<ExtendedCell> = MovementRules.getMoveRange(map, unit)

    fun getUnitMoveRange(unit: GameUnit): Int = MovementRules.getUnitMoveRange(unit)

    fun getUnitSpotRange(unit: GameUnit): Int = MovementRules.getUnitSpotRange(unit)

    fun setZOCRange(map: GameMap, unit: GameUnit, add: Boolean) = MovementRules.setZOCRange(map, unit, add)

    fun setSpotRange(map: GameMap, unit: GameUnit, add: Boolean): Int = MovementRules.setSpotRange(map, unit, add)

    fun getShortestPath(start: Cell, end: Cell, moveRange: List<Cell>): List<Cell> =
        MovementRules.getShortestPath(start, end, moveRange)

    fun canPassInto(map: Array<Array<Hex>>?, unit: GameUnit, cell: Cell): Boolean =
        MovementRules.canPassInto(map, unit, cell)

    fun isBridgeForSide(hex: Hex?, side: Int): Boolean = MovementRules.isBridgeForSide(hex, side)

    fun getEmbarkType(map: GameMap, unit: GameUnit): Int = MovementRules.getEmbarkType(map, unit)

    fun getDisembarkPositions(map: GameMap, unit: GameUnit): List<Cell> = MovementRules.getDisembarkPositions(map, unit)

    fun getReinforcementDeployPositions(map: GameMap, unit: GameUnit, row: Int, col: Int): Cell? =
        MovementRules.getReinforcementDeployPositions(map, unit, row, col)

    fun canEmbark(map: GameMap, unit: GameUnit): Boolean = MovementRules.canEmbark(map, unit)

    fun canDisembark(map: GameMap, unit: GameUnit): Boolean = MovementRules.canDisembark(map, unit)

    // --- Supply / reinforce (SupplyRules) ---

    fun getResupplyValue(map: GameMap, unit: GameUnit, full: Boolean = false): Supply =
        SupplyRules.getResupplyValue(map, unit, full)

    fun getReinforceValue(map: GameMap, unit: GameUnit, overStrength: Boolean = false): Int =
        SupplyRules.getReinforceValue(map, unit, overStrength)

    fun canResupply(map: GameMap, unit: GameUnit): Boolean = SupplyRules.canResupply(map, unit)

    fun canReinforce(map: GameMap, unit: GameUnit, overStrength: Boolean = false): Boolean =
        SupplyRules.canReinforce(map, unit, overStrength)

    // --- Unit predicates (UnitPredicates) ---

    fun canCapture(unit: GameUnit): Boolean = UnitPredicates.canCapture(unit)
    fun canEntrench(unit: GameUnit): Boolean = UnitPredicates.canEntrench(unit)
    fun isEnemy(a: GameUnit?, b: GameUnit?): Boolean = UnitPredicates.isEnemy(a, b)
    fun canMount(unit: GameUnit): Boolean = UnitPredicates.canMount(unit)
    fun canUnmount(unit: GameUnit): Boolean = UnitPredicates.canUnmount(unit)
    fun isTransportable(eqid: Int): Boolean = UnitPredicates.isTransportable(eqid)
    fun isAir(unit: GameUnit?): Boolean = UnitPredicates.isAir(unit)
    fun isSea(unit: GameUnit?): Boolean = UnitPredicates.isSea(unit)
    fun isGround(unit: GameUnit?): Boolean = UnitPredicates.isGround(unit)
    fun isTrain(unit: GameUnit?): Boolean = UnitPredicates.isTrain(unit)
    fun isCloseCombatTerrain(terrain: Int): Boolean = UnitPredicates.isCloseCombatTerrain(terrain)
    fun unitUsesFuel(unit: GameUnit): Boolean = UnitPredicates.unitUsesFuel(unit)
    fun unitUsesFuel(transport: Transport): Boolean = UnitPredicates.unitUsesFuel(transport)
    fun unitLowFuel(unit: GameUnit, threshold: Int): Boolean = UnitPredicates.unitLowFuel(unit, threshold)
    fun unitUsesAmmo(unit: GameUnit): Boolean = UnitPredicates.unitUsesAmmo(unit)
    fun unitLowAmmo(unit: GameUnit, threshold: Int): Boolean = UnitPredicates.unitLowAmmo(unit, threshold)

    // --- Costs (CostCalculator) ---

    fun calculateUnitCosts(eqid: Int, transportEqid: Int): Int = CostCalculator.calculateUnitCosts(eqid, transportEqid)
    fun calculateUpgradeCosts(unit: GameUnit, newEqid: Int, transportEqid: Int): Int =
        CostCalculator.calculateUpgradeCosts(unit, newEqid, transportEqid)
    fun calculateUnitCostPerStrength(unit: GameUnit): Int = CostCalculator.calculateUnitCostPerStrength(unit)
    fun calculateUnitSellCost(unit: GameUnit): Int = CostCalculator.calculateUnitSellCost(unit)

    // --- Geometry (HexGeometry) ---

    fun getDirection(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): Int? =
        HexGeometry.getDirection(fromRow, fromCol, toRow, toCol)

    fun distance(row1: Int, col1: Int, row2: Int, col2: Int): Int = HexGeometry.distance(row1, col1, row2, col2)

    fun getAdjacent(row: Int, col: Int): List<Cell> = HexGeometry.getAdjacent(row, col)

    fun isAdjacent(row1: Int, col1: Int, row2: Int, col2: Int): Boolean = HexGeometry.isAdjacent(row1, col1, row2, col2)

    fun facingToAdjacentIndex(facing: Int): Int = HexGeometry.facingToAdjacentIndex(facing)

    internal fun getRing(row: Int, col: Int, radius: Int, rows: Int, cols: Int, extended: Boolean): MutableList<Cell> =
        HexGeometry.getRing(row, col, radius, rows, cols, extended)

    // --- Dice ---

    fun rollDice(min: Int, max: Int): Int = Dice.roll(min, max)
}
