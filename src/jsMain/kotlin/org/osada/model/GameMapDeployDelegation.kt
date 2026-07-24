package org.osada.model

/** Deploy/reinforce/core-unit-list delegation for [GameMap] (to [UnitDeployOperations]/
 *  [CoreUnitListOperations]), split out to keep its function count in bounds. */
fun GameMap.deployPlayerUnit(
    player: Player,
    index: Int,
    row: Int,
    col: Int,
): Boolean = unitDeployOperations.deployPlayerUnit(player, index, row, col)

@JsName("deployPlayerUnitByUnit")
fun GameMap.deployPlayerUnit(
    player: Player,
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean = unitDeployOperations.deployPlayerUnit(player, unit, row, col)

fun GameMap.deployNewUnitByEqId(
    eqid: Int,
    row: Int,
    col: Int,
    owner: Int,
) = unitDeployOperations.deployNewUnitByEqId(eqid, row, col, owner)

fun GameMap.deployReinforcement(
    unit: GameUnit,
    row: Int,
    col: Int,
): Cell? = unitDeployOperations.deployReinforcement(unit, row, col)

fun GameMap.resupplyUnit(unit: GameUnit): Supply = unitDeployOperations.resupplyUnit(unit)

fun GameMap.reinforceUnit(
    unit: GameUnit,
    overStrength: Boolean,
): dynamic = unitDeployOperations.reinforceUnit(unit, overStrength)

fun GameMap.buildCoreUnitList(player: Player) = coreUnitListOperations.buildCoreUnitList(player)

fun GameMap.ensureFormationIds(player: Player) = coreUnitListOperations.ensureFormationIds(player)

fun GameMap.undeployCoreUnits(player: Player) = coreUnitListOperations.undeployCoreUnits(player)

fun GameMap.restoreCoreUnitList(
    player: Player,
    saved: List<dynamic>,
) = coreUnitListOperations.restoreCoreUnitList(player, saved)

fun GameMap.removeNonCampaignUnits(player: Player) = coreUnitListOperations.removeNonCampaignUnits(player)
