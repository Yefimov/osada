package org.osada.rules

import org.osada.TerrainType
import org.osada.model.Hex

/**
 * The seven engineering jobs OSADA builds out of Open General manual §9.3, in the order they are
 * offered on the unit strip.
 *
 * [cost] is quoted from the manual's own text, one figure per sub-section: a bridge is *"16 PP"*, a
 * fortification and a port *"12 PP"* each, an airfield *"20 PP"*. Demolition costs nothing there and
 * costs nothing here.
 *
 * [turns] is an **`INFERENCE`**, and the only number in this enum that is not quoted. OG says only
 * that *"some of these actions last several turns until finished"* and puts the real durations in
 * each efile's `build_turn` / `repair_turn` lists — which OSADA parses but cannot read, because the
 * column order is undecoded (`rules/Engineering`'s header has the evidence). The values here sit
 * inside the range LXF's own lists use (2–3 for building, 1–2 for repair), so a campaign that
 * enables the rule gets durations of the right size even though they are not that campaign's own.
 *
 * Repair is [turns] 2 rather than a per-facility figure for the same reason: `repair_turn` has six
 * columns and no labels.
 */
internal enum class EngineeringWork(
    val cost: Int,
    val turns: Int,
    val demolition: Boolean,
) {
    /** §9.3.2 — a road across a river or stream. */
    BRIDGE(cost = 16, turns = 2, demolition = false),

    /** §9.3.3 — a fortification the defender can hold. */
    FORTIFICATION(cost = 12, turns = 3, demolition = false),

    /** §9.3.4 — a dirt airfield. OG notes these *"can only supply one aircraft per turn"*; OSADA
     *  has no per-airfield capacity, so the built field behaves as any other. Recorded rather than
     *  approximated, and it is the same gap `No Dirt Airfields` (216 records) waits on. */
    AIRFIELD(cost = 20, turns = 3, demolition = false),

    /** §9.3.5 — a port on a coastal land hex. */
    PORT(cost = 12, turns = 3, demolition = false),

    /** §9.3.8 — puts back whichever of a razed terrain feature or a blown bridge is missing. */
    REPAIR(cost = 0, turns = 2, demolition = false),

    /** §9.3.1 — drops the bridge under the unit. Instant: OG's own wording is *"just press the 1 key
     *  to destroy the bridge"*, with no mention of turns. */
    BLOW_BRIDGE(cost = 0, turns = 0, demolition = true),

    /** §9.3.7 — razes the hex's terrain feature to clear ground. Instant, for the same reason. */
    RAZE(cost = 0, turns = 0, demolition = true),
    ;

    /**
     * Whether this job makes sense on [hex] at all — the terrain half of OG's own conditions, with
     * the "has taken no action" half left to `UnitActionAvailability` where every other action's
     * turn-state conditions live.
     *
     * [grid] is only consulted for [PORT], which is the one job whose condition is about the
     * NEIGHBOURS of the hex rather than the hex itself.
     */
    fun possibleOn(
        hex: Hex,
        grid: Array<Array<Hex>>?,
    ): Boolean {
        val bridgeableGap = Engineering.isWaterCrossing(hex) && hex.road <= 0
        return when (this) {
            // A bridge needs a crossing to span and no crossing already there.
            BRIDGE -> bridgeableGap
            // OG puts a fortification on "a hex"; refusing water and existing works is the
            // least that keeps the action honest, since neither could hold one.
            FORTIFICATION -> isBuildableGround(hex) && hex.terrain != TerrainType.FORTIFICATION.value
            // "must be in a clear hex" -- quoted, and the one condition OG states exactly.
            AIRFIELD -> hex.terrain == TerrainType.CLEAR.value
            PORT -> isPortSite(hex, grid)
            // Only what was actually DESTROYED can be repaired. Testing `road <= 0` on a water
            // hex (as this did until 2026-08-25) is also true of every river nobody ever bridged,
            // which made Repair a free Build Bridge -- see `Hex.blownRoad`. The other half of
            // keeping this test honest lives in `Engineering.complete`: CONSTRUCTION never writes
            // either field and clears whichever one it supersedes, or a newly finished airfield
            // would pass this test and offer to be demolished back into its own foundations
            // (found 2026-08-26, `Hex.razedTerrain`).
            REPAIR -> hex.razedTerrain >= 0 || hex.blownRoad != 0
            BLOW_BRIDGE -> Engineering.isWaterCrossing(hex) && hex.road > 0
            // Only a feature can be razed: clear ground is already clear, and water is not
            // terrain a demolition charge removes.
            RAZE -> hex.terrain in RAZEABLE_TERRAIN
        }
    }

    private companion object {
        /** Terrain a demolition can take back to clear ground: the built works, plus the two kinds
         *  of cover OG's own `blow_any_terrain` efiles use it on. Never water, never high ground —
         *  a charge does not remove a mountain. */
        val RAZEABLE_TERRAIN =
            setOf(
                TerrainType.CITY.value,
                TerrainType.FOREST.value,
                TerrainType.BOCAGE.value,
                TerrainType.FORTIFICATION.value,
                TerrainType.AIRFIELD.value,
                TerrainType.PORT.value,
            )

        /** Dry land beside open water, with no port on it yet. */
        fun isPortSite(
            hex: Hex,
            grid: Array<Array<Hex>>?,
        ): Boolean =
            isBuildableGround(hex) &&
                hex.terrain != TerrainType.PORT.value &&
                Engineering.isCoastal(grid, hex)

        /** Dry land that can carry a structure. */
        fun isBuildableGround(hex: Hex): Boolean =
            hex.terrain != TerrainType.OCEAN.value &&
                hex.terrain != TerrainType.RIVER.value &&
                hex.terrain != TerrainType.STREAM.value &&
                hex.terrain != TerrainType.IMPASSABLE_RIVER.value
    }
}
