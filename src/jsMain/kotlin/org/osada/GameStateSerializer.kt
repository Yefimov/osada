package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.Transport
import org.osada.model.getPlayers
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.serializeRuleset
import org.osada.scenario.Scenario
import kotlin.js.json

/**
 * Pure model → JSON serialization for save files and cloud saves.
 *
 * Extracted from the former `GameState` god-class (Single Responsibility): this object
 * only turns the in-memory game graph into plain JS objects/arrays. It performs no I/O
 * and rebuilds nothing — see [GameStateDeserializer]/[GameStateRestore] for the inverse.
 * The emitted key names are part of the save-file format and must stay byte-stable.
 */
object GameStateSerializer {
    /** Bumped when a save's eqids/country codes stop being resolvable by the running game --
     *  e.g. the eqp-united equipment merge renumbered every id, so pre-merge saves (fmt<2 or
     *  missing) are rejected outright rather than loaded with stale ids. See GameStateRestore.
     *
     *  3 = the eqp-pzliga addition (2026-08-15). Merged ids are handed out sequentially over a list
     *  sorted by country/unit-class/name, so adding an efile interleaves its ~3,200 new units and
     *  shifts every id after each insertion: all 46,978 previously merged ids changed, and the
     *  roster grew to 50,589. Deployed scenario XML was carried across by
     *  tools/eqp-merge/remap_united_ids.py, but saves on players' machines cannot be, so fmt=2 is
     *  now rejected too. Note this makes GameStateRestore's fmt=2 `flag` fallback unreachable;
     *  it is kept rather than deleted so the next bump does not have to re-derive it.
     *
     *  4 = the eqp-cc76 addition (2026-08-15, same day). Same mechanism again: 50,589 -> 56,970
     *  merged records, every id renumbered. **Every efile added renumbers everything**, so batch
     *  efile imports into one merge rather than adding them one at a time — each one costs the
     *  players their saves. */
    const val SAVE_FORMAT_VERSION = 4

    /** Full save payload as a JSON string: `{ fmt, scenario, players, campaign }`. */
    fun exportGameState(game: Game): String {
        val scenario = game.scenario
        val base =
            json(
                Pair("fmt", SAVE_FORMAT_VERSION),
                Pair("scenario", scenario?.let { serializeScenario(it) }),
                Pair(
                    "players",
                    scenario
                        ?.map
                        ?.getPlayers()
                        ?.map { serializePlayer(it) }
                        ?.toTypedArray(),
                ),
                Pair("campaign", buildCampaignData(game)),
                // The effective rules this battle actually ran under. Values, not just a profile
                // id -- see `docs/design/ruleset-profiles.md` §4.
                Pair("ruleset", serializeRuleset(ActiveRuleset.currentOrNull())),
            )
        return JSON.stringify(base)
    }

    fun serializeScenario(scenario: Scenario): dynamic =
        json(
            Pair("file", scenario.file ?: ""),
            Pair("name", scenario.name),
            Pair("description", scenario.getDescription()),
            Pair("maxTurns", scenario.maxTurns),
            Pair("date", scenario.date.getTime()),
            Pair("dayTurn", scenario.dayTurn),
            Pair("turnsPerDay", scenario.turnsPerDay),
            Pair("atmosferic", scenario.atmosferic),
            Pair("latitude", scenario.latitude),
            Pair("ground", scenario.ground),
            Pair("iconset", scenario.iconset),
            Pair("effectiveIconset", scenario.effectiveIconset),
            Pair("eqp", scenario.eqp),
            Pair("expPerSide", scenario.expPerSide.toTypedArray()),
            Pair("unitsCostPerSide", scenario.unitsCostPerSide.toTypedArray()),
            Pair("victoryTurns", scenario.map.victoryTurns.toTypedArray()),
            Pair("victoryHoldCounts", scenario.victoryHoldCounts.toTypedArray()),
            Pair("currentPlayerId", scenario.map.currentPlayer?.id ?: 0),
            Pair("turn", scenario.map.turn),
            Pair("map", serializeMap(scenario.map)),
            Pair("reinforcements", serializeReinforcements(scenario.reinforcements)),
            // Authored scenario events, definitions AND progress — see GameStateEventSerialization.
            Pair("events", serializeScenarioEvents(scenario.events)),
        )

    fun serializeMap(map: GameMap): dynamic {
        val rows = js("[]")
        val m = map.map ?: return rows
        for (r in 0 until map.rows) {
            val row = js("[]")
            for (c in 0 until map.cols) {
                row.push(serializeHex(m[r][c]))
            }
            rows.push(row)
        }
        return json(
            Pair("rows", map.rows),
            Pair("cols", map.cols),
            Pair("terrainImage", map.terrainImage),
            Pair("name", map.name),
            Pair("turn", map.turn),
            Pair("maxTurns", map.maxTurns),
            Pair("hexes", rows),
        )
    }

    fun serializeHex(hex: Hex): dynamic =
        json(
            Pair("terrain", hex.terrain),
            Pair("road", hex.road),
            Pair("rail", hex.rail),
            Pair("owner", hex.owner),
            Pair("flag", hex.flag),
            Pair("isDeployment", hex.isDeployment),
            Pair("victorySide", hex.victorySide),
            Pair("name", hex.name),
            Pair("unit", hex.unit?.let { serializeUnit(it) }),
            Pair("airunit", hex.airunit?.let { serializeUnit(it) }),
        )

    fun serializeUnit(unit: GameUnit): dynamic {
        val obj =
            json(
                Pair("eqid", unit.eqid),
                Pair("id", unit.id),
                Pair("owner", unit.owner),
                Pair("flag", unit.flag),
                Pair("isCore", unit.isCore),
                Pair("isDeployed", unit.isDeployed),
                Pair("isSurprised", unit.isSurprised),
                Pair("isMounted", unit.isMounted),
                Pair("hasOverstrength", unit.hasOverstrength),
                Pair("hasResupplied", unit.hasResupplied),
                Pair("hasFired", unit.hasFired),
                Pair("hasMoved", unit.hasMoved),
                Pair("strength", unit.strength),
                Pair("facing", unit.facing),
                Pair("destroyed", unit.destroyed),
                Pair("carrier", unit.carrier),
                Pair("moveLeft", unit.moveLeft),
                Pair("ammo", unit.ammo),
                Pair("fuel", unit.fuel),
                Pair("entrenchment", unit.entrenchment),
                Pair("entrenchTicks", unit.entrenchTicks),
                Pair("experience", unit.experience),
                Pair("hits", unit.hits),
                Pair("leader", unit.leader),
                Pair("nodossier", unit.nodossier),
                Pair("transport", unit.transport?.let { serializeTransport(it) }),
            )
        // Optional key: emitted only when the player renamed the unit, so saves of unrenamed
        // units stay byte-identical to the pre-rename format (old saves simply lack the key).
        unit.customName?.let { obj.asDynamic().customName = it }
        // Same rule for the core-formation id: scenario-only units have none, so their saved
        // shape is unchanged by the hero system.
        unit.formationId?.let { obj.asDynamic().formationId = it }
        if (unit.isTemporaryBorrowed) obj.asDynamic().temporaryBorrowed = true
        if (unit.stalinRegimeBoosted) obj.asDynamic().stalinRegimeBoosted = true
        return obj
    }

    fun serializeTransport(transport: Transport): dynamic =
        json(
            Pair("eqid", transport.eqid),
            Pair("ammo", transport.ammo),
            Pair("fuel", transport.fuel),
        )

    fun serializeReinforcements(reinforcements: Map<Int, List<Scenario.Reinforcement>>): dynamic {
        val arr = js("[]")
        reinforcements.forEach { (turn, list) ->
            val turnArr = js("[]")
            list.forEach { turnArr.push(serializeReinforcement(it)) }
            arr.push(json(Pair("turn", turn), Pair("units", turnArr)))
        }
        return arr
    }

    fun serializeReinforcement(r: Scenario.Reinforcement): dynamic =
        json(
            Pair("turn", r.turn),
            Pair("row", r.row),
            Pair("col", r.col),
            Pair("unit", serializeUnit(r.unit)),
            Pair("id", r.id),
        )

    fun serializePlayer(player: Player): dynamic =
        json(
            Pair("id", player.id),
            Pair("side", player.side),
            Pair("country", player.country),
            Pair("prestige", player.prestige),
            Pair("score", player.score),
            Pair("playedTurn", player.playedTurn),
            Pair("type", player.type.value),
            Pair("airTransports", player.airTransports),
            Pair("navalTransports", player.navalTransports),
            Pair("supportCountries", player.supportCountries.toTypedArray()),
            Pair("prestigePerTurn", player.prestigePerTurn.toTypedArray()),
            Pair("coreUnits", player.getCoreUnitList().map { serializeUnit(it) }.toTypedArray()),
            Pair("dossier", player.dossier),
        )

    /** Campaign-specific save block (core unit roster), or null when not in a campaign. */
    fun buildCampaignData(game: Game): dynamic? {
        val campaign = game.campaign ?: return null
        return game.getCampaignPlayer()?.let { player ->
            val data =
                json(
                    Pair("id", campaign.id),
                    Pair("file", campaign.file),
                    Pair("scenario", campaign.getCurrentScenario().id),
                    Pair("country", campaign.country),
                    Pair("difficulty", campaign.difficulty),
                    Pair("coreUnits", player.getCoreUnitList().map { serializeCoreUnit(it) }.toTypedArray()),
                )
            // Additive and optional: omitted entirely when the run has no narrative state yet, so
            // saves keep their previous shape until the campaign actually records something.
            val narrative: dynamic = CampaignNarrative.snapshot()
            if (narrative != null) data["narrative"] = narrative
            // Same additive-and-optional rule: a run that has produced no formations or heroes
            // writes no `heroes` key, so its save keeps the previous shape exactly.
            val heroes: dynamic = HeroCampaign.snapshot()
            if (heroes != null) data["heroes"] = heroes
            data
        }
    }

    fun serializeCoreUnit(unit: GameUnit): dynamic {
        val obj =
            json(
                Pair("eqid", unit.eqid),
                Pair("id", unit.id),
                Pair("owner", unit.owner),
                Pair("flag", unit.flag),
                Pair("strength", unit.strength),
                Pair("experience", unit.experience),
                Pair("leader", unit.leader),
                Pair("carrier", unit.carrier),
                Pair("isMounted", unit.isMounted),
                Pair("isCore", unit.isCore),
                Pair("isDeployed", unit.isDeployed),
                Pair("hasOverstrength", unit.hasOverstrength),
                Pair("transport", unit.transport?.let { serializeTransport(it) }),
                Pair(
                    "player",
                    unit.player?.let {
                        json(Pair("id", it.id), Pair("side", it.side), Pair("country", it.country))
                    },
                ),
            )
        // Same optional-key rule as serializeUnit.
        unit.customName?.let { obj.asDynamic().customName = it }
        unit.formationId?.let { obj.asDynamic().formationId = it }
        if (unit.isTemporaryBorrowed) obj.asDynamic().temporaryBorrowed = true
        if (unit.stalinRegimeBoosted) obj.asDynamic().stalinRegimeBoosted = true
        return obj
    }
}
