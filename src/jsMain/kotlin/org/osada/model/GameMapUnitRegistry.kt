package org.osada.model

import org.osada.GameHolder
import org.osada.PlayerSide
import org.osada.getCampaignPlayer
import org.osada.rules.CarrierHangars
import org.osada.rules.GameRules
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/** Unit registry for [GameMap], split out to keep its function count in bounds. */
fun GameMap.addUnit(unit: GameUnit) {
    if (preserveUnitIds && unit.id >= 0 && units.none { it.id == unit.id }) {
        nextUnitId = maxOf(nextUnitId, unit.id + 1)
    } else {
        unit.id = nextUnitId++
    }
    units.add(unit)
    unitImages.add(unit.eqid)
    unit.transport?.let { unitImages.add(it.eqid) }
    if (unit.carrier > 0) unitImages.add(unit.carrier)
    unit.player = getPlayer(unit.owner)
    // A container's passengers are off the map but still this battle's units: their ids stay
    // reserved, and they answer to the same player (a restored hangar arrives with neither).
    unit.hangar.forEach { passenger ->
        nextUnitId = maxOf(nextUnitId, passenger.id + 1)
        passenger.player = getPlayer(passenger.owner)
        unitImages.add(passenger.eqid)
    }
    if (GameHolder.instance?.scenario?.isLoaded == true) {
        unit.synchronizeStalinRegime(unit.player?.usesStalinRegime() == true)
    }
    GameHolder.instance
        ?.getCampaignPlayer()
        ?.takeIf { it.id == unit.owner }
        ?.let { player ->
            ensureFormationIds(player, listOf(unit))
            // OG's Make Core on a formation that arrives MID-BATTLE -- an authored reinforcement
            // wave or a scenario event's spawn.
            //
            // The guard is MAP IDENTITY, not `scenario.isLoaded`. During a scenario load or a save
            // restore this map is not yet the holder's, while `Game.campaignPlayer` still points at
            // the PREVIOUS battle's player object -- so an `isLoaded` test would file the new
            // battle's units into a roster about to be discarded, and charge OG's purchase cap for
            // each. Placement at load time is swept once by
            // `Game.handleCampaignScenarioLoaded`'s `enrollAuthoredCoreUnits`, after the right
            // player exists.
            if (GameHolder.instance?.scenario?.map === this) enrollIfAuthoredCore(player, unit)
        }
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
            recordLoss(unit)
            // A carrier takes its hangar down with it -- an aircraft cannot outlive the ship it
            // was contained in (`rules/CarrierHangars`). Its passengers are never in `units`, so
            // this sweep would not otherwise see them: each is booked as a loss of its own first
            // (dossier, commander, replacement credit, kill count). A container removed without
            // being lost (`nodossier`: disbanded, converted) takes its passengers out the same way.
            unit.hangar.forEach { passenger ->
                passenger.destroyed = true
                if (unit.nodossier) passenger.nodossier = true
                recordLoss(passenger)
            }
            CarrierHangars.sinkWith(unit)
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
