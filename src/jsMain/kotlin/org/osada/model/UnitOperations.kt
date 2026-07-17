package org.osada.model

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.rules.GameRules
import kotlin.js.json

/**
 * Unit lifecycle operations: mount/unmount, embark/disembark, upgrade, disband, deploy,
 * resupply, reinforce, and campaign core-unit list management. Extracted from the former
 * [GameMap] god-class (SRP).
 */
internal class UnitOperations(private val gameMap: GameMap) {

    fun mountUnit(unit: GameUnit) {
        mountUnitHandler(unit)
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        gameMap.selectUnit(unit)
    }

    fun mountUnitHandler(unit: GameUnit) {
        GameRules.setSpotRange(gameMap, unit, false)
        unit.mount()
        GameRules.setSpotRange(gameMap, unit, true)
    }

    fun unmountUnit(unit: GameUnit) {
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        unmountUnitHandler(unit)
        gameMap.selectUnit(unit)
    }

    fun unmountUnitHandler(unit: GameUnit) {
        GameRules.setSpotRange(gameMap, unit, false)
        unit.unmount()
        GameRules.setSpotRange(gameMap, unit, true)
    }

    fun embarkUnit(unit: GameUnit): Boolean {
        var result = false
        if (unit.carrier < 0) {
            unit.carrier = -unit.carrier
            result = true
        } else {
            val type = GameRules.getEmbarkType(gameMap, unit)
            if (type > 0) {
                if (!unit.embark(UnitClass.values().find { it.value == type } ?: return false)) return false
                when (type) {
                    UnitClass.AIR_TRANSPORT.value ->
                        unit.player?.airTransports =
                            unit.player?.airTransports?.minus(1) ?: 0
                    UnitClass.NAVAL_TRANSPORT.value ->
                        unit.player?.navalTransports =
                            unit.player?.navalTransports?.minus(1) ?: 0
                }
                result = true
            }
        }
        if (result) {
            gameMap.delMoveSel()
            gameMap.delAttackSel()
            gameMap.selectUnit(unit)
        }
        return result
    }

    fun disembarkUnit(unit: GameUnit): Boolean {
        val positions = GameRules.getDisembarkPositions(gameMap, unit)
        if (positions.isEmpty()) return false
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        positions.forEach { cell ->
            gameMap.currentMoveRange.add(cell)
            gameMap.map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isMoveSel = true
        }
        unit.toggleEmbark()
        return true
    }

    fun upgradeUnit(unitId: Int, newEqid: Int, transportEqid: Int): Boolean {
        val unit = gameMap.getUnitById(unitId) ?: return false
        if (!unit.player!!.upgradeUnit(unit, newEqid, transportEqid)) return false
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

    fun deployPlayerUnit(player: Player, index: Int, row: Int, col: Int): Boolean {
        if (player == null) return false
        val list = player.getCoreUnitList()
        if (index < 0 || index >= list.size) return false
        return deployPlayerUnit(player, list[index], row, col)
    }

    fun deployPlayerUnit(player: Player, unit: GameUnit, row: Int, col: Int): Boolean {
        if (player == null || unit.isDeployed) return false
        val hex = gameMap.map?.getOrNull(row)?.getOrNull(col) ?: return false
        val isAir = GameRules.isAir(unit)
        if ((isAir && hex.airunit != null) || (!isAir && hex.unit != null)) return false
        if (!isAir && hex.terrain == TerrainType.OCEAN.value) {
            unit.embark(UnitClass.NAVAL_TRANSPORT)
        }
        hex.setUnit(unit)
        gameMap.addUnit(unit)
        return true
    }

    fun deployNewUnitByEqId(eqid: Int, row: Int, col: Int, owner: Int) {
        val unit = GameUnit(eqid)
        unit.owner = owner
        gameMap.map?.getOrNull(row)?.getOrNull(col)?.setUnit(unit)
        gameMap.addUnit(unit)
    }

    fun deployReinforcement(unit: GameUnit, row: Int, col: Int): Cell? {
        if (unit == null) return null
        val pos = GameRules.getReinforcementDeployPositions(gameMap, unit, row, col) ?: return null
        gameMap.map?.getOrNull(pos.row)?.getOrNull(pos.col)?.setUnit(unit)
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

    fun reinforceUnit(unit: GameUnit, overStrength: Boolean): dynamic {
        val strengthValue = GameRules.getReinforceValue(gameMap, unit, overStrength)
        val supply = GameRules.getResupplyValue(gameMap, unit)
        val player = unit.player ?: return json(Pair("strength", 0), Pair("ammo", 0), Pair("fuel", 0))
        val reinforced = player.reinforceUnit(unit, strengthValue, overStrength)
        // Only commit (and forfeit undo) once we know the unit was actually reinforced.
        if (reinforced <= 0) return json(Pair("strength", 0), Pair("ammo", 0), Pair("fuel", 0))
        gameMap.undoState.unit = null
        player.resupplyUnit(unit, supply)
        gameMap.delAttackSel()
        gameMap.delMoveSel()
        return json(Pair("strength", reinforced), Pair("ammo", supply.ammo), Pair("fuel", supply.fuel))
    }

    fun buildCoreUnitList(player: Player) {
        gameMap.units.filter {
            it.player?.id == player.id && it.getHex()?.isDeployment == player.id
        }.forEach { player.addCoreUnit(it) }
    }

    /**
     * Lift the player's core units OFF the map back into the (undeployed) tray, so the player
     * deploys them by hand — the Open General campaign start behaviour. On the FIRST campaign
     * scenario [buildCoreUnitList] makes on-deploy-hex units core but leaves them DEPLOYED, unlike
     * later scenarios where the core arrives undeployed (setPlayerToHQ at the previous scenario's
     * end + restoreCoreUnitList). This makes the first scenario consistent: same tray + buy phase.
     * Safe when there are no core units (e.g. no deploy hexes) — it simply does nothing.
     */
    fun undeployCoreUnits(player: Player) {
        player.getCoreUnitList().toList().forEach { unit ->
            val pos = unit.getPos()
            if (pos != null) {
                GameRules.setZOCRange(gameMap, unit, false)
                GameRules.setSpotRange(gameMap, unit, false)
                gameMap.map?.getOrNull(pos.row)?.getOrNull(pos.col)?.delUnit(unit)
            }
            unit.isDeployed = false
            gameMap.units.remove(unit)
        }
        gameMap.updateUnitList()
    }

    fun restoreCoreUnitList(player: Player, saved: List<dynamic>) {
        gameMap.units.filter { it.isCore && it.isDeployed }.forEach { player.addCoreUnit(it) }
        saved.filter { !(it.isDeployed as? Boolean ?: false) }.forEach { savedUnit ->
            val unit = GameUnit((savedUnit.eqid as? Int) ?: 0)
            unit.id = savedUnit.id as? Int ?: -1
            unit.owner = savedUnit.owner as? Int ?: player.id
            unit.flag = savedUnit.flag as? Int ?: (player.country + 1)
            unit.strength = savedUnit.strength as? Int ?: 10
            unit.experience = savedUnit.experience as? Int ?: 0
            unit.leader = savedUnit.leader as? Int ?: -1
            unit.carrier = savedUnit.carrier as? Int ?: 0
            unit.isMounted = savedUnit.isMounted as? Boolean ?: false
            unit.isCore = true
            unit.isDeployed = false
            unit.hasOverstrength = savedUnit.hasOverstrength as? Boolean ?: false
            unit.customName = savedUnit.customName as? String // optional key (rename feature)
            unit.player = player
            savedUnit.transport?.let { t ->
                val teqid = t.eqid as? Int ?: 0
                if (teqid > 0) {
                    unit.setTransport(teqid)
                    unit.transport?.ammo = t.ammo as? Int ?: 0
                    unit.transport?.fuel = t.fuel as? Int ?: 0
                }
            }
            player.addCoreUnit(unit)
        }
    }

    fun removeNonCampaignUnits(player: Player) {
        var removed = false
        gameMap.units.filter { it.player?.id != player.id || !it.isCore }.forEach { unit ->
            val hex = unit.getHex()
            if (hex?.isDeployment == player.id) {
                hex.delUnit(unit)
                unit.destroyed = true
                unit.nodossier = true
                removed = true
            }
        }
        if (removed) gameMap.updateUnitList()
    }
}
