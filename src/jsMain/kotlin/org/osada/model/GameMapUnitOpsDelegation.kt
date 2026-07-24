package org.osada.model

/** Mount/embark/upgrade delegation for [GameMap] (to [UnitMountOperations]/[UnitDeployOperations]),
 *  split out to keep its function count in bounds. */
fun GameMap.mountUnit(unit: GameUnit) = unitMountOperations.mountUnit(unit)

fun GameMap.mountUnitHandler(unit: GameUnit) = unitMountOperations.mountUnitHandler(unit)

fun GameMap.unmountUnit(unit: GameUnit) = unitMountOperations.unmountUnit(unit)

fun GameMap.unmountUnitHandler(unit: GameUnit) = unitMountOperations.unmountUnitHandler(unit)

fun GameMap.embarkUnit(unit: GameUnit): Boolean = unitMountOperations.embarkUnit(unit)

fun GameMap.disembarkUnit(unit: GameUnit): Boolean = unitMountOperations.disembarkUnit(unit)

fun GameMap.upgradeUnit(
    unitId: Int,
    newEqid: Int,
    transportEqid: Int,
): Boolean = unitDeployOperations.upgradeUnit(unitId, newEqid, transportEqid)

fun GameMap.disbandUnit(unitId: Int): Boolean = unitDeployOperations.disbandUnit(unitId)
