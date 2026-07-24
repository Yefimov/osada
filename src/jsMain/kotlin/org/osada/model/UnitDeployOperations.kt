package org.osada.model

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.getReinforceValue
import org.osada.rules.getReinforcementDeployPositions
import org.osada.rules.getResupplyValue
import org.osada.rules.isAir
import kotlin.js.json

/**
 * Upgrade, disband, deploy, resupply and reinforce unit operations. Split from [UnitOperations]
 * (SRP / function-count limits).
 */
internal class UnitDeployOperations(
    private val gameMap: GameMap,
) {
    fun upgradeUnit(
        unitId: Int,
        newEqid: Int,
        transportEqid: Int,
    ): Boolean {
        val unit = gameMap.getUnitById(unitId)
        if (unit == null || !unit.player!!.upgradeUnit(unit, newEqid, transportEqid)) return false
        gameMap.undoState.unit = null
        gameMap.unitImages.add(unit.eqid)
        unit.transport?.let { gameMap.unitImages.add(it.eqid) }
        if (unit.carrier > 0) gameMap.unitImages.add(unit.carrier)
        return true
    }

    fun disbandUnit(unitId: Int): Boolean {
        val unit = gameMap.getUnitById(unitId) ?: return false
        unit.destroyed = true
        unit.nodossier = true
        gameMap.undoState.unit = null
        unit.player?.sellUnit(unit)
        gameMap.delCurrentUnit()
        gameMap.updateUnitList()
        return true
    }

    fun deployPlayerUnit(
        player: Player,
        index: Int,
        row: Int,
        col: Int,
    ): Boolean {
        val list = player.getCoreUnitList()
        if (index < 0 || index >= list.size) return false
        return deployPlayerUnit(player, list[index], row, col)
    }

    // player is unused in this overload's own body (ownership is set via gameMap.addUnit ->
    // unit.owner) but must stay in the signature: it's part of the exported
    // GameMap.deployPlayerUnit (@JsName("deployPlayerUnitByUnit")) surface and the sibling
    // index-based overload forwards its own `player` here positionally.
    @Suppress("UnusedParameter")
    fun deployPlayerUnit(
        player: Player,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Boolean {
        val hex = gameMap.map?.getOrNull(row)?.getOrNull(col)
        if (unit.isDeployed || hex == null) return false
        val isAir = GameRules.isAir(unit)
        val occupied = (isAir && hex.airunit != null) || (!isAir && hex.unit != null)
        if (!occupied) {
            if (!isAir && hex.terrain == TerrainType.OCEAN.value) {
                unit.embark(UnitClass.NAVAL_TRANSPORT)
            }
            hex.setUnit(unit)
            gameMap.addUnit(unit)
        }
        return !occupied
    }

    fun deployNewUnitByEqId(
        eqid: Int,
        row: Int,
        col: Int,
        owner: Int,
    ) {
        val unit = GameUnit(eqid)
        unit.owner = owner
        gameMap.map
            ?.getOrNull(row)
            ?.getOrNull(col)
            ?.setUnit(unit)
        gameMap.addUnit(unit)
    }

    fun deployReinforcement(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Cell? {
        val pos = GameRules.getReinforcementDeployPositions(gameMap, unit, row, col) ?: return null
        gameMap.map
            ?.getOrNull(pos.row)
            ?.getOrNull(pos.col)
            ?.setUnit(unit)
        gameMap.addUnit(unit)
        return pos
    }

    fun resupplyUnit(unit: GameUnit): Supply {
        gameMap.undoState.unit = null
        val supply = GameRules.getResupplyValue(gameMap, unit)
        unit.player?.resupplyUnit(unit, supply)
        gameMap.delAttackSel()
        gameMap.delMoveSel()
        return supply
    }

    fun reinforceUnit(
        unit: GameUnit,
        overStrength: Boolean,
    ): dynamic {
        val strengthValue = GameRules.getReinforceValue(gameMap, unit, overStrength)
        val supply = GameRules.getResupplyValue(gameMap, unit)
        val player = unit.player
        val reinforced = player?.reinforceUnit(unit, strengthValue, overStrength) ?: 0
        // Only commit (and forfeit undo) once we know the unit was actually reinforced.
        if (player == null || reinforced <= 0) return json(Pair("strength", 0), Pair("ammo", 0), Pair("fuel", 0))
        gameMap.undoState.unit = null
        player.resupplyUnit(unit, supply)
        gameMap.delAttackSel()
        gameMap.delMoveSel()
        return json(Pair("strength", reinforced), Pair("ammo", supply.ammo), Pair("fuel", supply.fuel))
    }
}
