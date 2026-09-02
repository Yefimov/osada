package org.osada.scenario

import org.osada.GroundCondition
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.TerrainEx
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.getUnits
import org.osada.model.invalidateWaterAccessCache
import org.osada.movTable
import org.osada.rules.TypedVictoryHexes
import org.osada.scoreGains
import kotlin.js.Date
import kotlin.js.json

@JsExport
@JsName("Scenario")
class Scenario(
    val file: String?,
) {
    companion object {
        private const val MILLIS_PER_DAY = 86400000
        private const val HOLD_OUTCOME_TIERS = 3
        private const val BRILLIANT_TIER = 0
        private const val VICTORY_TIER = 1
        private const val TACTICAL_TIER = 2

        /** OG `iconset`: 0=Default 1=Snow 2=Desert 3=Jungle. */
        private const val ICONSET_DEFAULT = 0
        private const val ICONSET_SNOW = 1
    }

    var name: String = ""
    var maxTurns: Int = 0
    var date: Date = Date()
    var atmosferic: Int = 0
    var latitude: Int = 0
    var ground: Int = 0
    var iconset: Int = 0
    internal var lockedEffectiveIconset: Int? = null

    /**
     * The unit iconset selected at scenario load: [iconset] as authored, except that a
     * **Frozen**-ground scenario which authored no iconset at all starts with snow unit art.
     *
     * OG scenario authors set `iconset` by hand and forgot it often. Measured across the shipped
     * scenarios, 47 of the 53 with Frozen ground do set snow, so the pairing is the intent and the
     * omissions are oversights — the visible symptom was Operation Uranus (November 1942, frozen,
     * snowing, snow-covered map art) fielding summer-camo paratroopers. Frozen ground is the only
     * initial trigger: falling snow over unfrozen ground must not change uniforms, and Dry/Mud are
     * left strictly alone. The locked value also survives later weather/ground transitions.
     *
     * Read this, never [iconset], on unit rendering paths. [iconset] stays the raw imported value;
     * saves persist both it and this fixed battle-season snapshot. Map art is always shown as
     * authored and never filtered from either value.
     */
    val effectiveIconset: Int
        get() =
            lockedEffectiveIconset
                ?: if (iconset == ICONSET_DEFAULT && ground == GroundCondition.FROZEN.value) {
                    ICONSET_SNOW
                } else {
                    iconset
                }

    var weatherCanChangeGround: Boolean = false

    /**
     * Open General's own per-scenario **Game settings** switches, imported 2026-08-26 from the
     * `.xscn` bitfield at bytes 1009-1017 (`tools/og-import/SCENARIO_FORMAT_NOTES.md`).
     *
     * **`null` means the scenario was never re-exported, and is NOT the same as `false`.** 397 of
     * the 502 deployed scenarios carry these attributes; the rest name a source this install cannot
     * read (29 legacy `.scn`) or cannot find (76). A rule that consults them must treat `null` as
     * *"the author did not say"* and fall back to its ruleset key alone — otherwise re-exporting a
     * scenario would be the difference between a mechanic existing and not, which is the §5.10
     * hazard in a new costume.
     *
     * These were the missing half of `rules.og_fidelity.gap.authored_options` — the profile gap
     * entry that §T retired, because with `TrueDLOF` and `UnitsBlockDLOF` built there is nothing
     * left in it. OG lets each scenario decide, and until §O OSADA applied one set of rules to all
     * of them. Measured over the
     * 397 scenarios that do carry them: Repair 362, Build 360, Blow 350, Extended LOS 321,
     * barrage 298, extended naval 203, TrueDLOF 131, Air ZOC 79, UnitsBlockDLOF 23, and air
     * missions **0** — the last of which is why building air missions would serve no shipped
     * battle at all.
     *
     * **These are serialized**, along with every option below them, in the save's `options` block —
     * a restore never re-reads the scenario XML, so until they were, every reload came back
     * unauthored. [AuthoredScenarioOptions] owns the attribute-to-field table that the loader, the
     * save writer, the save reader and [copy] all share; [AuthoredOptionsBackfill] completes a save
     * written before that existed from the XML.
     */
    var canBuild: Boolean? = null
    var canBlow: Boolean? = null
    var canRepair: Boolean? = null
    var extendedLos: Boolean? = null

    /**
     * The rest of the option bitfield. Two of these are read and three are not, and the group is
     * declared together because the whole bitfield is parsed in one place.
     *
     * **Read:** [barrageAllowed] since schema 7, as the scenario half of the `barrage` key's gate
     * in `rules/Barrage` (356 of the 457 readable scenarios allow it); [trueDirectLof] and
     * [unitsBlockLof] since §T, in `rules/ExtendedLos.hasLineOfFire` (131 and 23 of the 397
     * scenarios that carry the bitfield).
     *
     * **[airZoc] and [extendedNaval] have had readers since §U** (`rules/AirZoneOfControl` and
     * `rules/ExtendedNaval`); this paragraph was not updated when they did.
     *
     * **[airMissions] is the one genuinely dead field, and it is deliberately kept.** It is
     * parsed, deployed and read by no rule — but **0 of the 397 deployed scenarios and 108 of the
     * whole 6,046-file OG corpus set it**, so there is nothing for a rule to run on. Removing the
     * attribute would cost the import a decoded bit and gain nothing; building the mechanic would
     * serve no content OSADA ships. It stays as data with a stated reason, and
     * `docs/og-fidelity-plan.md` §Y.4's standing instruction applies: re-take the measurement if
     * an imported campaign ever authors it, and it jumps the queue. The old text read
     * [airMissions] — the only one **no** shipped scenario sets at all, which is why
     * `docs/og-fidelity-plan.md` §M puts building it last.
     */
    var trueDirectLof: Boolean? = null
    var unitsBlockLof: Boolean? = null
    var barrageAllowed: Boolean? = null
    var airZoc: Boolean? = null
    var airMissions: Boolean? = null
    var extendedNaval: Boolean? = null

    /**
     * OG's *"air units can be fired on when entering AD range"* — the scenario's own switch for
     * anti-aircraft interception, authored by **404 of the 457** scenarios whose source parses.
     *
     * Null means the scenario's source could not be read, and every reader treats that as
     * permitted, exactly as the other authored switches do (`docs/og-fidelity-plan.md` §AD).
     */
    var airIntercept: Boolean? = null

    /** OG's *"ports do not supply hexes"*. 84 scenarios. */
    var portsNoSupply: Boolean? = null

    /** OG's *"ports do not deploy naval units"*. 48 scenarios. */
    var portsNoNavalDeploy: Boolean? = null

    /**
     * OG's *"no prototypes"* — this scenario awards none, however the campaign is going. 43 of the
     * 397 deployed scenarios whose source parses.
     *
     * Null means the source could not be read, and every reader treats that as PERMITTED, exactly
     * as the other authored switches do (`docs/og-fidelity-plan.md` §AD).
     */
    var prototypesAllowed: Boolean? = null

    /**
     * OG's **prototype time frame** — how many months ahead the brilliant-victory award may reach.
     * `.xscn` `@848`, gated on `opt_custom_time_frame` (`@1010` bit 0); **69 of the 397 deployed
     * scenarios whose source parses set it**, 1,007 corpus-wide.
     *
     * Null means the author left OG's own window alone, and [getPrototypeUnitsAvailable] then keeps
     * OSADA's long-standing next-calendar-year selection — see that function for why the month
     * window is not applied to content that carries no month data.
     *
     * The importer substitutes nothing: it deploys OG's byte, and **zero means "switch on, value
     * never configured"** (48 of those 69), which the loader turns into the manual's documented
     * default of [PROTOTYPE_DEFAULT_MONTHS]. The current OG changelog raises the accepted range to
     * 60 months while this install exercises only 1-12, so no upper bound is enforced.
     */
    var prototypeTimeFrameMonths: Int? = null

    /**
     * OG's **custom music track** for this battle — `.xscn` `@127`, a filename gated on
     * `opt_custom_music` (`@1009` bit 6). **583 deployed scenarios author one**, over 62 distinct
     * names corpus-wide.
     *
     * Null means the author left the ordinary soundtrack alone. `ui/ScenarioMusic` decides whether
     * anything can actually be played from the licensed manifest; absent source files and formats
     * browsers cannot decode fall back to silence without a network request.
     */
    var musicTrack: String? = null

    /**
     * OG's *"Subs no need DLOF"* — this scenario exempts submarines from the direct-line-of-fire
     * requirement `ExtendedNaval` bullet 4 imposes. 3 scenarios corpus-wide, which is why it is the
     * smallest of the three wired on 2026-08-29 and was wired anyway: the rule it overrides was
     * already built, so the whole cost was one condition.
     */
    var subsNeedLineOfFire: Boolean? = null

    /**
     * OG's *"True range 0: units with Range 0 cannot attack adjacent hexes"*
     * (`Manual_OSuite-Scenario.pdf` p.23). **295 of the 397 deployed scenarios whose source parses.**
     *
     * OSADA has always read a `gunrange` of 0 as "adjacent only" ([AttackEligibility]'s
     * `if (range == 0) range = 1`), which IS OG's behaviour with this option off. With it on, such a
     * formation cannot attack at all — 3,434 of the 56,970 shipped records carry `gunrange = 0`,
     * and they are the engineering, transport and support units OG means by it.
     *
     * Null means the source could not be read, and every reader treats that as the option being
     * OFF — the direction that keeps today's behaviour for the 105 unreadable scenarios.
     */
    var trueRangeZero: Boolean? = null

    /**
     * OG's *"True spotting 0: units with Spotting of 0 don't spot adjacent hexes"* (same page).
     * **295 scenarios**, and the one place OSADA was wrong in the OPPOSITE direction.
     *
     * `HexGeometry.getRing` returns nothing for a radius of 0, so a `spotrange = 0` formation has
     * always seen only its own hex here — which is OG's behaviour with the option **ON**. The
     * scenarios that set it were therefore already right, and the ~3,000 that do not were quietly
     * blinding 303 records that OG lets see their neighbours. Reading this switch is what lets the
     * unauthored case get its adjacent ring back.
     */
    var trueSpottingZero: Boolean? = null

    /**
     * OG's *"Reinforces arrive when player is active: reinforcements arrive in the player turn, not
     * at the start of the Player 1 turn"* (same page). **202 scenarios.**
     */
    var reinforcementsWhenActive: Boolean? = null

    /**
     * OG's *"BB, CV & BC can fire as FlaKs: those ship classes can defend from air attacks with a
     * range of 1, and attack planes at their range"* (same page). **197 scenarios.**
     */
    var capitalShipsAsFlak: Boolean? = null

    /**
     * OG's *"Use current/basic strength as defined"* — **332 scenarios**, the largest authored
     * option in the list and the last to be wired (2026-08-30).
     *
     * ON leaves the authored current and basic strengths alone; OFF resets `current := basic` at
     * load. See [org.osada.model.GameUnit.basicStrength] for why OFF is the harsher of the two and
     * why that inversion held this back for a day.
     */
    var useBasicStrength: Boolean? = null

    /**
     * OG manual §3.7.4's *"number of units to retreat"*, per side, and §3.7's kill quota — both
     * recovered 2026-08-30 (`@1021` bits 2 and 4, counts at `Moff-34/-33` and `Moff-30/-29`).
     *
     * 0 means the side has no such objective, which is not the same as "retreat none": a side
     * without a quota simply cannot win this way. [org.osada.rules.ExtendedVictory] reads both.
     *
     * [unitsWithdrawn] and [unitsKilled] are the running counts, and they are live game state —
     * serialized, because a reload that forgot them would reset an objective the player had half
     * completed.
     */
    var retreatUnitsPerSide: List<Int> = emptyList()

    var killUnitsPerSide: List<Int> = emptyList()

    /**
     * OG manual §3.7.1's *"number of the MSU that need to survive not to lose the scenario"*, per
     * side (`@1021` bit 3, counts at `Moff-32/-31`).
     *
     * Unlike the retreat and kill quotas beside it this is a **losing** condition rather than a
     * winning one: falling below it loses the scenario. `zero_msu` lets an efile author 0 meaning
     * *"none of them has to survive"* rather than *"all of them must"* — `EFILE_NOKORP/equip.cfg`
     * spells that out, which is why 0 is stored as "no requirement" and not as a trap.
     */
    var mustSurvivePerSide: List<Int> = emptyList()

    /**
     * OG's *"Allow Typed VH"* (`opt_specific_vh`, `@1010` bit 1) — manual §3.7.2's per-level
     * victory hexes. With it off every objective counts for every level, which is OSADA's
     * long-standing behaviour and what `Hex.victoryTiersForSide`'s default of 7 already expresses.
     */
    var typedVictoryHexes: Boolean? = null

    /**
     * OG's **"EH for MSU only"** (`opt_eh_for_msu_only`, `@1016` bit 6) — **15 deployed scenarios,
     * 93 corpus-wide**, named from the Suite's own `ScenOptionsUsed_*.csv`.
     *
     * With it on, an escape hex accepts **only a Must-Survive Unit**: the exit is for the formation
     * the author needs brought out, not for whatever happens to reach it. Both halves were already
     * built — the exits in the extended-victory pass and [org.osada.model.GameUnit.mustSurvive]
     * from unit `@43` bit 0 — so this is one condition joining two existing mechanics, read by
     * [org.osada.rules.ExtendedVictory.canWithdrawThrough].
     *
     * Absent (null) means unrestricted, the direction that takes nothing from the player and the
     * one 105 scenarios with unreadable sources depend on.
     */
    var escapeHexesForMsuOnly: Boolean? = null

    /**
     * OG's **"avoid paratroop drops on ocean"** (`opt_no_paradrop_ocean`, `@1009` bit 4) — **19
     * deployed scenarios, 526 corpus-wide**, stored INVERTED as a permission so `1` always means
     * "allowed" and an absent attribute needs no special case.
     *
     * The option exists because the drop is otherwise legal, and it was legal here too:
     * `rules/EmbarkRules.getDisembarkPositions` filters candidate hexes with the TRANSPORT's
     * movement table, and an aircraft's makes ocean passable. That object reads this.
     */
    var paradropOnOceanAllowed: Boolean? = null

    /**
     * OG's **"core units added by design do not count against the CAP"** (`opt_cores_off_cap`,
     * `@1015` bit 5) — **89 deployed scenarios**, and the other half of `opt_purchase_cap`.
     *
     * The option only says anything because by DEFAULT such a formation does count: a unit the
     * author enrols into the campaign core (OG's Make Core) is a net-new formation like a bought
     * one. [org.osada.rules.PurchaseCap] owns both halves.
     *
     * **Inert on shipped content and deployed anyway**: no deployed scenario authors both a purchase
     * cap and a Make Core unit, so nothing exercises the exemption today. It is here because the cap
     * rule cannot otherwise represent what an author asked for, and because leaving the option
     * unread would be a decoded bit with no rule — the exact state this backlog exists to close.
     */
    var coresExemptFromPurchaseCap: Boolean? = null

    var unitsWithdrawn: MutableList<Int> = mutableListOf(0, 0)

    var unitsKilled: MutableList<Int> = mutableListOf(0, 0)

    var turnsPerDay: Int = 1
    var dayTurn: Int = 0
    var reinforcements: MutableMap<Int, MutableList<Reinforcement>> = mutableMapOf()

    /** Optional authored message box per reinforcement turn (`<reinforce turn="2" message="...">`),
     *  shown when that wave actually deploys. Empty for scenarios that do not author one. */
    var reinforcementMessages: MutableMap<Int, String> = mutableMapOf()

    /**
     * Optional authored `<events>` (see [ScenarioEvent]): declarative, once-only reactions to the
     * battle reaching a place or a state. Empty for every scenario that does not author any, which
     * is what makes the feature invisible to the ~700 imported scenarios.
     *
     * `internal` deliberately: [Scenario] is `@JsExport`, and events are engine-side data with no
     * JS-facing consumer.
     */
    internal var events: MutableList<ScenarioEvent> = mutableListOf()

    /** Optional OG-style objective-hold thresholds for the turn-limit outcome, ordered
     * brilliant / victory / tactical. Empty keeps the legacy all-objectives-or-defeat rule.
     *
     * This is SIDE 0's triple. [victoryHoldCountsSide1] carries side 1's, because OG stores one
     * per side and they routinely differ — see [checkTimedOutcome]. */
    var victoryHoldCounts: List<Int> = emptyList()

    /**
     * Side 1's objective-hold thresholds, OG's second triple.
     *
     * **Recovered from the binary 2026-08-30** at `Moff-37..-35`, beside side 0's at `Moff-41..-39`
     * and the BV/V/TV turn limits at `Moff-45`. Until then [victoryHoldCounts] was parsed out of
     * the BRIEFING PROSE — a regex over *"After N turns control 4/3/2 VHs"* — which only ever
     * worked where an author wrote that sentence in English, and gave both sides the same numbers
     * because prose has only one of them. 58 of the 502 deployed scenarios author a hold condition.
     *
     * Empty means this side has none, which is not the same as "hold zero": a side with no hold
     * requirement falls back to the ordinary turn-limit outcome.
     */
    var victoryHoldCountsSide1: List<Int> = emptyList()
    var map: GameMap = GameMap()
    var expPerSide: MutableList<dynamic> =
        mutableListOf(
            json("exp" to 0, "count" to 0),
            json("exp" to 0, "count" to 0),
        )
    var unitsCostPerSide: MutableList<Int> = mutableListOf(0, 0)
    var isLoaded: Boolean = false
    var eqp: String = Equipment.DEFAULT_NAME
    private var description: String = ""
    private var onLoadFinished: (() -> Unit)? = null

    init {
        if (file != null) {
            val data = ScenarioLoader.getScenarioDataByFileName(file)
            if (data != null) {
                name = data[1] as String
                description = data[2] as String
            }
        }
    }

    fun load(callback: () -> Unit) {
        onLoadFinished = callback
        ScenarioLoader.loadScenario(this)
    }

    fun onLoadFinished() {
        if (isLoaded) {
            setMoveTable()
            val scoreMap = mutableMapOf<Int, Int>()
            val players = map.getPlayers()
            players.forEach { player ->
                scoreMap[player.id] =
                    map.sidesVictoryHexes[player.side].size * (scoreGains["objectivePerTurn"] ?: 0) / map.maxTurns
            }
            map.getUnits().forEach { unit ->
                val playerId = unit.player?.id ?: -1
                scoreMap[playerId] =
                    (scoreMap[playerId] ?: 0) +
                    if (unit.isCore) (scoreGains["coreUnit"] ?: 0) else (scoreGains["normalUnit"] ?: 0)
            }
            players.forEach { player ->
                player.updateScore(scoreMap[player.id] ?: 0)
            }
        }
        onLoadFinished?.invoke()
    }

    fun checkDefeat(
        side: Int,
        humanSides: Int,
    ): Boolean {
        if (map.turn >= map.maxTurns && (side == humanSides || humanSides == 2)) {
            return map.getPlayers().none { it.side == side && it.playedTurn < map.maxTurns }
        }
        return false
    }

    /**
     * The result a capture win earns, capped by how long it took.
     *
     * **OG's Typed VH (manual §3.7.2) raises the floor, never the ceiling.** With typed hexes
     * authored, the level a side has actually completed is the best it can claim; the turn limits
     * then cap it exactly as before, so taking the brilliant-victory hex on the last turn still
     * yields whatever the clock allows. Without them — which is every scenario that does not set
     * `opt_specific_vh` — this is the turn-based answer OSADA has always given.
     */
    fun checkVictory(): String = victoryOutcome(this, map.currentPlayer?.side ?: 0)

    /** Returns the authored result when the final human turn has actually completed. */
    fun checkTimedOutcome(
        side: Int,
        humanSides: Int,
    ): String? {
        if (!checkDefeat(side, humanSides)) return null
        // OG stores a hold requirement PER SIDE and the two routinely differ -- `bn9s00` asks 4/4/4
        // of one side and 3/2/1 of the other. Reading one triple for both was an artefact of
        // sourcing them from briefing prose, which has only one set of numbers in it.
        val counts = if (side == 0) victoryHoldCounts else victoryHoldCountsSide1
        val result =
            if (counts.size < HOLD_OUTCOME_TIERS) {
                "lose"
            } else {
                val held = objectivesHeldBy(map, side)
                when {
                    held >= counts[BRILLIANT_TIER] -> "briliant"
                    held >= counts[VICTORY_TIER] -> "victory"
                    held >= counts[TACTICAL_TIER] -> "tactical"
                    else -> "lose"
                }
            }
        return result
    }

    fun getDescription(): String = description

    fun setDescription(desc: String) {
        description = desc
    }

    fun endTurn() {
        dayTurn++
        if (dayTurn >= turnsPerDay) {
            dayTurn = 0
            date = Date(date.getTime() + MILLIS_PER_DAY)
        }
        map.endTurn()
    }

    /**
     * Publishes the active movement-cost table for this scenario's ground condition.
     *
     * [TerrainEx.movementCostTable] returns PM's own `movTableDry`/`movTableFrozen`/`movTableMud`
     * with the active efile's OG `[terrain-cost]` laid over it, and falls back to the PM table
     * unchanged for any efile that ships no TerrainEx data. Called on scenario load, on save
     * restore, and on every weather change that flips the ground condition, so an efile switch
     * always lands before the next read of `movTable`.
     *
     * `hasWaterAccess`/`hasOpenWaterAccess` cache answers derived from this table, so they are
     * dropped here rather than left to go stale across a ground-condition change.
     */
    fun setMoveTable() {
        movTable = TerrainEx.movementCostTable(ground)
        map.invalidateWaterAccessCache()
    }

    fun showStatistics() {
        // Intentional no-op: the legacy JS's own `showStatistics` was already an empty stub
        // (`this.showStatistics = function() {}`, never implemented upstream either).
    }

    fun copy(other: Scenario) {
        maxTurns = other.maxTurns
        date = Date(other.date.getTime())
        atmosferic = other.atmosferic
        latitude = other.latitude
        // Every authored option, from one table -- the ten that used to be listed here carried the
        // same bug the save file did: the fourteen added after this function was written were
        // silently dropped by it (`AuthoredScenarioOptions`).
        AuthoredScenarioOptions.copy(this, other)
        iconset = other.iconset
        ground = other.ground
        lockedEffectiveIconset = other.effectiveIconset
        turnsPerDay = other.turnsPerDay
        eqp = other.eqp
        reinforcementMessages.clear()
        reinforcementMessages.putAll(other.reinforcementMessages)
        victoryHoldCounts = other.victoryHoldCounts.toList()
        victoryHoldCountsSide1 = other.victoryHoldCountsSide1.toList()
        retreatUnitsPerSide = other.retreatUnitsPerSide.toList()
        killUnitsPerSide = other.killUnitsPerSide.toList()
        mustSurvivePerSide = other.mustSurvivePerSide.toList()
        unitsWithdrawn = other.unitsWithdrawn.toMutableList()
        unitsKilled = other.unitsKilled.toMutableList()
        reinforcements.clear()
        other.reinforcements.forEach { (turn, list) ->
            list.forEach { r ->
                val unit = GameUnit(r.unit.eqid).apply { copy(r.unit) }
                addReinforcement(turn, r.row, r.col, unit)
            }
        }
        copyEventsFrom(other)
        map.copy(other.map)
        file?.let { /* keep */ }
        setMoveTable()
    }

    data class Reinforcement(
        val turn: Int,
        val row: Int,
        val col: Int,
        val unit: GameUnit,
        val id: Int,
    )
}

/**
 * Objective hexes currently owned by [side].
 *
 * Split out of [Scenario.checkTimedOutcome] to keep that function inside detekt's complexity budget
 * once the hold thresholds became per-side, and kept at FILE level rather than as a method because
 * [Scenario] is already at its function budget too.
 */
private fun objectivesHeldBy(
    map: GameMap,
    side: Int,
): Int {
    var held = 0
    for (row in 0 until map.rows) {
        for (col in 0 until map.cols) {
            val hex = map.map?.getOrNull(row)?.getOrNull(col) ?: continue
            if (hex.victorySide != -1 && hex.owner != -1 && map.getPlayer(hex.owner).side == side) held++
        }
    }
    return held
}

/** Outcome names by tier index, brilliant first — the strings the campaign layer expects. */
private val OUTCOME_NAMES = listOf("briliant", "victory", "tactical")

/**
 * The result a capture win earns [side], capped by how long it took.
 *
 * At file level because [Scenario] is at its function budget. OG's Typed VH (manual §3.7.2) raises
 * the floor and never the ceiling: the level a side has actually completed is the best it can
 * claim, and the turn limits then cap it exactly as before.
 */
private fun victoryOutcome(
    scenario: Scenario,
    side: Int,
): String {
    val map = scenario.map
    val byTurn =
        when {
            map.turn <= map.victoryTurns[0] -> 0
            map.turn <= map.victoryTurns[1] -> 1
            map.turn <= map.victoryTurns[2] -> 2
            else -> return "lose"
        }
    val byHexes = TypedVictoryHexes.completedTier(scenario, map, side) ?: byTurn
    return OUTCOME_NAMES[maxOf(byTurn, byHexes)]
}
