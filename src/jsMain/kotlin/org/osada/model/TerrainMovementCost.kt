package org.osada.model

import org.osada.GroundCondition
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.movTableDry
import org.osada.movTableFrozen
import org.osada.movTableMud
import kotlin.js.Json

/**
 * The `terrain_cost` / `roads_cost` half of an efile's TerrainEx data: PM's shared movement table
 * with OG's own per-efile move costs laid over it. Split from [TerrainEx] purely to keep that object
 * within the project's function-count limit -- [TerrainEx] owns the fetch and hands the raw JSON
 * here via [load]; nothing else should call into this directly, use [TerrainEx.movementCostTable].
 *
 * PM ships ONE movement table for every efile ([movTableDry] / [movTableFrozen] / [movTableMud]);
 * OG gives each efile its own `[terrain-cost]`, and they differ substantially -- measured over the
 * six efiles that ship TerrainEx data, between 7 (KAISER) and 156 (BASEKORP) of the 13x18 cells
 * disagree with PM. The reported symptom was naval: PM marks IMPASSABLE_RIVER 255 for every
 * movement method, so a river gunboat could not sail its own river wherever the map author drew it
 * as terrain 15 rather than terrain 10. BASEKORP puts that cell at 1 for Coastal/Naval and 2 for
 * Deep Naval -- in `Falciu 1` the Prut is an unbroken mix of both terrains, so the Shtorm TB was
 * stopped mid-river by hexes OG lets it cross.
 */
internal object TerrainMovementCost {
    /** `{movmethod: {ground name: [cost per terrain 0..18]}}`, verbatim from OG. */
    private var terrainCost: Map<Int, Map<String, List<Int>>> = emptyMap()

    /** `{ground name: [road cost per movmethod]}` -- OG's `[roads-cost]` section, verbatim. */
    private var roadsCost: Map<String, List<Int>> = emptyMap()

    /** Replaces the held data from one efile's TerrainEx JSON. Null (or JSON without these
     *  sections) clears it, which makes [table] hand back PM's baseline untouched. */
    fun load(text: String?) {
        terrainCost = text?.let(::parseTerrainCost) ?: emptyMap()
        roadsCost = text?.let { parseGroundRows(it, "roads_cost") } ?: emptyMap()
    }

    /**
     * PM's movement table for [ground], with the loaded efile's OG costs laid over it.
     *
     * Two of OG's rows are deliberately NOT taken, and both are policy, not oversight:
     *
     *  - **Air ([MovMethod.AIR])**. OSADA does not resolve air movement through this table at all
     *    ([org.osada.rules.MoveRangeCalculation] gives air a flat cost of 1 and consults only the
     *    air layer's occupancy), so PM's all-1 row is what every table-reading path assumes for
     *    aircraft. Some efiles put something else entirely in that slot: AG's row 5 is
     *    `ocean 255, river 254, impassable 255` while AG's 76 FIGHTER records all declare movmethod
     *    5 -- taking it would leave AG aircraft unable to be reinforced onto a coastal hex
     *    ([org.osada.rules.ReinforcementDeployment] does read the table for air units) while their
     *    move range, which ignores the table, still flew them over the ocean freely.
     *  - **Rail ([MovMethod.RAIL])**. OSADA's row 12 is an intentional all-255 sentinel; a train's
     *    real legality gate is the isTrain + `hex.rail` check in `MovementRules.getMoveRange` and
     *    its siblings (see Constants.kt). OG's own Train row is `port 254, everything else 255`,
     *    which would put a train in a port hex through the table instead.
     *
     * OG's terrain columns run 0..18 (`custom`, `shallow`); OSADA's [TerrainType] stops at
     * ROUGH(16) and reuses index 17 of each row for the ROAD cost, which OG keeps in its own
     * `[roads-cost]` section keyed by movement method. Columns 0..16 are copied across, index 17
     * comes from `roads_cost`, and OG 17/18 are dropped -- no shipped scenario emits either
     * (verified across every scenario XML under `resources/scenarios/data`: only 1..16 appear).
     */
    fun table(ground: Int): List<List<Int>> {
        val baseline =
            when (ground) {
                GroundCondition.FROZEN.value -> movTableFrozen
                GroundCondition.MUD.value -> movTableMud
                else -> movTableDry
            }
        if (terrainCost.isEmpty()) return baseline
        val groundKey =
            when (ground) {
                GroundCondition.FROZEN.value -> "frozen"
                GroundCondition.MUD.value -> "mud"
                else -> "dry"
            }
        return baseline.mapIndexed { method, pmRow ->
            if (method in METHODS_KEEPING_PM_ROW) pmRow else overlayRow(pmRow, method, groundKey)
        }
    }

    /** [pmRow] with every terrain column OG actually supplies replaced, plus the road column. */
    private fun overlayRow(
        pmRow: List<Int>,
        method: Int,
        groundKey: String,
    ): List<Int> {
        val ogRow = terrainCost[method]?.get(groundKey) ?: return pmRow
        val roadCost = roadsCost[groundKey]?.getOrNull(method)
        return pmRow.mapIndexed { terrain, pmCost ->
            if (terrain == ROAD_COLUMN) roadCost ?: pmCost else ogRow.getOrNull(terrain) ?: pmCost
        }
    }

    /** `{"terrain_cost": {"<movmethod>": {"dry": [n, ...], ...}}}` -> `{method: {ground: costs}}`.
     *  Missing or malformed input yields whatever entries it does have, never throws. */
    internal fun parseTerrainCost(text: String): Map<Int, Map<String, List<Int>>> {
        val costs = JSON.parse<Json>(text).asDynamic().terrain_cost
        if (costs == null || costs == undefined) return emptyMap()
        val map = mutableMapOf<Int, Map<String, List<Int>>>()
        js("Object.keys")(costs).unsafeCast<Array<String>>().forEach { method ->
            val id = method.toIntOrNull() ?: return@forEach
            val rows = groundRows(costs[method])
            if (rows.isNotEmpty()) map[id] = rows
        }
        return map
    }

    /** `{"<field>": {"dry": [n, ...], "frozen": [...], "mud": [...]}}` -> that map, verbatim. Rows
     *  are taken at whatever length OG wrote them -- a short row means "OG said nothing about the
     *  remaining terrains", which [overlayRow] must distinguish from "OG said 255". */
    internal fun parseGroundRows(
        text: String,
        field: String,
    ): Map<String, List<Int>> {
        val section = JSON.parse<Json>(text).asDynamic()[field]
        return if (section == null || section == undefined) emptyMap() else groundRows(section)
    }

    private fun groundRows(section: dynamic): Map<String, List<Int>> {
        val rows = mutableMapOf<String, List<Int>>()
        GROUND_KEYS.forEach { key ->
            val raw = section[key]
            if (raw != null && raw != undefined) {
                rows[key] = (raw as Array<Int>).toList()
            }
        }
        return rows
    }

    internal fun setForTest(
        terrainCostMap: Map<Int, Map<String, List<Int>>> = terrainCost,
        roadsCostMap: Map<String, List<Int>> = roadsCost,
    ) {
        terrainCost = terrainCostMap
        roadsCost = roadsCostMap
    }

    /** Index 17 of every [movTableDry] row: PM's ROAD bonus column, which OG keeps in its own
     *  `[roads-cost]` section instead. Not a terrain id -- [TerrainType] stops at ROUGH(16). */
    private const val ROAD_COLUMN = 17

    private val GROUND_KEYS = listOf("dry", "frozen", "mud")

    /** Movement methods whose PM row is kept even when the efile ships its own -- see [table] for
     *  why each one is policy rather than an omission. */
    private val METHODS_KEEPING_PM_ROW = setOf(MovMethod.AIR.value, MovMethod.RAIL.value)
}
