package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerSide
import org.osada.getCampaignPlayer
import org.osada.rules.GameRules
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/** Unit registry for [GameMap], split out to keep its function count in bounds. */
fun GameMap.addUnit(unit: GameUnit) {
    unit.id = nextUnitId++
    units.add(unit)
    unitImages.add(unit.eqid)
    unit.transport?.let { unitImages.add(it.eqid) }
    if (unit.carrier > 0) unitImages.add(unit.carrier)
    unit.player = getPlayer(unit.owner)
    if (GameHolder.instance?.scenario?.isLoaded == true) {
        unit.synchronizeStalinRegime(unit.player?.usesStalinRegime() == true)
    }
    GameHolder.instance
        ?.getCampaignPlayer()
        ?.takeIf { it.id == unit.owner }
        ?.let { ensureFormationIds(it, listOf(unit)) }
    if (unit.flag == -1) unit.flag = getPlayer(unit.owner).country + 1
    GameRules.setZOCRange(this, unit, true)
    GameRules.setSpotRange(this, unit, true)
    rebuildSpottingForSightBlocker(unit)
}

fun GameMap.getUnits(): Array<GameUnit> = units.toTypedArray()

fun GameMap.getUnitById(id: Int): GameUnit? = units.find { it.id == id }

fun GameMap.getUnitImagesList(): dynamic {
    val result = js("{}")
    unitImages.forEach { eqid ->
        val icon = Equipment.equipment[eqid]?.icon as? String
        if (!icon.isNullOrEmpty()) result[eqid] = icon
    }
    return result
}

fun GameMap.hasAliveUnits(side: Int): Boolean = units.any { it.player?.side == side && !it.destroyed }

/** The surviving side after combat eliminated its opponent; null while both sides still fight. */
internal fun GameMap.getEliminationWinner(): Int? {
    val axisAlive = hasAliveUnits(PlayerSide.AXIS.value)
    val alliesAlive = hasAliveUnits(PlayerSide.ALLIES.value)
    return when {
        axisAlive && !alliesAlive -> PlayerSide.AXIS.value
        alliesAlive && !axisAlive -> PlayerSide.ALLIES.value
        else -> null
    }
}

/** Original `GameMap` compatibility operation; kept even though current flows do not call it. */
@Suppress("unused")
fun GameMap.removeAllSideUnits(side: Int) {
    units.filter { it.player?.side == side }.forEach { it.destroyed = true }
    updateUnitList()
}

fun GameMap.updateUnitList() {
    val iter = units.iterator()
    while (iter.hasNext()) {
        val unit = iter.next()
        if (unit.destroyed) {
            val pos = unit.getPos()
            if (pos != null) {
                GameRules.setZOCRange(this, unit, false)
                GameRules.setSpotRange(this, unit, false)
                map?.getOrNull(pos.row)?.getOrNull(pos.col)?.delUnit(unit)
                // A dead blocker stops blocking, and only a rebuild can give back the sight lines
                // it was masking.
                rebuildSpottingForSightBlocker(unit)
            }
            // `nodossier` means exactly what its name says: explicitly omit this unit. The old
            // inverted check recorded only omitted units, leaving normal campaign losses at zero.
            if (recordsInCampaignDossier(GameHolder.instance?.campaign != null, unit.nodossier)) {
                GameHolder.instance?.getCampaignPlayer()?.addDestroyedUnitToDossier(unit)
            }
            iter.remove()
        }
    }
}

internal fun recordsInCampaignDossier(
    hasCampaign: Boolean,
    noDossier: Boolean,
): Boolean = hasCampaign && !noDossier

/** Legacy unfiltered unit cycling; the modern HUD uses `ReadyUnitNavigator` instead. */
@Suppress("unused")
fun GameMap.getUnitNeighbor(
    unit: GameUnit,
    direction: Int,
    onlyUnmoved: Boolean,
): GameUnit {
    val sameSideUnits =
        units
            .filter {
                it.player?.id == unit.player?.id && (!onlyUnmoved || !it.hasMoved)
            }.toMutableList()
    val index = sameSideUnits.indexOf(unit)
    if (index == -1) return unit
    val newIndex =
        if (direction > 0) {
            (index + 1) % sameSideUnits.size
        } else {
            (index - 1 + sameSideUnits.size) % sameSideUnits.size
        }
    return sameSideUnits[newIndex]
}
