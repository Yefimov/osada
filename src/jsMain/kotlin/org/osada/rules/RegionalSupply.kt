package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getPlayer
import org.osada.model.isWorking
import org.osada.movTable
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * **Regional supply** ([RuleKey.REGIONAL_SUPPLY]): OSADA's own logistics rule, not Open General's
 * (owner's request and parameters, 2026-10-09; design `docs/design/regional-supply.md` §3).
 *
 * A ground formation's resupply is capped by how hard the way back to the nearest friendly
 * **source** is -- a working city or port its side owns, or a railway station its side owns or
 * holds. Nothing is drawn: the route is a computation, and the unit card explains the result.
 *
 * **The route.** A cheapest path from the formation to a source, priced like a supply column on
 * wheels ([STEP_COST_ROW]): a road or railway hex costs 1, open ground 2, forest and hills more,
 * and a hex a column can only crawl into (swamp, mountain, an unbridged river) [CRAWL_COST].
 * Ocean, impassable rivers and escarpments carry nothing. The cost is what entering each hex after
 * the formation's own costs, the source included; a formation standing on a source pays nothing.
 *
 * **Enemies.** Owner: *"a unit of the enemy makes the imagined route around it dearer, so the
 * supply has to look for a way round"*, and *"if many enemies surround it, that effectively closes
 * it"*. So a hex held by a KNOWN enemy formation is closed, and each known enemy ground formation
 * next to a hex adds [ENEMY_AURA_COST] to entering it -- one enemy is a detour, three around a
 * pocket price every way out beyond reach. Only enemies the side can see count (owner: *"the
 * player sees the supply will be smaller, and goes looking for the enemy"*): a hidden unit
 * interdicts once it is found, and the overlay cannot be used to find it.
 *
 * **The result.** Up to [FULL_ACCESS_COST] the route costs nothing; past it every point costs
 * [FADE_PERCENT_PER_COST]% of the refill, down to an emergency [EMERGENCY_PERCENT]% that a cut-off
 * formation always keeps -- there is no hard-zero pocket in this first version. `SupplyRules` takes
 * the LOWER of this and the existing local factor (terrain, road, ground, adjacent enemies), so the
 * two do not multiply into an accidental zero. Ammunition and fuel only: replacements, aircraft,
 * ships and depot refills keep their own rules.
 *
 * These numbers are a first proposal for play-testing, not balance that has been tuned.
 */
internal object RegionalSupply {
    const val FULL_ACCESS_COST = 6
    const val FADE_PERCENT_PER_COST = 8
    const val EMERGENCY_PERCENT = 20
    const val ENEMY_AURA_COST = 4
    const val CRAWL_COST = 5
    private const val PERCENT = 100

    /** Beyond this route cost only the emergency floor is left, so the search stops there. */
    const val MAX_USEFUL_COST: Int = FULL_ACCESS_COST + (PERCENT - EMERGENCY_PERCENT) / FADE_PERCENT_PER_COST

    /** The movement-table row a supply column moves on. */
    private val STEP_COST_ROW = MovMethod.WHEELED.value
    private const val ROAD_COLUMN = 17
    private const val STOP = 254
    private const val IMPASSABLE = 255

    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.REGIONAL_SUPPLY, false)

    /**
     * How much of a refill reaches [unit]: [percent] of it, over a route costing [routeCost]
     * (null when no source is within reach, i.e. only the emergency floor arrives).
     */
    data class Access(
        val percent: Int,
        val routeCost: Int?,
    )

    fun access(
        map: GameMap,
        unit: GameUnit,
    ): Access {
        val cost = routeCost(map, unit)
        return Access(percentFor(cost), cost)
    }

    /** The share of a refill that survives a route of [cost]; null means no route. */
    fun percentFor(cost: Int?): Int =
        when {
            cost == null -> EMERGENCY_PERCENT
            cost <= FULL_ACCESS_COST -> PERCENT
            else -> (PERCENT - (cost - FULL_ACCESS_COST) * FADE_PERCENT_PER_COST).coerceAtLeast(EMERGENCY_PERCENT)
        }

    /**
     * The cheapest route cost from [unit] to one of its side's sources, or null when none is within
     * [MAX_USEFUL_COST]. A Dijkstra search outward from the formation that stops at the first
     * source it settles, so it touches only the neighbourhood that matters.
     */
    fun routeCost(
        map: GameMap,
        unit: GameUnit,
    ): Int? {
        val grid = map.map
        val pos = unit.getPos()
        val side = unit.player?.side
        if (grid == null || pos == null || side == null) return null
        val enemies = knownEnemyCells(map, side)
        val best = HashMap<Int, Int>()
        val queue = MinQueue()
        val start = key(map, pos.row, pos.col)
        best[start] = 0
        queue.push(0, start)
        var found: Int? = null
        while (found == null && queue.isNotEmpty()) {
            val (cost, cell) = queue.pop()
            if (cost > (best[cell] ?: Int.MAX_VALUE) || cost > MAX_USEFUL_COST) continue
            val row = cell / map.cols
            val col = cell % map.cols
            if (isSource(map, grid[row][col], side)) {
                found = cost
            } else {
                relax(map, enemies, row, col, cost, best, queue)
            }
        }
        return found
    }

    @Suppress("LongParameterList")
    private fun relax(
        map: GameMap,
        enemies: Set<Int>,
        row: Int,
        col: Int,
        cost: Int,
        best: HashMap<Int, Int>,
        queue: MinQueue,
    ) {
        val grid = map.map ?: return
        HexGeometry.getAdjacent(row, col).forEach { next ->
            val hex = grid.getOrNull(next.row)?.getOrNull(next.col)
            val step = hex?.let { stepCost(it, enemies, map, next.row, next.col) }
            val nextKey = key(map, next.row, next.col)
            if (step != null && nextKey !in enemies) {
                val total = cost + step
                if (total < (best[nextKey] ?: Int.MAX_VALUE)) {
                    best[nextKey] = total
                    queue.push(total, nextKey)
                }
            }
        }
    }

    /** What a supply column pays to enter [hex], or null when it cannot. */
    private fun stepCost(
        hex: Hex,
        enemies: Set<Int>,
        map: GameMap,
        row: Int,
        col: Int,
    ): Int? {
        val roadOrRail = hex.road != RoadType.NONE.value || hex.rail != RoadType.NONE.value
        val column = if (roadOrRail) ROAD_COLUMN else hex.terrain
        val raw = movTable.getOrNull(STEP_COST_ROW)?.getOrNull(column) ?: IMPASSABLE
        val terrainCost =
            when {
                raw >= IMPASSABLE -> return null
                raw >= STOP -> CRAWL_COST
                else -> raw
            }
        val pressure = HexGeometry.getAdjacent(row, col).count { key(map, it.row, it.col) in enemies }
        return terrainCost + pressure * ENEMY_AURA_COST
    }

    /** Whether [hex] feeds [side]'s supply: a working city or port it owns, or a station it owns
     *  or holds. */
    fun isSource(
        map: GameMap,
        hex: Hex,
        side: Int,
    ): Boolean {
        val owned = hex.owner != -1 && map.getPlayer(hex.owner).side == side
        val portsSupply = GameHolder.instance?.scenario?.portsNoSupply != true
        val held = hex.unit?.player?.side == side
        return when {
            hex.isWorking(TerrainType.CITY.value) -> owned
            hex.isWorking(TerrainType.PORT.value) -> owned && portsSupply
            hex.station -> owned || (hex.owner == -1 && held)
            else -> false
        }
    }

    /** Hexes held by enemy ground formations [side] can see and that exert a zone of control. */
    private fun knownEnemyCells(
        map: GameMap,
        side: Int,
    ): Set<Int> =
        map.units
            .asSequence()
            .filter { enemy ->
                !enemy.destroyed &&
                    enemy.player?.side != side &&
                    !UnitPredicates.isAir(enemy) &&
                    UnitCapabilities.projectsZoneOfControl(enemy) &&
                    UnitConcealment.isVisibleTo(enemy, side)
            }.mapNotNull { enemy -> enemy.getPos()?.let { key(map, it.row, it.col) } }
            .toSet()

    private fun key(
        map: GameMap,
        row: Int,
        col: Int,
    ): Int = row * map.cols + col

    /** A small binary min-heap of (cost, cell) pairs. */
    private class MinQueue {
        private val costs = ArrayList<Int>()
        private val cells = ArrayList<Int>()

        fun isNotEmpty(): Boolean = costs.isNotEmpty()

        fun push(
            cost: Int,
            cell: Int,
        ) {
            costs.add(cost)
            cells.add(cell)
            var i = costs.lastIndex
            while (i > 0) {
                val parent = (i - 1) / 2
                if (costs[parent] <= costs[i]) break
                swap(i, parent)
                i = parent
            }
        }

        fun pop(): Pair<Int, Int> {
            val top = costs[0] to cells[0]
            val lastCost = costs.removeAt(costs.lastIndex)
            val lastCell = cells.removeAt(cells.lastIndex)
            if (costs.isNotEmpty()) {
                costs[0] = lastCost
                cells[0] = lastCell
                siftDown()
            }
            return top
        }

        private fun siftDown() {
            var i = 0
            while (true) {
                val left = 2 * i + 1
                val right = left + 1
                var smallest = i
                if (left < costs.size && costs[left] < costs[smallest]) smallest = left
                if (right < costs.size && costs[right] < costs[smallest]) smallest = right
                if (smallest == i) return
                swap(i, smallest)
                i = smallest
            }
        }

        private fun swap(
            a: Int,
            b: Int,
        ) {
            val c = costs[a]
            costs[a] = costs[b]
            costs[b] = c
            val d = cells[a]
            cells[a] = cells[b]
            cells[b] = d
        }
    }
}
