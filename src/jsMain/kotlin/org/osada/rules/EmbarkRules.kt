package org.osada.rules

import org.osada.EmbarkType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.movTable

/**
 * Embark/disembark eligibility for air and naval transports. Split out purely to keep
 * [MovementRules] within the project's function-count/class-size limits.
 */
internal object EmbarkRules {
    // movTable sentinel cost (see Constants.kt movTableDry doc): 255 = impassable via this table.
    private const val IMPASSABLE_TERRAIN_COST = 255

    /** The carrier class [unit] could embark onto at its current hex, or NONE. */
    fun getEmbarkType(
        map: GameMap,
        unit: GameUnit,
    ): Int {
        val pos = unit.getPos()
        val hex = if (pos == null) null else map.map?.getOrNull(pos.row)?.getOrNull(pos.col)
        if (hex == null) return UnitClass.NONE.value
        val data = unit.unitData()
        val canEmbarkAir =
            hex.terrain == TerrainType.AIRFIELD.value &&
                (unit.player?.airTransports ?: 0) > 0 &&
                data.embark > EmbarkType.NAVAL.value &&
                hex.airunit == null
        val canEmbarkNaval =
            hex.terrain == TerrainType.PORT.value &&
                (unit.player?.navalTransports ?: 0) > 0 &&
                data.embark > EmbarkType.NONE.value
        return when {
            canEmbarkAir -> UnitClass.AIR_TRANSPORT.value
            canEmbarkNaval -> UnitClass.NAVAL_TRANSPORT.value
            else -> UnitClass.NONE.value
        }
    }

    /** Cells an embarked transport [unit] may disembark its cargo into. */
    fun getDisembarkPositions(
        map: GameMap,
        unit: GameUnit,
    ): List<Cell> {
        val result = mutableListOf<Cell>()
        val data = unit.unitData()
        val isTransport = data.uclass == UnitClass.AIR_TRANSPORT.value || data.uclass == UnitClass.NAVAL_TRANSPORT.value
        val movementMethod = Equipment.equipment[unit.eqid]?.movmethod
        val pos = unit.getPos()
        val cannotDisembark = unit.hasMoved || !isTransport
        if (cannotDisembark || movementMethod == null || pos == null) return result

        val movementTable = movTable[movementMethod]
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (hex.unit == null && movementTable[hex.terrain] < IMPASSABLE_TERRAIN_COST) {
                result.add(cell)
            }
        }
        return result
    }

    fun canEmbark(
        map: GameMap,
        unit: GameUnit,
    ): Boolean = getEmbarkType(map, unit) > UnitClass.NONE.value || unit.carrier < 0

    fun canDisembark(
        map: GameMap,
        unit: GameUnit,
    ): Boolean = getDisembarkPositions(map, unit).isNotEmpty()
}
