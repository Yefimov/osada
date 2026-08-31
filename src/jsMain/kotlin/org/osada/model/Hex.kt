package org.osada.model

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.rules.GameRules
import org.osada.rules.SpottingModel
import org.osada.rules.isAir
import org.osada.uiSettings

/** OG's Typed-VH mask with every level set — an ordinary victory hex. */
const val ALL_VICTORY_TIERS: Int = 7

@JsExport
@JsName("Hex")
class Hex(
    private val rowVal: Int,
    private val colVal: Int,
) {
    var unit: GameUnit? = null
    var airunit: GameUnit? = null
    var terrain: Int = TerrainType.CLEAR.value
    var road: Int = RoadType.NONE.value

    // 8-direction mask like [road], but for rail (train movement only) -- see
    // MovementRules.getMoveRange's isTrain gate. Parsed from the scenario XML's own `rail`
    // attribute (tools/og-import/xscn.py already split it out of the OG binary's road/rail
    // uint16; scn_to_xml.py/add_rails.py now emit it). Absent (0) on scenarios never re-patched
    // with rail data -- MovementRules falls back to today's behaviour for those.
    var rail: Int = RoadType.NONE.value

    /**
     * Sides that have a LAND MINEFIELD laid on this hex, as a bitmask (`1 shl side`), and the sides
     * that have detected it (`docs/og-fidelity-plan.md` C.1, OG manual 9.9).
     *
     * A minefield in Open General is a **characteristic of the hex, not a unit** -- authored by the
     * scenario designer or laid during play -- which is why it lives here beside `road` and `rail`
     * rather than as an occupant. OG's own binary agrees: it is `byte6` of the hex record, bit 1 for
     * Axis and bit 2 for Allied (`tools/og-import/SCENARIO_FORMAT_NOTES.md`), so a hex really can
     * hold one field per side.
     *
     * [minesDetected] is the half that keeps the mechanic honest. `DEFERRED.md` §1.1 forbids
     * movement damage with no visible cause, so a field a side has detected is DRAWN and made to
     * cost the rest of the move without damage, and only an undetected one may ambush. A side always
     * counts as detecting its own field.
     *
     * Both are 0 on every hex unless the `minefields` ruleset key is on, and every rule that reads
     * them checks that key first ([org.osada.rules.Minefields]).
     */
    var mines: Int = 0

    var minesDetected: Int = 0

    /**
     * Engineering work in progress on this hex (OG manual 9.3, `rules/Engineering`).
     *
     * [construction] is an [org.osada.rules.EngineeringWork] ordinal, or `-1` for "nothing being
     * built"; [constructionTurns] counts down at the END of the BUILDER's turn and completes the
     * work at zero; [constructionPlayer] and [constructionCountry] are who is paying for it, and
     * [constructionSide] which side they are on.
     *
     * **The builder is stored per-PLAYER, not merely per-side, and that is what decides both the
     * countdown and the finished facility's flag.** Ticking on the side alone is wrong in the one
     * case it can be told apart: with two allied players on one side, every one of their turns
     * would advance every job the side is paying for — so a two-turn bridge would finish in one
     * round — and the facility would be flagged to whichever ally happened to end the turn it
     * completed on. Both are latent in shipped content (all 502 scenarios have two opposing
     * players) and both are real in hot-seat play. Found in review 2026-08-26.
     * [constructionSide] is still stored and still consulted, but only as the fallback for saves
     * written before the builder was recorded, where the side is all there is.
     *
     * [razedTerrain] is the terrain a `Can Blow` unit destroyed here, or `-1`, and [blownRoad] is
     * the road/bridge mask one took away, or `0`. They exist so Repair (9.3.8) has something
     * exact to restore rather than a guess: OG's own rule is to repair *"any destroyed facility"*,
     * which only means anything if the engine remembers what was destroyed.
     *
     * **Both are records of DESTRUCTION only, and construction never writes them.** Building an
     * airfield used to stash the terrain it covered in [razedTerrain], which made every freshly
     * completed airfield, port and fortification pass Repair's "was something destroyed here?"
     * test — so the reward for finishing one was a Repair chip that turned it back into the clear
     * or forest hex it had been built on. Construction instead CLEARS whichever record it
     * supersedes: raising a facility on razed ground spends [razedTerrain], and bridging a gap
     * spends [blownRoad], because after either there is nothing destroyed left to put back.
     * Found in review 2026-08-26.
     *
     * **[blownRoad] is what makes Repair a repair rather than a free bridge.** Found in review
     * 2026-08-25: Repair was offered on any river hex with no road, and completing it laid a full
     * bridge mask — so a sapper could build a crossing for nothing by pressing Repair instead of
     * the 16-prestige Build Bridge. `road == 0` cannot tell "blown" from "never bridged"; this
     * field can, and it restores the mask the hex actually had rather than an invented full one.
     *
     * All four are inert and serialize to nothing unless the `build_and_repair` ruleset key is on,
     * the same way the minefield fields above are inert without `minefields`.
     */
    var construction: Int = -1

    var constructionTurns: Int = 0

    var constructionSide: Int = -1

    var constructionPlayer: Int = -1

    var constructionCountry: Int = -1

    var razedTerrain: Int = -1

    var blownRoad: Int = 0

    /**
     * Whether the airfield on this hex was BUILT here rather than being part of the map
     * (OG manual §7.2, `Cannot use dirt airfields`: *"unit can't refuel nor deploy in airfields
     * defined as dirt or built by sappers during the scenario"*).
     *
     * It exists because that ability needs the airfield's ORIGIN and nothing else recorded it: the
     * construction fields above are cleared the moment the work finishes, and `terrain ==
     * AIRFIELD` cannot tell a sapper's strip from a permanent field. Written once by
     * `Engineering`, read by `MovementRules.hasAirfield`.
     *
     * **The "defined as dirt" half of OG's sentence is NOT this flag**, and is not imported: no
     * per-hex dirt marking has been located in the `.xscn`/`.map` binaries, so a map's own dirt
     * strips are indistinguishable from its permanent ones here. What is built is the half the
     * data supports; see `rules/AirfieldQuality`.
     *
     * Inert and serializes to nothing unless `build_and_repair` is on — nothing else can set it.
     */
    var sapperBuilt: Boolean = false

    /**
     * Whether this hex carries a **railroad station** (OG manual §9.3.6, and §6.12's rail
     * transport: *"the unit must be in a station hex"*).
     *
     * **Authored data, recovered 2026-08-27** — 915 stations across 143 of the 502 deployed
     * scenarios, imported by `tools/og-import/add_stations.py` from `.xscn` grid byte @13 bit 5.
     * That bit had never been located; OpenSuite's own map report counts stations as a per-hex
     * feature, which gave the correlation an oracle to check against. See `xscn.py` for the decode
     * and `docs/og-fidelity-plan.md` §U for the evidence.
     *
     * Unlike [sapperBuilt] this is NOT inert without a ruleset key: it is part of the map as the
     * author drew it, exactly as [rail] is, and it is loaded and saved whether or not any rule
     * reads it. `rules/EngineeringWork.STATION` is the rule that does — OG 9.3.6's construction,
     * which has to know which rail hexes already have one.
     */
    var station: Boolean = false

    /**
     * Whether this airfield (or port) is a **dirt** one — a scraped strip rather than a permanent
     * installation.
     *
     * > *"Dirt Airfield - deploy and supply Air Units"* — OG's own string,
     * > `OPENTXT_SAMPLE/strings-en-template.txt:850`
     *
     * **Authored data, recovered 2026-08-29** from `.xscn` grid byte `@19` bit 6, by the controlled
     * OpenSuite diff `docs/og-fidelity-plan.md` §Y.1 had been asking for since 2026-08-25. OG uses
     * **one flag for both airfield and port**, which was the open half of that question: clearing
     * it on two airfield hexes and setting it on a port hex moved the same bit each time.
     *
     * 29 hexes across 15 of the 502 deployed scenarios, imported by
     * `tools/og-import/add_dirt_airfields.py`. Corpus-wide the population is 193 hexes, **87% of
     * them Airfield terrain** — against the candidate ruled out in §X.4 (`@13` bit 0: 13,612 hexes,
     * 54.6% of them cities). The offset was proved to survive OG's 0027-2 → 0030-2 format change by
     * an oracle rather than by assumption: the shipped `bn6s09.xscn` carries it on exactly the two
     * hexes the editor showed as already Dirt.
     *
     * Authored map data like [station] and [rail], so it is read and saved unconditionally rather
     * than behind a ruleset key. [org.osada.rules.AirfieldQuality] is the rule that consumes it.
     *
     * **This is the authored twin of [sapperBuilt], not a replacement for it.** OG's
     * `Cannot use dirt airfields` names two kinds of unusable field — *"airfields defined as dirt
     * or built by sappers during the scenario"* — and until this flag was located only the second
     * existed here.
     */
    var dirt: Boolean = false

    /**
     * OG's **Escape Hexes** — the map side of manual §3.7.4's *"retreat N units"* victory
     * condition. A formation that ends its move here is withdrawn from the map and counted.
     *
     * **Two separate flags, because OG splits them**: `.xscn` grid `@13` bit 3 is the GROUND exit
     * and `@12` bit 4 the AIR one, and a hex may carry either or both. Recovered 2026-08-30 by a
     * controlled OpenSuite diff in which three hexes were marked Escape-Ground, Escape-Air and
     * Escape-Both, and produced exactly those bits.
     *
     * 419 corpus hexes are ground exits (Clear/Ocean/City/Forest — map-edge terrain) and 156 air
     * exits (87% Airfield or Ocean). `@13` bit 3 is also the bit `SCENARIO_FORMAT_NOTES.md` had
     * recorded as *"used by something else, on 419 hexes of which only 8.6% carry rail"*.
     *
     * Authored map data, so both are read and saved unconditionally;
     * [org.osada.rules.ExtendedVictory] decides what happens on one.
     */
    var escapeGround: Boolean = false

    var escapeAir: Boolean = false

    /**
     * OG's **trigger** on this hex — *"a hex where if a unit ends there its move, something
     * happens"* (`Manual_OSuite-Scenario.pdf` §3.4).
     *
     * [trigger] is the action code 1..9 and 0 means none; [triggerParam] is OG's 0–255 parameter,
     * whose meaning depends on the action; [triggerEquip] is the equipment id that actions 8 and 9
     * award; [triggerMessage] is the text OG keeps in a `.xtrig` sidecar and OSADA carries inline.
     *
     * **Recovered 2026-08-29** from `.xscn` grid bytes `@20`/`@21`/`@22`/`@26`, by the controlled
     * OpenSuite diff `docs/og-fidelity-plan.md` §Y.1 had been waiting on. 311 trigger hexes across
     * 86 of the 502 deployed scenarios; corpus-wide 858 across 401. `@20` takes no value outside
     * 0..9 anywhere in the corpus, which a mis-located byte could not manage.
     *
     * [org.osada.rules.TriggerHexes] is the rule, and it names which of the nine it executes.
     *
     * Authored map data, so all four are read and saved unconditionally; whether anything fires is
     * the rule's question.
     */
    var trigger: Int = 0

    var triggerParam: Int = 0

    var triggerEquip: Int = 0

    var triggerMessage: String = ""

    /**
     * Whether this hex's [trigger] has already fired.
     *
     * **A trigger is once-only, and that is an inference** — OG's manual says only what happens,
     * never how often. It is the reading that cannot overstate (`docs/og-sources.md`): a repeating
     * trigger is a prestige, experience and free-unit tap the player can farm by stepping off the
     * hex and back on, which would silently re-tune every one of the 86 scenarios that author one.
     * Once-only takes less from nobody and can only under-deliver.
     *
     * If OG turns out to repeat them, this is the field to remove and this is the sentence to come
     * back to. Serialized, because a reload must not re-arm a trigger the player already spent.
     */
    var triggerFired: Boolean = false

    /**
     * Whether what stood on this hex has been shelled into wreckage (OG 9.2, `rules/Barrage`).
     *
     * **A state of a DESTROYED facility or road, not a crater on open ground.** Open General School
     * theme 5 lists what barrage and demolition can destroy — airfield, bridge, city, fortification,
     * port and road — and says the wreck *"loses its usual functions and costs more to move
     * through"*. Clear ground, snow and sand are not in that list, and an efile only widens it by
     * setting `blow_any_terrain` (`EngineeringWork.razeableTerrain`, which barrage reads too). So
     * this is only ever set where something was actually wrecked.
     *
     * **A flag rather than OG's own rubble TERRAIN, and that is a finding.** OG carries rubble as
     * terrain index 17, but the index is an efile-authored slot: across the 35 `TerrainEx` tables in
     * the OG install, 25 efiles call it `custom`, 7 `snow` and only 3 `rubble` — LXF, which backs
     * four deployed campaigns, is a `custom` one. Claiming that index would misname terrain most
     * shipped content authored as something else.
     *
     * Read by `MoveRangeCalculation` as a movement surcharge and cleared by Repair, which is what
     * *"unusable until Repaired"* means. What it deliberately does NOT do is give cover: no source
     * grants a crater entrenchment, and the barrage that made it takes 2 entrenchment OFF the unit
     * standing there. Serialized only when set.
     */
    var rubble: Boolean = false

    /**
     * Whether shelling has cratered this open ground — OSADA's own rule, not Open General's
     * (`rules/Craters`, behind `RuleKey.CRATERS`).
     *
     * Separate from [rubble] because the two do opposite things to a defender: wreckage is a
     * destroyed facility and gives no cover, while a crater is a hole to lie in and sets a floor
     * under the occupant's entrenchment. They share only the movement surcharge.
     *
     * Only ever set on clear, snow or sand — ground with nothing on it to destroy. Serialized when
     * set, so a map nobody has shelled saves exactly as it did before the rule existed.
     */
    var crater: Boolean = false

    /**
     * The two Open General spotting layers that sit BESIDE the reference counts below, as per-side
     * bitmasks (`1 shl side`): what a side has seen so far this turn (`spotting_memory`, OG's
     * "a hex once spotted stays spotted for the active turn"), and what its own cities, ports and
     * airfields watch with no unit present (`installation_spotting`).
     *
     * They are separate fields rather than additions to [spotted] for one reason, and it is the
     * reason [clearSpotted] exists: [spotted] is a REFERENCE COUNT that is only ever correct while
     * each remove cancels an add of the same range. Memory has no matching remove, and an
     * installation has no unit to cancel it when the hex changes hands, so writing either into the
     * counter would strand it above zero and lift the fog permanently.
     *
     * [spotMemory] is cleared for a side when that side's turn ends; [installationSpotted] is
     * recomputed wholesale each turn. Both are 0 on every hex unless the matching ruleset key is on
     * (`org.osada.rules.SpottingModel`), which is what keeps [isSpotted] unchanged for everyone
     * else.
     */
    var spotMemory: Int = 0

    var installationSpotted: Int = 0

    var owner: Int = -1
    var flag: Int = -1
    var isDeployment: Int = -1
    var victorySide: Int = -1

    /**
     * Which VICTORY LEVELS this objective counts for — OG's *"Typed VH"*, manual §3.7.2.
     *
     * A 3-bit mask: `1` brilliant, `2` victory, `4` tactical. **7 means "every level"**, which is
     * what an ordinary victory hex is and what OSADA has always assumed, so that is the default and
     * a scenario without typed hexes behaves exactly as before.
     *
     * > *"Typed VH allow you to set some VH as needed for a level of victory."*
     *
     * **Decoded 2026-08-30** from `.xscn` grid `@10`, which turns out to be two nibbles — one per
     * primary player — each a tier mask rather than the plain flag this project read. Import maps
     * those players to OSADA sides; keeping both masks matters in scenarios where player 0 is on
     * side 1. The corpus values are
     * exactly the combinations that predicts (7, 1, 3, 6, 4) and they reproduce the manual's own
     * two worked examples; the enabling switch is `opt_specific_vh` at `@1010` bit 1.
     */
    var victoryTiersSide0: Int = ALL_VICTORY_TIERS
    var victoryTiersSide1: Int = ALL_VICTORY_TIERS
    var name: String = ""
    var isMoveSel: Boolean = false
    var isAttackSel: Boolean = false

    /** Set alongside [isMoveSel] when a SPOTTED enemy AA unit covers this hex and the currently
     *  selected unit is an aircraft (DEFERRED.md §1.1). Never derived from hidden AA -- see
     *  `AAInterception.visibleThreatHexes`. Cleared in `delMoveSel`. */
    var isAaThreat: Boolean = false

    /** Set alongside [isMoveSel] when the selected formation can reach this hex ONLY by climbing
     *  into its own organic transport first (`rules/AutoMount`). Drawn dashed and hovered with the
     *  truck cursor, so "my legs do not reach that far" is visible while the player is still
     *  planning. Cleared in `delMoveSel`. */
    var needsTransport: Boolean = false

    /** Set while the Barrage targeting mode is open and this hex is one the selected formation may
     *  shell (OG 9.2). Cleared with the rest of the selection overlay. */
    var isBarrageSel: Boolean = false

    /** A railway destination currently offered to the selected formation (`rules/RailTransport`).
     *  Painted and cleared exactly like [isBarrageSel] -- a transient selection overlay, never
     *  saved. */
    var isRailSel: Boolean = false

    private val zoc: IntArray = IntArray(2)
    private val spotted: IntArray = IntArray(2)

    fun getPos(): Cell = Cell(rowVal, colVal)

    fun isZOC(side: Int): Boolean = side < zoc.size && zoc[side] > 0

    fun isSpotted(side: Int): Boolean =
        when {
            uiSettings.noFOW -> true
            side < spotted.size && spotted[side] > 0 -> true
            // The two OG layers, in that order deliberately: live vision is the common case and the
            // cheapest test, and with both keys off the fields below are 0 on every hex.
            else -> SpottingModel.revealedByLayers(this, side)
        }

    fun setZOC(
        side: Int,
        add: Boolean,
    ) {
        if (side < zoc.size) {
            if (add) {
                zoc[side]++
            } else if (zoc[side] > 0) {
                zoc[side]--
            }
        }
    }

    fun setSpotted(
        side: Int,
        add: Boolean,
    ) {
        if (side < spotted.size) {
            if (add) {
                spotted[side]++
                // The one choke point every source of live vision passes through, which is why OG's
                // turn-scoped memory is recorded here rather than in `MovementRules.setSpotRange`.
                // A no-op unless `spotting_memory` is on.
                SpottingModel.remember(this, side)
            } else if (spotted[side] > 0) {
                spotted[side]--
            }
        }
    }

    /**
     * Zeroes both sides' spotting counters, for a full recompute (see
     * `GameMap.recomputeSpotting`).
     *
     * These are reference counts, added and removed one unit at a time, so they only stay correct
     * while every remove uses the same range its add did. Anything that changes a unit's spot range
     * out from under them — Stalin Regime being toggled, or a build that changes how the range is
     * derived — leaves counters that never fall back to zero, and the fog stays permanently lifted
     * over those hexes. Recomputing from the units is the only way back.
     */
    fun clearSpotted() = spotted.fill(0)

    fun copy(other: Hex) {
        terrain = other.terrain
        road = other.road
        rail = other.rail
        construction = other.construction
        constructionTurns = other.constructionTurns
        constructionSide = other.constructionSide
        constructionPlayer = other.constructionPlayer
        constructionCountry = other.constructionCountry
        razedTerrain = other.razedTerrain
        blownRoad = other.blownRoad
        mines = other.mines
        minesDetected = other.minesDetected
        spotMemory = other.spotMemory
        installationSpotted = other.installationSpotted
        owner = other.owner
        flag = other.flag
        isDeployment = other.isDeployment
        victorySide = other.victorySide
        victoryTiersSide0 = other.victoryTiersSide0
        victoryTiersSide1 = other.victoryTiersSide1
        name = other.name
        setUnit(other.unit)
        setUnit(other.airunit)
    }

    fun getUnit(airMode: Boolean = false): GameUnit? =
        if (unit != null && airunit != null) {
            if (airMode) airunit else unit
        } else {
            unit ?: airunit
        }

    fun setUnit(unit: GameUnit?) {
        if (unit != null) {
            unit.setHex(this)
            if (GameRules.isAir(unit)) {
                airunit = unit
            } else {
                this.unit = unit
            }
        }
    }

    fun delUnit(unit: GameUnit?) {
        if (unit == null) return
        unit.setHex(null)
        if (this.unit?.id == unit.id) this.unit = null
        if (this.airunit?.id == unit.id) this.airunit = null
    }

    fun cleanup() {
        unit?.cleanup()
        unit = null
        airunit?.cleanup()
        airunit = null
    }
}
