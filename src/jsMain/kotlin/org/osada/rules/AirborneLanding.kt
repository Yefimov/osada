package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.WeatherCondition
import org.osada.hero.HeroCampaign
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.isWorking
import org.osada.movTable

/**
 * OG's **random airborne misdrop** (`opt_airborne_misdrop`, `Scenario.airborneMisdrop`), from the
 * author's [Features page](https://www.luis-guzman.com/OpenGen_Features.html), "Airborne units":
 *
 * - a unit dropped from an AIR transport onto a hex that is not an airfield lands where ordered
 *   with a chance of 70 / 40 / 30 / 20 % in fair / overcast / rain / snow, **+5 % per experience
 *   bar** and **+5 % with a leader**;
 * - otherwise it lands in a random enterable hex of the six around the target (impassable river
 *   and escarpment excluded, which the paratrooper's own movement table already says);
 * - if that hex holds an UNSPOTTED enemy, the drop ends in a surprise combat against it.
 *
 * Owner's policies for what the page leaves open (2026-10-08):
 *
 * - **Hidden enemy:** combat is mandatory. The unit comes down on the target hex beside the enemy
 *   and fights it surprised, through the ordinary move-surprise path (`GameMap.surpriseTarget`).
 *   A leader that makes the unit immune to surprise still applies, as it does to every surprise.
 * - **No enterable neighbour:** the unit drifts 2–3 hexes from the target and lands
 *   [heavily damaged][SCATTER_LOSS_DIVISOR]; with no land within 3 hexes (a drop over the sea) the
 *   formation is lost.
 * - **Undo:** a drop that rolled is final, whatever the roll said; otherwise undo would re-roll it.
 *
 * Every draw comes from [GameRandomSource], so the multiplayer host and a reload resolve the same
 * landing.
 */
object AirborneLanding {
    /** Fair, overcast, rain, snow: the published base chances, indexed by [WeatherCondition]. */
    private val BASE_CHANCE = listOf(70, 40, 30, 20)
    private const val PER_BAR = 5
    private const val LEADER_BONUS = 5
    private const val PERCENT = 100
    private const val IMPASSABLE_TERRAIN_COST = 255

    /** The farthest ring searched when no neighbour of the target can take the unit. */
    private const val MAX_DRIFT = 3

    /** A drifted unit loses half its strength, rounded up, keeping at least one point. */
    private const val SCATTER_LOSS_DIVISOR = 2

    enum class Kind {
        /** Landed where ordered. */
        ON_TARGET,

        /** Landed on a neighbour of the target. */
        SCATTERED,

        /** Came down on the target beside a hidden enemy it scattered onto, and fights it. */
        HIDDEN_ENEMY,

        /** No neighbour could take it; drifted 2–3 hexes and landed with heavy losses. */
        DRIFTED,

        /** No land within [MAX_DRIFT] hexes: the formation is lost. */
        LOST,
    }

    class Outcome(
        val kind: Kind,
        /** Where the unit comes down; null when [Kind.LOST]. */
        val landing: Cell?,
        /** The hidden enemy's hex for [Kind.HIDDEN_ENEMY]. */
        val enemyCell: Cell? = null,
        val losses: Int = 0,
        val chancePercent: Int = 0,
    )

    /** Whether the drop of [unit] onto [target] rolls at all. */
    fun applies(
        map: GameMap,
        unit: GameUnit,
        target: Cell,
    ): Boolean {
        val scenario = GameHolder.instance?.scenario?.takeIf { it.map === map }
        val fromAir = unit.carrier < 0 && Equipment.getEquipment(-unit.carrier)?.uclass == UnitClass.AIR_TRANSPORT.value
        val hex = map.map?.getOrNull(target.row)?.getOrNull(target.col)
        val onAirfield = hex?.isWorking(TerrainType.AIRFIELD.value) == true
        return scenario?.airborneMisdrop == true && fromAir && hex != null && !onAirfield
    }

    /** The published landing chance for [unit] in the current weather, clamped to 0..100. */
    fun chancePercent(unit: GameUnit): Int {
        val weather = GameHolder.instance?.scenario?.atmosferic ?: WeatherCondition.FAIR.value
        val base = BASE_CHANCE.getOrElse(weather) { BASE_CHANCE.last() }
        val led = unit.leader >= 0 || HeroCampaign.heroFor(unit) != null
        val chance = base + PER_BAR * UnitExperience.bars(unit) + if (led) LEADER_BONUS else 0
        return chance.coerceIn(0, PERCENT)
    }

    /** Rolls the drop of [unit] onto [target]. Call only when [applies]. [roll] is
     *  [GameRandomSource.nextInt] in play; a test passes its own to reach each branch. */
    fun resolve(
        map: GameMap,
        unit: GameUnit,
        target: Cell,
        roll: (Int) -> Int = GameRandomSource::nextInt,
    ): Outcome {
        val chance = chancePercent(unit)
        return if (roll(PERCENT) < chance) {
            Outcome(Kind.ON_TARGET, target, chancePercent = chance)
        } else {
            scatter(map, unit, target, chance, roll)
        }
    }

    private fun scatter(
        map: GameMap,
        unit: GameUnit,
        target: Cell,
        chance: Int,
        roll: (Int) -> Int,
    ): Outcome {
        val side = unit.player?.side ?: -1
        val neighbours = HexGeometry.getAdjacent(target.row, target.col).filter { canReceive(map, unit, it, side) }
        if (neighbours.isEmpty()) return drift(map, unit, target, chance, roll)
        val cell = neighbours[roll(neighbours.size)]
        val occupied = map.map!![cell.row][cell.col].unit != null
        return if (occupied) {
            Outcome(Kind.HIDDEN_ENEMY, target, enemyCell = cell, chancePercent = chance)
        } else {
            Outcome(Kind.SCATTERED, cell, chancePercent = chance)
        }
    }

    private fun drift(
        map: GameMap,
        unit: GameUnit,
        target: Cell,
        chance: Int,
        roll: (Int) -> Int,
    ): Outcome {
        val candidates =
            (2..MAX_DRIFT).firstNotNullOfOrNull { radius ->
                ring(map, target, radius).filter { isEmptyLand(map, unit, it) }.takeIf { it.isNotEmpty() }
            }
        return if (candidates == null) {
            Outcome(Kind.LOST, null, chancePercent = chance)
        } else {
            val losses = ((unit.strength + 1) / SCATTER_LOSS_DIVISOR).coerceAtMost(unit.strength - 1).coerceAtLeast(0)
            Outcome(Kind.DRIFTED, candidates[roll(candidates.size)], losses = losses, chancePercent = chance)
        }
    }

    /** A scatter neighbour: land the unit can enter, either empty or holding an enemy its side has
     *  not spotted. A visible or friendly occupant is a hex nobody would land on. */
    private fun canReceive(
        map: GameMap,
        unit: GameUnit,
        cell: Cell,
        side: Int,
    ): Boolean {
        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return false
        val occupant = hex.unit
        val hiddenEnemy =
            occupant != null &&
                occupant.player?.side != side &&
                !occupant.destroyed &&
                !ZoneOfControlContact.isKnownTo(occupant, side)
        return isLand(unit, hex.terrain) && (occupant == null || hiddenEnemy)
    }

    private fun isEmptyLand(
        map: GameMap,
        unit: GameUnit,
        cell: Cell,
    ): Boolean {
        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return false
        return hex.unit == null && isLand(unit, hex.terrain)
    }

    /** Enterable by the paratrooper's OWN movement method, not the aircraft's. */
    private fun isLand(
        unit: GameUnit,
        terrain: Int,
    ): Boolean {
        val method = Equipment.getEquipment(unit.eqid)?.movmethod ?: return false
        return movTable[method][terrain] < IMPASSABLE_TERRAIN_COST
    }

    private fun ring(
        map: GameMap,
        center: Cell,
        radius: Int,
    ): List<Cell> =
        (0 until map.rows).flatMap { row ->
            (0 until map.cols).mapNotNull { col ->
                Cell(row, col).takeIf { HexGeometry.distance(center.row, center.col, row, col) == radius }
            }
        }
}
