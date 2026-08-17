package org.osada.model

import org.osada.GroundCondition
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.TerrainEx.movementCostTable
import org.osada.movTableDry
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.terrainEntrenchment
import org.osada.terrainInitiative
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json
import kotlin.math.roundToInt

/**
 * Per-efile terrain entrenchment, initiative cap, supply factor and MOVEMENT COST, imported from
 * OG's `TerrainEx.txt` (`tools/og-import/terrain_ex_to_json.py` ->
 * `resources/terrain-ex/<tag>.json`).
 *
 * Falls back to PM's own baseline ([terrainEntrenchment] / [terrainInitiative] / [movTableDry] and
 * friends, one shared set of tables for every efile) for any efile that ships no TerrainEx data --
 * GCE, OLGCW and OLGWW2 never had the file at all, and not every efile that does has been run
 * through the importer -- and for any terrain id the data omits. [terrainInitiative] is
 * element-for-element identical to the initiative-cap column of every TerrainEx.txt we import, so
 * that fallback is exact, not approximate (see `docs/design/terrain-supply-and-initiative.md` §1).
 * The movement-cost fallback is NOT exact: see [movementCostTable].
 */
object TerrainEx {
    private const val PATH = "resources/terrain-ex/"
    private val httpSuccessRange = 200..299

    private var loadedForEfile: String? = null
    private var baseEntrenchByTerrain: Map<Int, Int> = emptyMap()
    private var initiativeCapByTerrain: Map<Int, Int> = emptyMap()
    private var supplyFactorByTerrain: Map<Int, Int> = emptyMap()
    private var supplyModifiers: Map<String, Int> = emptyMap()

    /** Base entrenchment for [terrain] under the currently active efile ([Equipment.name]). */
    fun baseEntrenchment(terrain: Int): Int {
        loadIfNeeded()
        return baseEntrenchByTerrain[terrain] ?: terrainEntrenchment.getOrElse(terrain) { 0 }
    }

    /** Initiative cap for [terrain] under the currently active efile ([Equipment.name]) --
     *  the highest initiative either combatant can effectively fight at when the defender
     *  stands on this terrain. */
    fun initiativeCap(terrain: Int): Int {
        loadIfNeeded()
        return initiativeCapByTerrain[terrain] ?: terrainInitiative.getOrElse(terrain) { CAP_NONE }
    }

    /**
     * Percentage supply factor (0..100) for [terrain] plus [road]/[rail] presence and [ground]
     * condition, under the currently active efile. OG's own wording: modifiers are "added to
     * terrain supply percentage" (`docs/design/terrain-supply-and-initiative.md` §3.3) -- road and
     * rail do NOT stack (`INFERENCE`: every efile that sets both uses the identical 30/20 value, so
     * the distinction is theoretical, but "added" does not mean the larger of the two doubled).
     *
     * Falls back per-terrain-id to PM's own flat off-city rule (city 100, else `100 /
     * OFF_CITY_SUPPLY_PENALTY` rounded) -- the exact number `SupplyRules` used before this existed
     * -- so an efile with no TerrainEx data (GCE/OLGCW/OLGWW2/adlerkorps) or missing a terrain id
     * sees no behaviour change at all.
     */
    fun supplyFactor(
        terrain: Int,
        road: Int,
        rail: Int,
        ground: Int,
    ): Int = supplyFactorBreakdown(terrain, road, rail, ground).totalPercent

    /** Which of the two mutually exclusive linear-infrastructure modifiers actually applied. */
    enum class SupplyRoadKind { NONE, ROAD, RAIL }

    /**
     * The individually named terms behind one [supplyFactor] result, so a tooltip can list exactly
     * the factors that participated instead of reciting a hard-coded table
     * (`docs/design/action-affordances-and-objectives.md` §4). [totalPercent] is the value the rules
     * use; the terms are informational and may sum past the 0..100 clamp.
     */
    data class SupplyFactorBreakdown(
        val basePercent: Int,
        val baseFromEfileData: Boolean,
        val roadKind: SupplyRoadKind,
        val roadPercent: Int,
        val groundPercent: Int,
        val totalPercent: Int,
    ) {
        internal companion object {
            /** Lays the active efile's `supply_modifiers` over an already-resolved [base] terrain
             *  percentage. Reads [supplyModifiers] directly, so callers cannot pass a stale map. */
            fun of(
                base: Int,
                baseFromEfileData: Boolean,
                road: Int,
                rail: Int,
                ground: Int,
                useEfileModifiers: Boolean = true,
            ): SupplyFactorBreakdown {
                // PM had no road/rail/weather supply modifiers at all, so selecting its flat model
                // has to drop them too rather than layer OG's modifiers onto a PM base.
                val modifiers = if (useEfileModifiers) supplyModifiers else emptyMap()
                val roadBonus = if (road != RoadType.NONE.value) modifiers["road"] ?: 0 else 0
                val railBonus = if (rail != RoadType.NONE.value) modifiers["rail"] ?: 0 else 0
                // Road and rail do not stack: the larger single modifier wins. Ties resolve to road
                // so the reported kind is deterministic; every shipped efile that sets both uses
                // the same value.
                val roadRailBonus = maxOf(roadBonus, railBonus)
                val roadKind =
                    when {
                        roadRailBonus == 0 -> SupplyRoadKind.NONE
                        roadBonus >= railBonus -> SupplyRoadKind.ROAD
                        else -> SupplyRoadKind.RAIL
                    }
                val groundBonus =
                    when (ground) {
                        GroundCondition.FROZEN.value -> modifiers["frozen"] ?: 0
                        GroundCondition.MUD.value -> modifiers["mud"] ?: 0
                        else -> 0
                    }
                return SupplyFactorBreakdown(
                    basePercent = base,
                    baseFromEfileData = baseFromEfileData,
                    roadKind = roadKind,
                    roadPercent = roadRailBonus,
                    groundPercent = groundBonus,
                    totalPercent = (base + roadRailBonus + groundBonus).coerceIn(0, PERCENT_MAX),
                )
            }
        }
    }

    fun supplyFactorBreakdown(
        terrain: Int,
        road: Int,
        rail: Int,
        ground: Int,
    ): SupplyFactorBreakdown {
        loadIfNeeded()
        // A ruleset may select PM's flat off-city formula instead of this efile's own factors
        // (`docs/design/ruleset-profiles.md` §1). That is not a new rule: it is the very fallback
        // this loader already applies to the five efiles that ship no TerrainEx data.
        val useEfileFactors = ActiveRuleset.currentOrNull()?.flag(RuleKey.SUPPLY_MODEL) != false
        val efileBase = if (useEfileFactors) supplyFactorByTerrain[terrain] else null
        // PM's own flat off-city rule is the per-terrain-id fallback (see the KDoc on [supplyFactor]).
        val base =
            efileBase ?: if (terrain == TerrainType.CITY.value) {
                PERCENT_MAX
            } else {
                (PERCENT_MAX / OFF_CITY_SUPPLY_PENALTY).roundToInt()
            }
        return SupplyFactorBreakdown.of(base, efileBase != null, road, rail, ground, useEfileFactors)
    }

    /**
     * PM's movement table for [ground], with the active efile's own OG `[terrain-cost]` laid over
     * it. See [TerrainMovementCost] for what the overlay does, which two of OG's rows it declines
     * to take and why. Falls back to PM's table unchanged for any efile with no TerrainEx data.
     */
    fun movementCostTable(ground: Int): List<List<Int>> {
        loadIfNeeded()
        return TerrainMovementCost.table(ground)
    }

    // Synchronous, like `EquipmentAvailability`'s allowlist fetch: a small per-efile file, read
    // lazily on first use after the efile changes rather than threaded through scenario loading.
    private fun loadIfNeeded() {
        val efile = Equipment.name
        if (efile == loadedForEfile) return
        loadedForEfile = efile
        val text = fetch(efile)
        baseEntrenchByTerrain = text?.let { parseField(it, "base_entrench") } ?: emptyMap()
        initiativeCapByTerrain = text?.let { parseField(it, "initiative_cap") } ?: emptyMap()
        supplyFactorByTerrain = text?.let { parseField(it, "supply_factor_pct") } ?: emptyMap()
        supplyModifiers = text?.let(::parseSupplyModifiers) ?: emptyMap()
        TerrainMovementCost.load(text)
    }

    private fun fetch(efile: String): String? {
        val request = XMLHttpRequest()
        request.open("GET", "$PATH${efile.removePrefix("eqp-")}.json", false)
        request.send(null)
        val status = request.status.toInt()
        return if (status in httpSuccessRange || status == 0) request.responseText else null
    }

    /** `{"terrain": {"<id>": {"<field>": n, ...}, ...}}` -> `{id: n}`. Missing or malformed input
     *  yields whatever entries it does have, never throws. */
    internal fun parseField(
        text: String,
        field: String,
    ): Map<Int, Int> {
        val terrain = JSON.parse<Json>(text).asDynamic().terrain
        if (terrain == null || terrain == undefined) return emptyMap()
        val map = mutableMapOf<Int, Int>()
        js("Object.keys")(terrain).unsafeCast<Array<String>>().forEach { id ->
            val tid = id.toIntOrNull()
            val entry = terrain[id]
            val value = (if (entry != null && entry != undefined) entry[field] else null) as? Int
            if (tid != null && value != null) map[tid] = value
        }
        return map
    }

    /** `{"supply_modifiers": {"road": 20, "rail": 20, "frozen": -30, "mud": -30}}` -> that map,
     *  verbatim. Missing or malformed input yields whatever entries it does have, never throws. */
    internal fun parseSupplyModifiers(text: String): Map<String, Int> {
        val modifiers = JSON.parse<Json>(text).asDynamic().supply_modifiers
        if (modifiers == null || modifiers == undefined) return emptyMap()
        val map = mutableMapOf<String, Int>()
        js("Object.keys")(modifiers).unsafeCast<Array<String>>().forEach { key ->
            (modifiers[key] as? Int)?.let { map[key] = it }
        }
        return map
    }

    // Defaults to the CURRENT [Equipment.name] rather than a fixed sentinel, so the next
    // [baseEntrenchment]/[initiativeCap]/[supplyFactor] call sees no efile change and does not
    // clobber this with a real fetch.
    internal fun setForTest(
        map: Map<Int, Int>,
        efile: String = Equipment.name,
        initiativeCapMap: Map<Int, Int> = initiativeCapByTerrain,
        supplyFactorMap: Map<Int, Int> = supplyFactorByTerrain,
        supplyModifierMap: Map<String, Int> = supplyModifiers,
    ) {
        baseEntrenchByTerrain = map
        initiativeCapByTerrain = initiativeCapMap
        supplyFactorByTerrain = supplyFactorMap
        supplyModifiers = supplyModifierMap
        loadedForEfile = efile
    }

    internal fun resetForTest() {
        baseEntrenchByTerrain = emptyMap()
        initiativeCapByTerrain = emptyMap()
        supplyFactorByTerrain = emptyMap()
        supplyModifiers = emptyMap()
        TerrainMovementCost.load(null)
        loadedForEfile = null
    }

    private const val CAP_NONE = 99
    private const val PERCENT_MAX = 100
    private const val OFF_CITY_SUPPLY_PENALTY = 1.3
}
