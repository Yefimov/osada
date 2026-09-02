package org.osada.rules

import org.osada.EmbarkType
import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.RAIL_UNKNOWN
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
        // The PERMISSION comes from OG's own per-type bits where they are known, and falls back
        // to `embark` where they are not. `embark` is an ordinal, so it could only ever express
        // the highest permission a record carried -- it was silently refusing naval transport to
        // 15,037 records that OG allows it to (`EquipmentData.navalTransportable`).
        val canEmbarkAir =
            hex.terrain == TerrainType.AIRFIELD.value &&
                (unit.player?.airTransports ?: 0) > 0 &&
                permits(data.airTransportable, data.embark > EmbarkType.NAVAL.value) &&
                hex.airunit == null
        val canEmbarkNaval =
            hex.terrain == TerrainType.PORT.value &&
                (unit.player?.navalTransports ?: 0) > 0 &&
                permits(data.navalTransportable, data.embark > EmbarkType.NONE.value)
        return when {
            canEmbarkAir -> UnitClass.AIR_TRANSPORT.value
            canEmbarkNaval -> UnitClass.NAVAL_TRANSPORT.value
            else -> UnitClass.NONE.value
        }
    }

    /**
     * OG's per-type permission where it is known, [fallback] where it is not.
     *
     * `RAIL_UNKNOWN` (-1) means the record has no OG source — 4,140 of the merged rosters are
     * Panzer Marshal stock — and a RULE-level permission reads silence as permission
     * (`docs/og-sources.md`), so those keep exactly the behaviour `embark` gave them.
     */
    private fun permits(
        permission: Int,
        fallback: Boolean,
    ): Boolean = if (permission == RAIL_UNKNOWN) fallback else permission != 0

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
        val oceanBarred = data.uclass == UnitClass.AIR_TRANSPORT.value && oceanParadropForbidden()
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            val barred = oceanBarred && hex.terrain == TerrainType.OCEAN.value
            val enterable = hex.unit == null && movementTable[hex.terrain] < IMPASSABLE_TERRAIN_COST
            if (enterable && !barred) result.add(cell)
        }
        return result
    }

    /**
     * OG's *"avoid paratroop drops on ocean"* (`opt_no_paradrop_ocean`, `@1009` bit 4) — **19
     * deployed scenarios, 526 corpus-wide**.
     *
     * **The option exists because the drop IS otherwise possible**, and it is here: the eligible
     * cells above are filtered with the TRANSPORT's movement table, and an aircraft's table makes
     * ocean passable. So a paratroop battalion can currently be put into the sea, and 19 scenarios
     * asked OG not to allow it.
     *
     * Restricted to the AIR transport deliberately. A naval transport unloading onto ocean is
     * already impossible by the same filter (its cargo is going ashore, and the option's own words
     * are about paratroops), so widening it would be an inference where OG gave a sentence.
     *
     * Absent means permitted — `Scenario.paradropOnOceanAllowed` is deployed as a PERMISSION, the
     * same inversion `prototypes` and `subsneedlof` use, so a scenario whose source could not be
     * read keeps exactly the behaviour it had.
     */
    private fun oceanParadropForbidden(): Boolean = GameHolder.instance?.scenario?.paradropOnOceanAllowed == false

    fun canEmbark(
        map: GameMap,
        unit: GameUnit,
    ): Boolean = getEmbarkType(map, unit) > UnitClass.NONE.value || unit.carrier < 0

    fun canDisembark(
        map: GameMap,
        unit: GameUnit,
    ): Boolean = getDisembarkPositions(map, unit).isNotEmpty()
}
