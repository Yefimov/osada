package org.osada.rules

import org.osada.TerrainType
import org.osada.model.EfileConfig
import org.osada.model.Hex
import org.osada.model.listKey

/**
 * The seven engineering jobs OSADA builds out of Open General manual §9.3, in the order they are
 * offered on the unit strip.
 *
 * [cost] and [turns] are the **DEFAULTS**, used only where the efile says nothing. The manual's own
 * text is where they come from: a bridge is *"16 PP"*, a fortification and a port *"12 PP"* each, an
 * airfield *"20 PP"*, a railroad station *"18 PP"*. Demolition costs nothing there and costs nothing
 * here.
 *
 * **The efile's own numbers are read since 2026-08-27** — see [costFor] and [turnsFor]. Until then
 * they were parsed and unread, because the column order was undecoded: LXF's `build_cost` of
 * `12,48,60,36,24` matches the manual's figures in no rotation, and guessing which number is a
 * bridge is exactly the invention `OG_ABILITY_AUDIT.md` §1 forbids.
 *
 * **`EFILE_NOKORP/equip.cfg` labels the columns itself**, and it is the same copy that documented
 * `supply_ex` for `DEFERRED.md` §2.10 — the one installed `equip.cfg` that ships its explanatory
 * comments:
 *
 * ```
 * build_cost = 20,10,40,15,15
 * * Can define specific cost to build: Bridge, Airport, Port, Fort, Station
 * build_turn = 2,1,3,1,2
 * * Can define the duration (turns) to build: Bridge, Airport, Port, Fort, Station
 * repair_turn = 1,1,1,1,1,1
 * * Can define the duration (turns) to repair: Bridge, Airport, Port, Fort, Station, rest
 * ```
 *
 * So the order is **Bridge, Airport, Port, Fort, Station**, and `repair_turn` carries a sixth column
 * for everything else. Read against LXF's `12,48,60,36,24` that is a 12-prestige bridge and a
 * **60-prestige port** — five times the manual's figure, on the efile behind four deployed
 * campaigns, which is how much this key was worth reading.
 *
 * [turns] for [REPAIR] is the sixth *rest* column's default; a repair of a named facility takes that
 * facility's own column.
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

    /**
     * §9.3.6 — a railroad station on a rail hex. **BUILT 2026-08-27, and this is the fifth
     * facility: OSADA built four until then** (`docs/og-fidelity-plan.md` §U).
     *
     * > *"To build a railroad station, a unit with the Sapper ability must be in a rail hex and
     * > hasn't done any action. If you point to the unit, you get a menu with the '4 Bld Station'
     * > option; just press the 4 key in the keyboard to start the construction. The construction of
     * > a railroad station costs 18 PP."*
     *
     * **The cost is quoted, not chosen** — 18 PP is the manual's own number, the only one of the
     * five it states outright. OG gives no build TIME for it, so it takes the three turns the other
     * three raised facilities take rather than a number invented for it alone.
     *
     * **What was blocking it was data, not design.** §M recorded stations as absent because they
     * *"would host an entrainment OSADA has no model for"*, and because no per-hex station marking
     * had ever been located in the `.xscn` — so a built station could not even be told from an
     * authored one. Both halves changed on 2026-08-27: the flag is `.xscn` @13 bit 5 and 915
     * authored stations across 143 deployed scenarios are now imported ([Hex.station]).
     *
     * **A station still hosts no entrainment, and that is deliberate.** OG's rail transport also
     * requires *"the player must have some rail transport available"* — a per-player pool that is
     * an unconfirmed candidate and is not read. So this action builds the thing OG's own §9.3.6
     * builds, on the hexes OG's own authors marked, and the transport that would use it stays on
     * the profile's gap list. Building the pool on an inference is the §Q.1 failure this project
     * has already made once.
     */
    STATION(cost = 18, turns = 3, demolition = false),

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
            // "must be in a rail hex" -- quoted, and the second condition OG states exactly. A
            // hex that already has one has nothing to build.
            STATION -> hex.rail != 0 && !hex.station
            // Only what was actually DESTROYED can be repaired. Testing `road <= 0` on a water
            // hex (as this did until 2026-08-25) is also true of every river nobody ever bridged,
            // which made Repair a free Build Bridge -- see `Hex.blownRoad`. The other half of
            // keeping this test honest lives in `Engineering.complete`: CONSTRUCTION never writes
            // either field and clears whichever one it supersedes, or a newly finished airfield
            // would pass this test and offer to be demolished back into its own foundations
            // (found 2026-08-26, `Hex.razedTerrain`).
            // Rubble counts: OG's blown CLEAR hex has no razed terrain to record -- it carries a
            // blown state instead -- and it is still something a sapper can put right.
            REPAIR -> repairableNow(hex)
            BLOW_BRIDGE -> Engineering.isWaterCrossing(hex) && hex.road > 0
            // Only a feature can be razed: clear ground is already clear, and water is not
            // terrain a demolition charge removes. WHICH features is the efile's decision, not
            // ours -- see [razeableTerrain].
            // Clear ground is razeable only under `blow_any_terrain`, where OG excludes just the
            // four water types -- and razing it leaves the hex BLOWN rather than re-terrained
            // (`Engineering.complete`). Without the key it stays refused: there is nothing to
            // remove, and OG's own three named facilities are the narrow set.
            RAZE -> razeableNow(hex)
        }
    }

    /**
     * This job's column in OG's `build_cost` / `build_turn` / `repair_turn` lists, or `null` for a
     * job those lists do not name.
     *
     * The order is OG's own, quoted in this file's header: **Bridge, Airport, Port, Fort, Station**.
     * The two demolitions have no column because OG charges nothing for them, and [REPAIR] has none
     * of its own because `repair_turn` indexes by the facility being repaired rather than by the
     * act — its sixth *rest* column is what [turns] already holds.
     */
    private val configColumn: Int?
        get() =
            when (this) {
                BRIDGE -> 0
                AIRFIELD -> 1
                PORT -> 2
                FORTIFICATION -> 3
                STATION -> 4
                REPAIR, BLOW_BRIDGE, RAZE -> null
            }

    /**
     * What this job costs in the ACTIVE efile: its own `build_cost` column, or [cost] where the
     * efile says nothing.
     *
     * A demolition is free in OG and stays free here — `build_cost` has no column for one, so
     * asking for a column it does not have must never fall through to a construction price.
     */
    fun costFor(): Int = column("build_cost") ?: cost

    /**
     * How many turns this job takes in the ACTIVE efile: its own `build_turn` column, or [turns]
     * where the efile says nothing.
     *
     * **[REPAIR] reads `repair_turn`'s sixth column**, the one OG labels *rest*. A repair of a
     * NAMED facility would take that facility's own column, which this cannot know: the hex records
     * what was destroyed ([Hex.razedTerrain] / [Hex.blownRoad]) but the job is one enum value, so
     * mapping a razed port back onto column 2 would need `possibleOn`'s knowledge at the moment the
     * duration is asked. Recorded rather than approximated; the *rest* column is the honest reading
     * of "a repair, unspecified".
     */
    fun turnsFor(hex: Hex? = null): Int =
        if (this == REPAIR) {
            EfileConfig.listKey("repair_turn").getOrNull(repairColumn(hex)) ?: turns
        } else {
            column("build_turn") ?: turns
        }

    /**
     * Which `repair_turn` column a repair of [hex] takes.
     *
     * OG labels the six **Bridge, Airport, Port, Fort, Station, rest**, and a repair is indexed by
     * WHAT IS BEING PUT BACK rather than by the act — so a destroyed airfield takes the Airport
     * column, not *rest*. `rest` covers everything that is not one of the five named structures:
     * roads, rails, cities and any other blown terrain.
     *
     * **This was `rest` for everything until 2026-08-27**, on the reasoning that the enum is one
     * value and cannot know what it is repairing. It can: the hex records it, in [Hex.razedTerrain]
     * for a facility and [Hex.blownRoad] for a crossing, which is the whole reason those fields
     * exist. A null hex — the action strip asking in the abstract — still gets *rest*.
     */
    private fun repairColumn(hex: Hex?): Int =
        when {
            hex == null -> REPAIR_REST_COLUMN
            hex.blownRoad != 0 -> REPAIR_BRIDGE_COLUMN
            hex.razedTerrain == TerrainType.AIRFIELD.value -> REPAIR_AIRPORT_COLUMN
            hex.razedTerrain == TerrainType.PORT.value -> REPAIR_PORT_COLUMN
            hex.razedTerrain == TerrainType.FORTIFICATION.value -> REPAIR_FORT_COLUMN
            else -> REPAIR_REST_COLUMN
        }

    private fun column(key: String): Int? = configColumn?.let { EfileConfig.listKey(key).getOrNull(it) }

    /**
     * This job's bit in OG's `build_mask` / `blow_mask`, or `null` for one neither names.
     *
     * `EFILE_NOKORP/equip.cfg` gives the codes: **Bridge=1, Airport=2, Port=4, Fort=8,
     * Station=16**, and `blow_mask` adds **City=32**. `0` means "allow any", which is what every
     * shipped efile carries.
     *
     * [RAZE] is the City bit rather than a construction bit, because razing is what OG's blow list
     * calls destroying a city; [BLOW_BRIDGE] is the Bridge bit on the blow side. [REPAIR] is in
     * neither mask — OG masks what may be BUILT and what may be BLOWN, and repairing is neither.
     */
    private val maskBit: Int?
        get() =
            when (this) {
                BRIDGE, BLOW_BRIDGE -> 1
                AIRFIELD -> 2
                PORT -> 4
                FORTIFICATION -> 8
                STATION -> 16
                RAZE -> 32
                REPAIR -> null
            }

    /**
     * Whether the active efile's `build_mask` / `blow_mask` permits this job at all.
     *
     * **No shipped efile sets either key** — both are 0 everywhere, which OG documents as "allow
     * any" — so this is a correctness read rather than a behaviour change, and it exists so that
     * content which does author them is honoured instead of silently overruled. That is the same
     * standard `blow_any_terrain` is held to since §N.4.
     */
    fun permittedByEfileMask(): Boolean {
        val bit = maskBit ?: return true
        val mask = EfileConfig.intKey(if (demolition) "blow_mask" else "build_mask", 0)
        return mask == 0 || mask and bit != 0
    }

    internal companion object {
        /** `repair_turn`'s sixth column, which OG labels *rest* — see [turnsFor]. */
        private const val REPAIR_REST_COLUMN = 5

        /** `repair_turn`'s named columns, in OG's order. */
        private const val REPAIR_AIRPORT_COLUMN = 1
        private const val REPAIR_PORT_COLUMN = 2
        private const val REPAIR_FORT_COLUMN = 3
        private const val REPAIR_BRIDGE_COLUMN = 0

        /** Whether Repair has anything to put back: a razed feature, a blown crossing, or ground
         *  a demolition left blown. */
        fun repairableNow(hex: Hex): Boolean = hex.razedTerrain >= 0 || hex.blownRoad != 0 || hex.rubble

        /** Whether a demolition has anything to do here: a feature to remove, or open ground that
         *  is not already blown ([Engineering.razeFeature]). */
        fun razeableNow(hex: Hex): Boolean =
            hex.terrain in razeableTerrain() &&
                !(hex.terrain == TerrainType.CLEAR.value && hex.rubble)

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
         * **This was an INFERENCE until 2026-08-27, and the inference was wrong in one direction.**
         * It read: *"Never water, never high ground — a demolition does not remove a mountain, and
         * no efile key implies it does."* The first half is right and the second is not.
         * `EFILE_NOKORP/equip.cfg`, the one installed copy that ships its explanatory comments,
         * documents the key itself:
         *
         * ```
         * blow_any_terrain=1
         * * Set to 1 to be able to blow any terrain except Ocean, Impas.River, River and Shallow Sea
         * ```
         *
         * So the key is stated as an EXCLUSION LIST, and it names only water. Hill, mountain, sand,
         * swamp, stream, escarpment and rough ground are all in reach of a charge under it; OG's
         * *"Shallow Sea"* is its terrain 18, which this project's importer already folds into
         * [TerrainType.IMPASSABLE_RIVER] (`xscn.py`'s `TERRAIN_REMAP`). A stream is not on OG's
         * list and is therefore blowable, which reads oddly beside an unblowable river until you
         * notice that is the difference between a culvert and a crossing.
         *
         * **Clear ground is included, and this got it wrong once.** §V.2 read the exclusion list
         * correctly and then refused clear ground anyway, reasoning that razing it would only
         * produce clear ground. OG does not re-terrain a blown hex — it records a BLOWN STATE of
         * its own. OSADA has that state already as [Hex.rubble], so razing clear ground now leaves
         * rubble: costly to cross, no cover, and repairable. Corrected 2026-08-27 against the
         * author's own documentation.
         *
         * Until 2026-08-26 this set was unconditional and the key was unread, so every efile got
         * ATOMIC's rules. That was the `authored_options` gap in miniature: one set of engineering
         * rules applied to content that authored two.
         */
        val UNBLOWABLE_TERRAIN =
            setOf(
                TerrainType.OCEAN.value,
                TerrainType.RIVER.value,
                // OG's own "Impas.River" and "Shallow Sea" both land here -- see the note above.
                TerrainType.IMPASSABLE_RIVER.value,
            )

        val EXTENDED_RAZEABLE_TERRAIN =
            TerrainType.entries
                .map { it.value }
                .filterNot { it in UNBLOWABLE_TERRAIN }
                .toSet()

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

        /**
         * Dry land that can carry a structure.
         *
         * **`build_terr_ex` widens it** — *"Set to 1 to allow building also in terrain: Mountain
         * and Forest"* (`EFILE_NOKORP/equip.cfg`), set by `eqp-atomic` and `eqp-basekorp` and not
         * by `eqp-lxf`. Without the key those two terrains are refused: OG names them as what the
         * key ADDS, so an efile that is silent does not have them, and reading the key's absence as
         * permission would give every efile ATOMIC's engineering exactly as an unread
         * `blow_any_terrain` once did.
         *
         * The four water types are refused either way — the key does not name them, and nothing in
         * OG puts a fortification in a river.
         */
        fun isBuildableGround(hex: Hex): Boolean {
            val water =
                hex.terrain == TerrainType.OCEAN.value ||
                    hex.terrain == TerrainType.RIVER.value ||
                    hex.terrain == TerrainType.STREAM.value ||
                    hex.terrain == TerrainType.IMPASSABLE_RIVER.value
            if (water) return false
            val roughGoing =
                hex.terrain == TerrainType.MOUNTAIN.value || hex.terrain == TerrainType.FOREST.value
            return !roughGoing || EfileConfig.flag("build_terr_ex", false)
        }
    }
}
