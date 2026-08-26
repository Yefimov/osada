package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.ATTR2_MASK_ALLOW_LOF
import org.osada.model.ATTR2_MASK_CUT_LOS
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Extended LOS** optional rule (manual §9.5), plus the two equipment attributes
 * that only mean anything while it is on.
 *
 * `docs/og-fidelity-plan.md` never tracked this as work. §B.5 mentions it exactly once, in a
 * parenthetical predicting that *"Extended LOS, if ever built, is a third key and not a value of
 * either"* — of `spotting_memory` or `installation_spotting`. That prediction is honoured here:
 * [RuleKey.EXTENDED_LOS] is a third key beside those two, and it is OFF by default, so nothing in
 * the 502 shipped scenarios changes until a profile asks for it.
 *
 * ### What OG's four bullets are, and which three are here
 *
 * | # | OG §9.5 | Here |
 * |---|---|---|
 * | 1 | *"Mountains, hills forest and city hexes cut LOS to the hexes opposite them"*
 *     | [hasLineOfSight], applied in `MovementRules.setSpotRange` |
 * | 2 | *"Units in forest hexes aren't spotted by other ground units, unless adjacent"* | [isConcealedByTerrain] |
 * | 3 | *"Units in forest or city hexes aren't spotted by air units more than 2 hexes away"* | [isConcealedByTerrain] |
 * | 4 | *"Units that cannot move and are spotted, remain located, but can only be
 *     inspected if within spotting range"* | **not built** — see below |
 *
 * **Bullet 4 is deliberately not built, and this is the place that says so.** It is an INSPECTION
 * rule, not a visibility rule: the unit stays on the map and only the detail panel is withheld.
 * OSADA has no "located but not inspectable" state, and inventing one would mean a new per-unit
 * flag, its serialization, and an answer for what the enemy card shows instead — for a rule whose
 * whole effect is to hide statistics the player has already seen once. Recorded rather than
 * approximated.
 *
 * ### Why bullet 1 is safe to write into the spotting counters and the abilities are not
 *
 * `Hex.setSpotted` is a per-side REFERENCE COUNT, and the fog is only correct while every remove
 * cancels an add made over the same set of cells (`model/GameMapGrid.recomputeSpotting`). Bullet 1
 * blocks on TERRAIN, which is fixed for the whole scenario — except where `rules/Engineering` builds
 * or razes something, and that path calls `recomputeSpotting()` for exactly this reason. So the add
 * and the remove always see the same map.
 *
 * A unit carrying `Cut LOS` does NOT satisfy that: it walks around, so a spotting range computed
 * while it stood there cannot be cancelled after it leaves, and the counters would strand above
 * zero — permanently revealed hexes no toggle can put back. So the two abilities are read on the
 * LINE OF FIRE instead ([hasLineOfFire]), which is recomputed from scratch at every attack and has
 * no symmetry to break. That is the natural home for `Allow LOF` in any case, and for `Cut LOS` it
 * is a narrowing recorded here rather than a silent one: the ability blocks shots through the hex,
 * not sight of it.
 *
 * ### Line of fire is behind this key even though OG §6.18 is not an optional rule
 *
 * OG states terrain-blocked ranged fire in section 6 (*"hills, mountains, cities, forest and bocage
 * cut the line of fire of these units, making an attack impossible"*), i.e. as a CORE rule, and
 * OSADA has never had it — `AttackEligibility.isInAttackRange` is pure distance. It is gated here
 * anyway, because switching it on universally would make some authored attacks impossible in every
 * one of the 502 shipped scenarios at once. That is the §5.10 hazard the whole fidelity plan is
 * written around; a rule that re-tunes shipped content belongs behind a key the player chooses.
 */
internal object ExtendedLos {
    /** OG's own reading of "more than 2 hexes away" for an air observer (§9.5 bullet 3). */
    private const val AIR_SPOT_LIMIT_IN_COVER = 2

    /** Whether the rule is in force. Off in every profile except Open General Fidelity. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.EXTENDED_LOS, false)

    /**
     * The terrain OG names as sight-blocking in §9.5 — mountain, hill, forest and city — plus
     * BOCAGE, which §6.18 adds for line of fire and which is the same kind of feature.
     *
     * PORT and AIRFIELD are NOT included even though `SpottingModel` groups them with CITY for
     * installation vision: those two are flat by definition, and OG's sentence names city alone.
     */
    private fun blocksSight(terrain: Int): Boolean =
        terrain == TerrainType.MOUNTAIN.value ||
            terrain == TerrainType.HILL.value ||
            terrain == TerrainType.FOREST.value ||
            terrain == TerrainType.CITY.value ||
            terrain == TerrainType.BOCAGE.value

    /**
     * Whether nothing on the map blocks sight from (`fromRow`,`fromCol`) to (`toRow`,`toCol`).
     *
     * Endpoints never block: standing in a forest does not stop you seeing out of it, and the
     * target's own cover is bullets 2 and 3's business, not this one's. Always true with the key
     * off, and always true at distance 1.
     */
    fun hasLineOfSight(
        grid: Array<Array<Hex>>?,
        fromRow: Int,
        fromCol: Int,
        toRow: Int,
        toCol: Int,
    ): Boolean {
        if (!enabled() || grid == null) return true
        return HexGeometry.lineBetween(fromRow, fromCol, toRow, toCol).none { cell ->
            blocksSight(grid.getOrNull(cell.row)?.getOrNull(cell.col)?.terrain ?: TerrainType.CLEAR.value)
        }
    }

    /**
     * Whether [attacker] has an unobstructed line of fire to [defender]: the same terrain test as
     * [hasLineOfSight], plus any unit standing in between that carries OG's `Cut LOS` attribute,
     * minus any that carries `Allow LOF`.
     *
     * `Allow LOF` beats `Cut LOS` where a record somehow carries both — the ability that says fire
     * passes through is the more specific statement, and it is the one whose whole purpose is to
     * stop a unit obstructing its own side's shots.
     *
     * Adjacent attacks are never blocked (there is nothing in between), which keeps every close
     * combat in the shipped scenarios untouched even with the key on.
     */
    @Suppress("ReturnCount") // the rule gate, the unresolvable case and the answer
    fun hasLineOfFire(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val from = attacker.getPos()
        val to = defender.getPos()
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        // Anything unknown means "not blocked": refusing an attack because the geometry could
        // not be resolved would take a legal order away with no explanation.
        if (!enabled()) return true
        if (from == null || to == null || grid == null) return true
        return HexGeometry.lineBetween(from.row, from.col, to.row, to.col).none { cell ->
            val hex = grid.getOrNull(cell.row)?.getOrNull(cell.col)
            hex != null && (blocksSight(hex.terrain) || cutsLineOfSight(hex.unit))
        }
    }

    /** Whether a unit standing in an intervening hex blocks fire through it. */
    private fun cutsLineOfSight(unit: GameUnit?): Boolean {
        val data = unit?.unitData(true) ?: return false
        val allows = data.attr2 and ATTR2_MASK_ALLOW_LOF != 0
        return !allows && data.attr2 and ATTR2_MASK_CUT_LOS != 0
    }

    /**
     * Bullets 2 and 3: whether cover hides [unit] from [observerSide] even though its hex is
     * spotted.
     *
     * Asked as *"does any enemy observer legitimately see this formation"* rather than as a
     * property of one observer, because OSADA's fog is a per-hex counter that has already merged
     * every observer by the time anything asks. A unit in cover is concealed exactly when no enemy
     * unit qualifies:
     *
     *  - a GROUND observer sees into a forest only from an adjacent hex, and into a city normally;
     *  - an AIR observer sees into either only within [AIR_SPOT_LIMIT_IN_COVER] hexes;
     *  - either way the observer must have the formation inside its own spotting range and an
     *    unobstructed [hasLineOfSight] to it.
     *
     * **Cost is paid only where the rule applies.** With the key off this returns on its first
     * comparison; with it on, only a unit actually standing in forest or city scans the unit list,
     * and `tempSpotted` (a unit that revealed itself by firing) short-circuits ahead of the scan.
     */
    fun isConcealedByTerrain(
        unit: GameUnit,
        observerSide: Int,
    ): Boolean {
        val hex = unit.getHex()
        val visibleAnyway = !enabled() || unit.tempSpotted || UnitPredicates.isAir(unit) || hex == null
        if (visibleAnyway || hex == null) return false
        val inForest = hex.terrain == TerrainType.FOREST.value
        val inCover = inForest || hex.terrain == TerrainType.CITY.value
        return inCover && !hasQualifiedObserver(unit, observerSide, inForest)
    }

    private fun hasQualifiedObserver(
        unit: GameUnit,
        observerSide: Int,
        inForest: Boolean,
    ): Boolean {
        val target = unit.getPos()
        val map = GameHolder.instance?.scenario?.map
        // No map or no position means nothing can be judged; erring towards VISIBLE is the
        // safe direction, because a wrongly hidden unit is a bug the player cannot see past.
        if (target == null || map == null) return true
        return map.units.any { observer ->
            observer.player?.side == observerSide &&
                !observer.destroyed &&
                qualifies(observer, target, inForest)
        }
    }

    private fun qualifies(
        observer: GameUnit,
        target: Cell,
        inForest: Boolean,
    ): Boolean {
        val pos = observer.getPos() ?: return false
        val distance = HexGeometry.distance(pos.row, pos.col, target.row, target.col)
        val closeEnough =
            if (UnitPredicates.isAir(observer)) {
                distance <= AIR_SPOT_LIMIT_IN_COVER
            } else {
                !inForest || distance <= 1
            }
        val inSpotRange = distance <= MovementRules.getUnitSpotRange(observer)
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        return inSpotRange &&
            closeEnough &&
            hasLineOfSight(grid, pos.row, pos.col, target.row, target.col)
    }
}
