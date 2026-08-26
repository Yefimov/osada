package org.osada.rules

import org.osada.TerrainType
import org.osada.model.EfileConfig
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
            // terrain a demolition charge removes. WHICH features is the efile's decision, not
            // ours -- see [razeableTerrain].
            RAZE -> hex.terrain in razeableTerrain()
        }
    }

    internal companion object {
        /**
         * What OG's own Blow applies to, quoted from the game's string template rather than
         * inferred: line 568 of `OPENTXT_SAMPLE/strings-en-template.txt` annotates the scenario's
         * "Allow to BLOW" switch with **"... Applies to bridges, ports, airfields && cities"**.
         * Bridges are [BLOW_BRIDGE]'s job, so this is the other three, minus the one OG does not
         * name.
         *
         * **Fortification is deliberately NOT here**, which looks odd beside "a sapper can build
         * one" and is what the quoted sentence says. It moves into reach only under
         * [EXTENDED_RAZEABLE_TERRAIN], the same place forest and bocage sit.
         */
        val RAZEABLE_TERRAIN =
            setOf(
                TerrainType.CITY.value,
                TerrainType.AIRFIELD.value,
                TerrainType.PORT.value,
            )

        /**
         * What `blow_any_terrain` adds — the efile key `eqp-atomic` and `eqp-basekorp` set and
         * `eqp-lxf` does not (`tools/og-import/out/efile-cfg/`).
         *
         * The key's own name is the whole of its documentation; the manual's §9.3.7 describes the
         * action and never says which terrain it reaches. So the split is an **`INFERENCE`**: what
         * OG's UI enumerates is always available, and a key called *blow ANY terrain* widens it to
         * the rest of the cover a charge could plausibly remove. Never water, never high ground —
         * a demolition does not remove a mountain, and no efile key implies it does.
         *
         * Until 2026-08-26 this set was unconditional and the key was unread, so every efile got
         * ATOMIC's rules. That was the `authored_options` gap in miniature: one set of engineering
         * rules applied to content that authored two.
         */
        val EXTENDED_RAZEABLE_TERRAIN =
            RAZEABLE_TERRAIN +
                setOf(
                    TerrainType.FOREST.value,
                    TerrainType.BOCAGE.value,
                    TerrainType.FORTIFICATION.value,
                )

        /**
         * [RAZEABLE_TERRAIN], widened by the active efile's own `blow_any_terrain`. An efile with
         * no `equip.cfg` at all — KAISER backs eight campaigns — says nothing, which reads as the
         * narrow set, per `docs/design/efile-config.md` §2 trap 4.
         *
         * **`Barrage` reads this too** (OG 9.2, 2026-08-26): shelling a hex destroys exactly what a
         * demolition charge could have destroyed on it, so the two can never disagree about whether
         * a wood or a snowfield may be turned into rubble. LXF sets neither `blow_any_terrain` nor
         * `blow_mask`, so in its four campaigns clear ground stays clear however hard it is shelled;
         * ATOMIC and BASEKORP set it and their guns can churn cover.
         */
        fun razeableTerrain(): Set<Int> =
            if (EfileConfig.flag("blow_any_terrain", false)) EXTENDED_RAZEABLE_TERRAIN else RAZEABLE_TERRAIN

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
