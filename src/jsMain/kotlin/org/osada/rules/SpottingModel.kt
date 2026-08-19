package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * The two Open General spotting rules OSADA's fog does not execute
 * (`docs/og-fidelity-plan.md` B.4 and B.5), behind `spotting_memory` and `installation_spotting`.
 *
 * ### Why these are two keys and two layers, not one
 *
 * `docs/design/ruleset-profiles.md` §2 already settled the shape for the four `weather_*` switches:
 * *"Four switches rather than one on purpose: they are four separate branches with four separate
 * call sites, and a player who turns one off should not silently lose the other three."* Turn-scoped
 * memory and installation vision are exactly that -- different sources, different lifetimes,
 * different call sites -- so they get a key and a bitmask each.
 *
 * ### Why neither touches the reference counts
 *
 * `Hex.setSpotted` keeps a per-side REFERENCE COUNT, and the fog is only ever correct while every
 * remove cancels an add made at the same range (`model/GameMapGrid.recomputeSpotting` documents what
 * happens when it does not: hexes permanently revealed that no toggle can put back). Both rules here
 * would break that pairing if written into the counter -- memory has no matching remove at all, and
 * an installation's vision has no unit to cancel it when the hex changes hands. So each is a
 * separate per-hex bitmask that [Hex.isSpotted] ORs in, and each is rebuilt wholesale rather than
 * incremented:
 *
 *  - [Hex.spotMemory] is SET as hexes are spotted and CLEARED for a side when that side's turn ends;
 *  - [Hex.installationSpotted] is recomputed for every side from scratch, so ownership changes,
 *    captured airfields and razed cities need no bookkeeping of their own.
 *
 * Both are 0 on every hex with the keys off, and every function here is a no-op then.
 */
internal object SpottingModel {
    /** Highest side index the bitmasks hold, matching [Minefields]' own limit: 31 would set the
     *  sign bit, and no scenario defines more than three players. */
    private const val MAX_SIDE_BIT = 30

    private fun sideBit(side: Int): Int = if (side in 0..MAX_SIDE_BIT) 1 shl side else 0

    fun memoryEnabled(): Boolean = ActiveRuleset.flag(RuleKey.SPOTTING_MEMORY, false)

    fun installationsEnabled(): Boolean = ActiveRuleset.flag(RuleKey.INSTALLATION_SPOTTING, false)

    /**
     * Records that [side] has seen [hex], for as long as its current turn lasts.
     *
     * Called from [Hex.setSpotted] itself rather than from `MovementRules.setSpotRange`, because
     * this is the one choke point every source of live vision passes through -- a unit arriving, a
     * unit's range changing, a full recompute. Missing one of them would give the player a fog that
     * remembers some hexes and not others, which is worse than not remembering any.
     */
    fun remember(
        hex: Hex,
        side: Int,
    ) {
        if (!memoryEnabled()) return
        hex.spotMemory = hex.spotMemory or sideBit(side)
    }

    /**
     * Drops [side]'s memory of the map, at the END of that side's turn.
     *
     * End rather than start, and the difference is not cosmetic: memory cleared at the start of a
     * side's own turn would survive the OPPONENT's turn, so enemy formations that moved into
     * remembered hexes would be visible for free at the moment the player takes over. Clearing on
     * the way out confines the rule to exactly what OG states -- the hex stays spotted for the
     * active turn -- and leaks nothing across the hand-over.
     */
    fun forgetTurnMemory(
        map: GameMap,
        side: Int,
    ) {
        val bit = sideBit(side)
        map.map?.forEach { row -> row.forEach { hex -> hex.spotMemory = hex.spotMemory and bit.inv() } }
    }

    /**
     * Rebuilds every side's installation vision from the map's own ownership.
     *
     * OG spots from owned cities, ports and airfields *and their adjacent hexes*, with no unit
     * present. Rebuilt wholesale on each call -- the loop is one pass over the grid and runs once
     * per turn, and a wholesale rebuild is what makes capture, loss and recapture correct with no
     * incremental bookkeeping at all.
     *
     * `flag` is the field the rest of the engine already treats as "whose installation this is"
     * (`MovementRules.hasAirfield` tests exactly this for airfields), so an installation the player
     * has not taken does not see for them.
     */
    fun recomputeInstallations(map: GameMap) {
        val grid = map.map ?: return
        grid.forEach { row -> row.forEach { it.installationSpotted = 0 } }
        if (!installationsEnabled()) return
        val sideOfCountry = map.players.associate { it.country to it.side }
        grid.forEach { row ->
            row.forEach { hex ->
                if (!isInstallation(hex.terrain)) return@forEach
                val side = sideOfCountry[hex.flag] ?: return@forEach
                val bit = sideBit(side)
                val pos = hex.getPos()
                hex.installationSpotted = hex.installationSpotted or bit
                HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
                    grid.getOrNull(cell.row)?.getOrNull(cell.col)?.let { neighbour ->
                        neighbour.installationSpotted = neighbour.installationSpotted or bit
                    }
                }
            }
        }
    }

    /** The three terrain types OG names. Not victory hexes and not supply hexes: an objective is a
     *  goal, and this rule is about installations that hold a garrison and a watch. */
    private fun isInstallation(terrain: Int): Boolean =
        terrain == TerrainType.CITY.value ||
            terrain == TerrainType.PORT.value ||
            terrain == TerrainType.AIRFIELD.value

    /** Whether either layer shows [hex] to [side]. Read by [Hex.isSpotted] after its own reference
     *  count, so with both keys off this is a single comparison against two zeroed fields. */
    fun revealedByLayers(
        hex: Hex,
        side: Int,
    ): Boolean {
        val bit = sideBit(side)
        return hex.spotMemory and bit != 0 || hex.installationSpotted and bit != 0
    }
}
