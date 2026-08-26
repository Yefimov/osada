package org.osada.rules

import org.osada.GameHolder
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.ATTR2_MASK_BUILD_REPAIR
import org.osada.model.ATTR_MASK_CAN_BLOW
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's **Build and Repair** optional rule (manual §9.3) — sappers who build bridges,
 * fortifications, airfields and ports, and demolition units who take them away again.
 *
 * ### Why this exists at all, given it was on nobody's list
 *
 * `docs/og-fidelity-plan.md` §C lists the Open General systems OSADA lacks. It names rail, air
 * missions, carrier hangars, minefields and extended naval rules. **It does not name this one**, and
 * neither does the profile's own player-facing gap list, so a whole optional rule with eight
 * sub-sections was invisible to both. Its two abilities appeared in the equipment card as muted
 * "decoded, not executed" badges — `Build/Repair` on 1,298 shipped records and `Can Blow` on 5,047 —
 * and nothing anywhere explained that they were an unbuilt subsystem rather than two loose bits.
 *
 * It also has the strongest content argument of anything left, the same one §C.1 used to promote
 * minefields ahead of the rest of §C: **this is configured in the shipped efiles.** `eqp-lxf`'s own
 * `equip.cfg` carries `build_cost=12,48,60,36,24`, `build_turn=2,3,3,3,2` and
 * `repair_turn=1,2,2,2,1,1`, and LXF backs four deployed campaigns; `eqp-atomic` and `eqp-basekorp`
 * add `blow_any_terrain`, `build_start_ex` and `build_terr_ex`.
 *
 * ### What is built, and the two things that are not
 *
 * | OG | Here |
 * |---|---|
 * | 9.3.1 Bridge destruction | [EngineeringWork.BLOW_BRIDGE] — clears the road mask off a river/stream hex |
 * | 9.3.2 Bridge construction | [EngineeringWork.BRIDGE] |
 * | 9.3.3 Fortification construction | [EngineeringWork.FORTIFICATION] |
 * | 9.3.4 Airfield construction | [EngineeringWork.AIRFIELD] |
 * | 9.3.5 Port construction | [EngineeringWork.PORT] |
 * | 9.3.6 Railroad station construction | **not built** — see below |
 * | 9.3.7 Terrain destruction | [EngineeringWork.RAZE] |
 * | 9.3.8 Repair | [EngineeringWork.REPAIR] |
 *
 * **Railroad stations are deliberately absent, and this is the place that records why.** OSADA has
 * no station concept for one to be built into: rail is an equipment MOVEMENT METHOD here, not OG's
 * non-organic transport mode, so there is no embarkation for a station to host and OG's own
 * `NoNeedStation` ability (11,003 records, the most common unwired attribute in the shipped data)
 * has nothing to be an exception to. Building one would put a button on the strip that spends
 * prestige and changes nothing. It becomes possible when rail transport does, and not before.
 *
 * **`build_cost` / `build_turn` / `repair_turn` are parsed but NOT read**, and the numbers below are
 * the manual's own instead. `equip_cfg_to_json.py` keeps those three as raw strings because they are
 * comma lists rather than plain ints, and **the column order is undecoded** — LXF's `12,48,60,36,24`
 * does not match the manual's 16/12/20/12/18 in any rotation, so mapping one to the other would be a
 * guess about which number is a bridge. `OG_ABILITY_AUDIT.md` §1's standing rule against inventing a
 * mechanic from a name applies just as well to inventing one from an unlabelled column. The manual's
 * costs are quoted; the durations are an INFERENCE, marked as one on [EngineeringWork.turns].
 */
@Suppress("TooManyFunctions")
internal object Engineering {
    /**
     * The road mask a newly built bridge carries: every direction at once.
     *
     * [org.osada.RoadType] is a per-direction BITMASK, not an on/off flag, and every reader that
     * matters -- `MoveRangeCalculation`'s road cost, `AttackCalculation`'s two road tests,
     * `TerrainEx`'s supply factor -- asks only whether it is non-zero. A built bridge is a crossing
     * in whatever direction the traffic needs, so it is drawn as all six/eight rather than guessed
     * from the neighbouring hexes' own masks.
     */
    private const val ALL_DIRECTIONS_ROAD = 255

    /** Whether the rule is in force. Off in every profile except Open General Fidelity. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.BUILD_AND_REPAIR, false)

    /** Whether [unit] carries OG's `Build/Repair` (Sapper) ability. */
    fun isSapper(unit: GameUnit): Boolean = unit.unitData(true).attr2 and ATTR2_MASK_BUILD_REPAIR != 0

    /** Whether [unit] carries OG's `Can Blow` (demolition) ability. */
    fun canDemolish(unit: GameUnit): Boolean = unit.unitData(true).attr and ATTR_MASK_CAN_BLOW != 0

    /** Whether anything is being built on [hex] right now. */
    fun underConstruction(hex: Hex): Boolean = hex.construction >= 0 && hex.constructionTurns > 0

    /** The stable serialized name of the job on [hex], or null when there is none. Paired with
     *  [workOrdinal]; see `GameStateSerializer` for why saves carry the name and not the ordinal. */
    fun workName(hex: Hex): String? = EngineeringWork.entries.getOrNull(hex.construction)?.name

    /** The ordinal a saved job [name] maps to, or `-1` for absent or unrecognised. */
    fun workOrdinal(name: String?): Int = EngineeringWork.entries.firstOrNull { it.name == name }?.ordinal ?: -1

    /** The work in progress on [hex], or null. */
    fun workInProgress(hex: Hex): EngineeringWork? =
        if (underConstruction(hex)) EngineeringWork.entries.getOrNull(hex.construction) else null

    /**
     * Every job [unit] could start on the hex it is standing on, in menu order.
     *
     * Empty when the rule is off, when the unit carries neither ability, or when the hex already has
     * work in progress — OG's own UI offers one job at a time, and two overlapping jobs would need a
     * queue nobody asked for.
     */
    fun availableWork(unit: GameUnit): List<EngineeringWork> {
        val hex = unit.getHex()
        val sapper = isSapper(unit)
        val demolisher = canDemolish(unit)
        val couldWork = enabled() && hex != null && !underConstruction(hex) && (sapper || demolisher)
        if (!couldWork || hex == null) return emptyList()
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map
        return EngineeringWork.entries.filter { work ->
            val hasAbility = if (work.demolition) demolisher else sapper
            hasAbility && work.possibleOn(hex, grid)
        }
    }

    /**
     * Starts [work] on [unit]'s hex: instant for a demolition, otherwise a countdown.
     *
     * The caller is responsible for the prestige and for ending the unit's turn — this owns the HEX
     * half only, the same division [Minefields] uses.
     */
    fun begin(
        hex: Hex,
        side: Int,
        work: EngineeringWork,
        owner: FacilityOwner,
    ) {
        if (work.turns == 0) {
            complete(hex, work, owner)
            return
        }
        hex.construction = work.ordinal
        hex.constructionTurns = work.turns
        hex.constructionSide = side
        // The BUILDER, not just their side: it decides whose turn end advances this job and whose
        // flag the finished facility flies. `Hex.constructionPlayer` records what storing only the
        // side cost -- an ally's turn advanced it, and an ally's turn end could claim it.
        hex.constructionPlayer = owner.playerId
        hex.constructionCountry = owner.country
    }

    /**
     * Advances the jobs [turnOwner] is paying for by one turn and completes the ones that finish.
     *
     * [side] is the fallback for a job with no recorded builder — a save written before the builder
     * was stored — and nothing else; a job that knows who began it is advanced by that player's
     * turn end alone, so two allied players cannot build one bridge in half the turns between them.
     *
     * Returns the hexes whose TERRAIN changed, so the caller can rebuild anything derived from it.
     * That return value is not a convenience: `MovementRules.setSpotRange` blocks line of sight on
     * terrain under `extended_los`, and its add/remove pairing is only safe while terrain holds
     * still — so a completed airfield must be followed by a wholesale spotting rebuild, which is
     * what `GameMap.endTurn` does with this list.
     */
    fun advanceTurn(
        grid: Array<Array<Hex>>?,
        side: Int,
        turnOwner: FacilityOwner = FacilityOwner.NONE,
    ): List<Hex> {
        if (!enabled() || grid == null) return emptyList()
        val finished = mutableListOf<Hex>()
        grid.forEach { row ->
            row.forEach { hex ->
                if (underConstruction(hex) && advancesNow(hex, side, turnOwner)) {
                    tick(hex, finished, builderOf(hex, turnOwner))
                }
            }
        }
        return finished
    }

    /** Whether the turn ending now is the one this job counts down on: its own builder's, or —
     *  for a job restored from a save that predates the builder field — any turn of the paying
     *  side's. */
    private fun advancesNow(
        hex: Hex,
        side: Int,
        turnOwner: FacilityOwner,
    ): Boolean =
        if (hex.constructionPlayer >= 0) {
            hex.constructionPlayer == turnOwner.playerId
        } else {
            hex.constructionSide == side
        }

    /** Who a job's finished facility belongs to: whoever began it, falling back to the player whose
     *  turn is ending for a builderless job from an older save. Without the fallback such a job
     *  would complete unflagged, and an unflagged airfield is scenery ([claim]). */
    private fun builderOf(
        hex: Hex,
        turnOwner: FacilityOwner,
    ): FacilityOwner =
        if (hex.constructionPlayer >= 0) {
            FacilityOwner(hex.constructionPlayer, hex.constructionCountry)
        } else {
            turnOwner
        }

    /** One turn off one job, completing it and recording the hex when it reaches zero. */
    private fun tick(
        hex: Hex,
        finished: MutableList<Hex>,
        owner: FacilityOwner,
    ) {
        hex.constructionTurns -= 1
        if (hex.constructionTurns > 0) return
        val work = EngineeringWork.entries.getOrNull(hex.construction)
        clearWork(hex)
        if (work != null) {
            complete(hex, work, owner)
            finished.add(hex)
        }
    }

    private fun clearWork(hex: Hex) {
        hex.construction = -1
        hex.constructionTurns = 0
        hex.constructionSide = -1
        hex.constructionPlayer = -1
        hex.constructionCountry = -1
    }

    /** Applies [work]'s finished effect to [hex], on behalf of [owner]. */
    private fun complete(
        hex: Hex,
        work: EngineeringWork,
        owner: FacilityOwner,
    ) {
        when (work) {
            EngineeringWork.BRIDGE -> {
                hex.road = ALL_DIRECTIONS_ROAD
                // A crossing built where one was blown SPENDS that record: the gap is closed, so
                // there is nothing destroyed left for Repair to put back. Leaving it set offered
                // Repair on the new bridge and would have replaced its full mask with the old
                // partial one.
                hex.blownRoad = 0
            }
            EngineeringWork.FORTIFICATION -> raiseTerrain(hex, TerrainType.FORTIFICATION.value, owner)
            EngineeringWork.AIRFIELD -> raiseTerrain(hex, TerrainType.AIRFIELD.value, owner)
            EngineeringWork.PORT -> raiseTerrain(hex, TerrainType.PORT.value, owner)
            EngineeringWork.BLOW_BRIDGE -> {
                // Recorded BEFORE it is cleared, so Repair restores the mask this crossing
                // actually had rather than inventing a full one.
                hex.blownRoad = hex.road
                hex.road = RoadType.NONE.value
            }

            EngineeringWork.RAZE -> {
                hex.razedTerrain = hex.terrain
                hex.terrain = TerrainType.CLEAR.value
            }

            EngineeringWork.REPAIR -> repair(hex, owner)
        }
    }

    /**
     * Raises a facility on [hex] and hands it to [owner], SPENDING any destruction record the new
     * work supersedes.
     *
     * **Construction must not write [Hex.razedTerrain], and this used to.** It stashed the covered
     * terrain there so that "Repair could put it back", but `razedTerrain >= 0` is exactly how
     * `EngineeringWork.REPAIR.possibleOn` asks *"was something destroyed here?"* — so every
     * freshly finished airfield, port and fortification advertised a Repair chip that demolished
     * it back into the clear or forest hex it had just been built on. It bought nothing either:
     * razing the facility overwrites the field with the facility's own terrain, which is what
     * Repair is supposed to restore, so the covered terrain was never read.
     *
     * Clearing it is the other half. Raze a forest and build an airfield on the clear ground it
     * leaves, and the forest's record would otherwise still be standing under the finished field —
     * Repair again, forest again. Building over razed ground is the player deciding what that hex
     * is now; the loss is settled and the record goes with it. Found in review 2026-08-26.
     */
    private fun raiseTerrain(
        hex: Hex,
        terrain: Int,
        owner: FacilityOwner,
    ) {
        hex.razedTerrain = -1
        hex.terrain = terrain
        claim(hex, owner)
    }

    /**
     * Gives a newly built facility to the side that paid for it.
     *
     * **Without this an airfield is not an airfield.** `MovementRules.hasAirfield` requires
     * `hex.flag == unit.player.country`, and a field built on ordinary unflagged ground carries
     * `flag = -1`, so aircraft could neither base nor resupply on the thing the help text had just
     * promised them. Found in review 2026-08-25; ports have the same dependency for naval supply
     * and deployment, and get the same answer.
     *
     * `owner` is set alongside `flag` because that is the pair the rest of the engine treats as
     * ownership (`CombatApplication.applyHexCapture` writes both), which also settles the
     * follow-up question of when the opponent may use it: by capturing it, exactly as for any
     * other city, port or airfield on the map. A built facility is not special.
     */
    private fun claim(
        hex: Hex,
        owner: FacilityOwner,
    ) {
        if (owner.playerId < 0) return
        hex.owner = owner.playerId
        hex.flag = owner.country
    }

    /**
     * Restores whichever of the two destructible things this hex is missing: its razed terrain
     * first, then a blown bridge. Terrain first because it is the larger loss.
     *
     * A blown bridge is restored to **the mask it had**, from [Hex.blownRoad], and only when that
     * field says a bridge was really taken away. The earlier version tested `road == 0` on a
     * water hex, which is also true of every river the scenario author never bridged — so Repair
     * built free crossings anywhere. See [Hex.blownRoad].
     */
    private fun repair(
        hex: Hex,
        owner: FacilityOwner,
    ) {
        if (hex.razedTerrain >= 0) {
            hex.terrain = hex.razedTerrain
            hex.razedTerrain = -1
            claim(hex, owner)
        } else if (hex.blownRoad != 0) {
            hex.road = hex.blownRoad
            hex.blownRoad = 0
        }
    }

    /** True when [hex] is a river or stream — the two terrains a bridge spans. */
    fun isWaterCrossing(hex: Hex): Boolean =
        hex.terrain == TerrainType.RIVER.value || hex.terrain == TerrainType.STREAM.value

    /** True when [hex] touches OCEAN, which is what a port needs. OSADA's terrain table has no
     *  separate shallow-water type (`Constants.TerrainType`), so ocean is the whole test. */
    fun isCoastal(
        grid: Array<Array<Hex>>?,
        hex: Hex,
    ): Boolean {
        val pos = hex.getPos()
        val cells = HexGeometry.getAdjacent(pos.row, pos.col)
        return cells.any { cell ->
            grid?.getOrNull(cell.row)?.getOrNull(cell.col)?.terrain == TerrainType.OCEAN.value
        }
    }
}
