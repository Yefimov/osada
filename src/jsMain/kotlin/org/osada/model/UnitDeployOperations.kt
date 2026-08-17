package org.osada.model

import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.movTable
import org.osada.rules.GameRules
import org.osada.rules.getReinforceValue
import org.osada.rules.getReinforcementDeployPositions
import org.osada.rules.getResupplyValue
import org.osada.rules.isAir
import kotlin.js.json

/** `movTable` sentinel for "this movement method may never enter"; 254 is merely costly. */
private const val IMPASSABLE_TERRAIN_COST = 255

/**
 * Whether [unit]'s movement method can exist on this hex's terrain at all.
 *
 * Deployment used to check ONLY occupancy, so any deploy hex accepted any unit: in `Falciu 1`
 * the author marks 3 town, 1 mountain, 1 clear and 2 river deploy hexes, and a river gunboat
 * could be placed on the town at (17,22) — `terrain 1`, Cantemir. Reinforcements have always
 * been gated this way (`ReinforcementDeployment.findTerrainDeployCell`); player deployment
 * simply never was.
 *
 * `254` means "costs the whole move allowance", not "forbidden" — only `255` is impassable, so
 * a leg unit deploying onto the mountain hex stays legal. Ocean keeps its amphibious exception:
 * a ground unit placed on ocean embarks rather than being refused.
 *
 * Top-level rather than private to [UnitDeployOperations] because the map renderer needs the
 * SAME answer to decide whether to light the hex up. When only this function knew the rule, the
 * deploy overlay highlighted every hex in the zone and `deployPlayerUnit` then refused most of
 * them silently — in `Falciu 1` the Shtorm TB's two legal river hexes were drawn identically to
 * the five town/clear ones that would reject it with no message at all.
 */
internal fun canDeployOnTerrain(
    unit: GameUnit,
    hex: Hex,
    isAir: Boolean,
): Boolean {
    val exempt = isAir || hex.terrain == TerrainType.OCEAN.value
    val cost = movTable.getOrNull(unit.unitData().movmethod)?.getOrNull(hex.terrain)
    return exempt || cost == null || cost != IMPASSABLE_TERRAIN_COST
}

/**
 * Upgrade, disband, deploy, resupply and reinforce unit operations. Split from the former
 * unit-operations component (SRP / function-count limits).
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
        gameMap.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
        gameMap.unitImages.add(unit.eqid)
        unit.transport?.let { gameMap.unitImages.add(it.eqid) }
        if (unit.carrier > 0) gameMap.unitImages.add(unit.carrier)
        return true
    }

    fun disbandUnit(unitId: Int): Boolean {
        val unit = gameMap.getUnitById(unitId) ?: return false
        unit.destroyed = true
        unit.nodossier = true
        gameMap.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
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
        val hex = gameMap.map?.getOrNull(row)?.getOrNull(col) ?: return false
        val isAir = GameRules.isAir(unit)
        val free = if (isAir) hex.airunit == null else hex.unit == null
        val allowed = !unit.isDeployed && free && canDeployOnTerrain(unit, hex, isAir)
        if (allowed) {
            if (!isAir && hex.terrain == TerrainType.OCEAN.value) {
                unit.embark(UnitClass.NAVAL_TRANSPORT)
            }
            hex.setUnit(unit)
            gameMap.addUnit(unit)
        }
        return allowed
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
        gameMap.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
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
        gameMap.undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
        player.resupplyUnit(unit, supply)
        gameMap.delAttackSel()
        gameMap.delMoveSel()
        return json(Pair("strength", reinforced), Pair("ammo", supply.ammo), Pair("fuel", supply.fuel))
    }
}
