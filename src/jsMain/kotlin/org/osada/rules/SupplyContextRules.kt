package org.osada.rules

import org.osada.GameHolder
import org.osada.GroundCondition
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.TerrainEx
import kotlin.math.roundToInt

/** Where the supply actually comes from. Decides which factors can participate at all: an
 *  aircraft on an airfield and a ship at sea resupply at a flat rate with no terrain term. */
enum class SupplySource {
    /** Aircraft rearming at an airfield or carrier. */
    AIRFIELD_CARRIER,

    /** Naval unit supplied at sea. */
    NAVAL,

    /** Ground unit supplied from the hex it stands on. */
    GROUND,

    /** The unit has no map position, so nothing can be computed. */
    NONE,
}

/**
 * The exact, already-resolved terms behind one manual supply/reinforcement efficiency, so the
 * UI can name the factors that participated instead of parsing an English label or reciting a
 * hard-coded PM table (`docs/design/action-affordances-and-objectives.md` §4).
 *
 * [efficiencyPercent] is what the rules apply; every other field explains it. The terrain terms
 * are absent for air/naval supply and for a unit with no position.
 */
data class SupplyContext(
    val efficiencyPercent: Int,
    val source: SupplySource,
    val terrain: Int = -1,
    val terrainFactor: TerrainEx.SupplyFactorBreakdown? = null,
    val groundCondition: Int = GroundCondition.DRY.value,
    val adjacentEnemies: Int = 0,
    val adjacentEnemyDivisor: Double = 1.0,
)

/**
 * The local supply-efficiency terms -- terrain factor, road/rail, ground condition and adjacent
 * enemy pressure -- resolved once and shared by [SupplyRules]' resupply/reinforce math and by the
 * UI that explains it. Split out of `SupplyRules` so the arithmetic and its explanation stay a
 * single source of truth.
 */
object SupplyContextRules {
    private const val LIGHT_ENEMY_SUPPLY_PENALTY = 1.5
    private const val HEAVY_ENEMY_SUPPLY_PENALTY = 3.0
    private const val PERCENT = 100

    /** City ground supply is the one source the UI names separately from plain field supply, so it
     *  needs a token of its own alongside the [SupplySource] names. */
    const val CITY_SUPPLY_TOKEN = "GROUND_CITY"

    /** Stable, language-independent token for [context], for storing in the turn report. */
    fun logToken(context: SupplyContext): String =
        if (context.source == SupplySource.GROUND && context.terrain == TerrainType.CITY.value) {
            CITY_SUPPLY_TOKEN
        } else {
            context.source.name
        }

    /** Count of adjacent enemy units around [pos], used by both supply-penalty calculations. */
    internal fun countAdjacentEnemies(
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ): Int =
        HexGeometry.getAdjacent(pos.row, pos.col).count { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
            hex?.unit?.player?.side != unit.player?.side && hex?.unit != null
        }

    /** Terrain/adjacent-enemy penalty multiplier shared by resupply and reinforce math.
     *
     *  The terrain term is OG's own per-efile factor ([TerrainEx.supplyFactor]) rather than PM's
     *  flat off-city penalty -- for the four efiles with no `TerrainEx.txt`, and for any terrain id
     *  a shipped efile omits, [TerrainEx.supplyFactor] falls back to that exact flat number, so this
     *  is a strict extension, not a behaviour change, where OG data is absent
     *  (`docs/design/terrain-supply-and-initiative.md` §3.2). The enemy-pressure divisors are PM's
     *  own rule, with no OG equivalent, and are unaffected. */
    internal fun supplyPenaltyModifier(
        hex: Hex?,
        adjacentEnemies: Int,
    ): Double = terrainBreakdown(hex).totalPercent / PERCENT.toDouble() / adjacentEnemyDivisor(adjacentEnemies)

    /** Player-facing explanation of the exact local modifier used by manual supply/reinforcement.
     *  Every returned term is the one the rules just used -- the UI must never recompute or
     *  approximate them. */
    fun getSupplyContext(
        map: GameMap,
        unit: GameUnit,
    ): SupplyContext =
        when {
            UnitPredicates.isAir(unit) -> SupplyContext(PERCENT, SupplySource.AIRFIELD_CARRIER)
            UnitPredicates.isSea(unit) -> SupplyContext(PERCENT, SupplySource.NAVAL)
            else -> groundSupplyContext(map, unit)
        }

    private fun groundSupplyContext(
        map: GameMap,
        unit: GameUnit,
    ): SupplyContext {
        val pos = unit.getPos() ?: return SupplyContext(0, SupplySource.NONE)
        val hex = unit.getHex()
        val adjacentEnemies = countAdjacentEnemies(map, unit, pos)
        val breakdown = terrainBreakdown(hex)
        val divisor = adjacentEnemyDivisor(adjacentEnemies)
        return SupplyContext(
            efficiencyPercent = (breakdown.totalPercent / divisor).roundToInt(),
            source = SupplySource.GROUND,
            terrain = hex?.terrain ?: TerrainType.CLEAR.value,
            terrainFactor = breakdown,
            groundCondition = currentGroundCondition(),
            adjacentEnemies = adjacentEnemies,
            adjacentEnemyDivisor = divisor,
        )
    }

    private fun currentGroundCondition(): Int = GameHolder.instance?.scenario?.ground ?: GroundCondition.DRY.value

    /** PM's own enemy-pressure divisor; 1.0 when no enemy is adjacent. No OG equivalent. */
    private fun adjacentEnemyDivisor(adjacentEnemies: Int): Double =
        when {
            adjacentEnemies in 1..2 -> LIGHT_ENEMY_SUPPLY_PENALTY
            adjacentEnemies > 2 -> HEAVY_ENEMY_SUPPLY_PENALTY
            else -> 1.0
        }

    private fun terrainBreakdown(hex: Hex?): TerrainEx.SupplyFactorBreakdown =
        TerrainEx.supplyFactorBreakdown(
            hex?.terrain ?: TerrainType.CLEAR.value,
            hex?.road ?: RoadType.NONE.value,
            hex?.rail ?: RoadType.NONE.value,
            currentGroundCondition(),
        )
}
