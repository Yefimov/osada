package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.model.ALL_VICTORY_TIERS
import org.osada.model.FrontFactionSlot
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.Transport
import org.osada.model.getPlayers
import org.osada.rules.Engineering
import org.osada.rules.GameRandomSource
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.serializeRuleset
import org.osada.scenario.AuthoredScenarioOptions
import org.osada.scenario.Scenario
import kotlin.js.Json
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
                // The gameplay random stream and how far into it this battle is. Two plain numbers
                // rather than the generator's internal word, so the meaning survives a reader that
                // has never heard of xorshift and cannot be corrupted into an unreachable state.
                // This is also what makes a multiplayer client adopt the host's stream: a joining or
                // resyncing peer restores the host's whole state, this block included
                // (`rules/GameRandomSource`).
                Pair("randomSeed", GameRandomSource.seed().toString()),
                Pair("randomCursor", GameRandomSource.cursor().toString()),
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
            Pair("victoryHoldCountsSide1", scenario.victoryHoldCountsSide1.toTypedArray()),
            Pair("retreatUnitsPerSide", scenario.retreatUnitsPerSide.toTypedArray()),
            Pair("killUnitsPerSide", scenario.killUnitsPerSide.toTypedArray()),
            Pair("mustSurvivePerSide", scenario.mustSurvivePerSide.toTypedArray()),
            // Superseded by `options.typedvh` and kept only so a save stays loadable by the readers
            // that already know this key. It cannot express the authored state -- `== true` folds
            // "the author said nothing" into "the author said no" -- which is why the whole option
            // family now travels in `options` and why the restore prefers that block.
            Pair("typedVictoryHexes", scenario.typedVictoryHexes == true),
            // OG's authored per-scenario options, all 27 of them. See AuthoredScenarioOptions: an
            // unauthored option writes no key, so the block distinguishes "the author said no" from
            // "the author said nothing" and an absent block marks a save written before this
            // existed (`AuthoredOptionsBackfill` completes those from the scenario XML).
            Pair("options", AuthoredScenarioOptions.serialize(scenario)),
            // The running totals of OG's two counted victory conditions. Live game state: a reload
            // that forgot them would reset an evacuation the player had half completed.
            Pair("unitsWithdrawn", scenario.unitsWithdrawn.toTypedArray()),
            Pair("unitsKilled", scenario.unitsKilled.toTypedArray()),
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

    fun serializeHex(hex: Hex): dynamic {
        val obj =
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
        // Optional keys, on the same byte-stability rule the unit record uses: a map with no
        // minefields anywhere -- which is every map unless the `minefields` key is on -- serializes
        // exactly as it did before the mechanic existed, and every pre-2026-08-18 save loads with
        // both fields at 0, which is the correct state for it.
        if (hex.mines != 0) obj.asDynamic().mines = hex.mines
        if (hex.minesDetected != 0) obj.asDynamic().minesDetected = hex.minesDetected
        // Same optional-key rule. `spotMemory` is genuinely turn state -- a save taken mid-turn under
        // `spotting_memory` has to restore what the player could see when they saved it, or reloading
        // would quietly un-spot hexes their recon had already found. `installationSpotted` is NOT
        // stored: it is derived wholly from ownership and is rebuilt by `GameMap.recomputeSpotting`.
        if (hex.spotMemory != 0) obj.asDynamic().spotMemory = hex.spotMemory
        if (hex.victoryTiersSide0 != ALL_VICTORY_TIERS) {
            obj.asDynamic().victoryTiersSide0 = hex.victoryTiersSide0
        }
        if (hex.victoryTiersSide1 != ALL_VICTORY_TIERS) {
            obj.asDynamic().victoryTiersSide1 = hex.victoryTiersSide1
        }
        serializeHexEngineering(obj, hex)
        if (hex.rubble) obj.asDynamic().rubble = 1
        if (hex.crater) obj.asDynamic().crater = 1
        return obj
    }

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
        // Optional keys, same byte-stability rule as `customName`: a formation that has not
        // attacked, carries no half-paid `Fire Discipline` point and holds no lasting suppression
        // serializes exactly as it did before these traits were wired.
        // OG's `Saboteur` leaves a STATE rather than a turn flag, so it has to survive a reload or
        // the sabotaged unit gets its turn back (`GameUnit.sabotaged`). Optional key, same rule.
        if (unit.sabotaged) obj.asDynamic().sabotaged = true
        if (unit.shotsThisTurn != 0) obj.asDynamic().shotsThisTurn = unit.shotsThisTurn
        if (unit.halfShotPending) obj.asDynamic().halfShotPending = true
        if (unit.lastingHits != 0) obj.asDynamic().lastingHits = unit.lastingHits
        if (unit.isTemporaryBorrowed) obj.asDynamic().temporaryBorrowed = true
        if (unit.stalinRegimeBoosted) obj.asDynamic().stalinRegimeBoosted = true
        // The scenario Depot designation. Optional key on the byte-stability rule: 8 of 502
        // scenarios author one, so every other save is unchanged.
        serializeScenarioUnitProperties(obj, unit)
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

    fun serializePlayer(player: Player): dynamic {
        val obj =
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
                Pair("railTransports", player.railTransports),
                Pair("airTransportsMax", player.airTransportsMax),
                Pair("navalTransportsMax", player.navalTransportsMax),
                Pair("railTransportsMax", player.railTransportsMax),
                Pair("defaultExperience", player.defaultExperience),
                Pair("defaultStrength", player.defaultStrength),
                Pair("supportCountries", player.supportCountries.toTypedArray()),
                Pair("prestigePerTurn", player.prestigePerTurn.toTypedArray()),
                Pair("coreUnits", player.getCoreUnitList().map { serializeUnit(it) }.toTypedArray()),
                Pair("dossier", player.dossier),
            )
        serializeAuthoredPurchaseLimits(obj, player)
        return obj
    }

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
        // The scenario Depot designation. Optional key on the byte-stability rule: 8 of 502
        // scenarios author one, so every other save is unchanged.
        serializeScenarioUnitProperties(obj, unit)
        return obj
    }
}

/**
 * OG 9.3's engineering state, split out of [serializeHex] to keep that function inside
 * detekt's complexity budget once the station flag joined it (2026-08-27).
 *
 * Every key here is optional, on the same byte-stability rule the unit record uses: a map with
 * no work in progress, nothing razed and no bridge blown -- every map unless `build_and_repair`
 * is on -- serializes exactly as it did before the mechanic existed.
 *
 * **[Hex.station] is the one exception and is written regardless**, because unlike the rest it
 * is also AUTHORED map data: 915 stations across 143 shipped scenarios arrive from the
 * scenario XML rather than from a sapper, so a save that dropped it under a ruleset with
 * engineering off would lose part of the map.
 */
private fun serializeHexEngineering(
    obj: Json,
    hex: Hex,
) {
    // OG 9.3's engineering state, on the same optional-key rule again: a map with no work in
    // progress, nothing razed and no bridge blown -- every map unless `build_and_repair` is
    // on -- serializes exactly as it did before the mechanic existed. `razedTerrain` and
    // `blownRoad` are stored even when the work is long finished, because they are the only
    // record of what Repair would put back.
    //
    // The job is written as its NAME, not its ordinal. An ordinal is a position in
    // `EngineeringWork`, so inserting a job mid-enum would silently reinterpret every save
    // that had one in progress -- and the multiplayer command already carries the name for
    // exactly that reason. Caught in review 2026-08-25; the two now agree.
    // The BUILDER travels with the job. It decides whose turn end counts the job down and whose
    // flag the finished facility flies, so a save that dropped it would hand a half-built
    // airfield to whichever ally reloaded and ended a turn first (`Hex.constructionPlayer`).
    // A save written before this field existed simply has no builder, and `Engineering`
    // falls back to `constructionSide` for exactly that case.
    Engineering.workName(hex)?.let { obj.asDynamic().construction = it }
    if (hex.construction >= 0) {
        obj.asDynamic().constructionTurns = hex.constructionTurns
        obj.asDynamic().constructionSide = hex.constructionSide
        if (hex.constructionPlayer >= 0) {
            obj.asDynamic().constructionPlayer = hex.constructionPlayer
            obj.asDynamic().constructionCountry = hex.constructionCountry
        }
    }
    if (hex.razedTerrain >= 0) obj.asDynamic().razedTerrain = hex.razedTerrain
    if (hex.blownRoad != 0) obj.asDynamic().blownRoad = hex.blownRoad
    // The airfield's ORIGIN, for OG's `Cannot use dirt airfields`. Stored on the same optional-
    // key rule as the two records above, and for the same reason: it outlives the job that set
    // it, so a reload must not turn a sapper's strip back into a permanent field.
    if (hex.sapperBuilt) obj.asDynamic().sapperBuilt = 1
    // A railroad station is authored map data, but engineers can also build one, so the save
    // has to carry it: reloading must not demolish a station the player paid 18 prestige for.
    if (hex.station) obj.asDynamic().station = 1
    // A dirt strip is authored map data that no rule can create or remove, so it never changes
    // during play -- but the save restores the map from JSON rather than re-reading the scenario
    // XML, so dropping it here would turn every authored dirt field into a permanent one on load.
    if (hex.dirt) obj.asDynamic().dirt = 1
    // A trigger is authored map data whose FIRED half is live game state, so both travel. The
    // action, parameter, equipment and message are restored so a save does not disarm the hex;
    // `triggerFired` is restored so a reload does not re-arm one the player already spent.
    if (hex.trigger != 0) {
        obj.asDynamic().trigger = hex.trigger
        obj.asDynamic().triggerParam = hex.triggerParam
        if (hex.triggerEquip != 0) obj.asDynamic().triggerEquip = hex.triggerEquip
        if (hex.triggerMessage.isNotEmpty()) obj.asDynamic().triggerMessage = hex.triggerMessage
        if (hex.triggerFired) obj.asDynamic().triggerFired = 1
    }
}

/**
 * The scenario author's AI orders (`rules/AiOrders`).
 *
 * Saved for the same reason the purchase cap is: a restore rebuilds the game from the save alone and
 * never re-reads the scenario XML, so an order that was not written down would be gone on the first
 * reload and the enemy's whole authored plan with it. Optional keys throughout -- 469,632 of the
 * corpus's formations carry no order at all, and their saved shape is unchanged.
 */
private fun serializeAuthoredAiOrders(
    obj: Json,
    unit: GameUnit,
) {
    if (unit.aiAnchored) obj.asDynamic().aiAnchored = true
    if (unit.aiHoldUntilTurn != 0) obj.asDynamic().aiHoldUntil = unit.aiHoldUntilTurn
    if (unit.aiFearless) obj.asDynamic().aiFearless = true
    if (unit.aiObjectiveCol >= 0) obj.asDynamic().aiObjCol = unit.aiObjectiveCol
    if (unit.aiObjectiveRow >= 0) obj.asDynamic().aiObjRow = unit.aiObjectiveRow
    if (unit.aiFreeObjectiveDistance != 0) obj.asDynamic().aiFreeOh = unit.aiFreeObjectiveDistance
    if (unit.aiObjectiveFromOrdinal != 0) obj.asDynamic().aiObjFrom = unit.aiObjectiveFromOrdinal
    if (unit.aiFollowsObjectiveUnit) obj.asDynamic().aiFollowPos = true
    if (unit.aiOrdinal != 0) obj.asDynamic().aiOrdinal = unit.aiOrdinal
    // The author's own attachments. Saved because a restore never re-reads the scenario XML, and
    // because a core formation carries them forward with it (`rules/Attachments`).
    if (unit.authoredAttachmentIds.isNotEmpty()) {
        obj.asDynamic().authoredAttachments = unit.authoredAttachmentIds.toTypedArray()
    }
    if (unit.attachmentsForbidden) obj.asDynamic().noAttachments = true
}

/**
 * The scenario's own purchase restrictions and how much of the cap this player has spent.
 *
 * **These have to be SAVED.** A restore rebuilds the game from the save alone -- `GameStateRestore`
 * never re-reads the scenario XML -- so an unsaved `purchasecap` would come back as "uncapped" and
 * an unsaved `buylist` as "unrestricted". The two counters are live game state for the same reason
 * `unitsWithdrawn` is: without them a reload is a way to restore spent slots (`rules/PurchaseCap`).
 *
 * This is the per-player half of the authored scenario; the per-scenario half is the `options` block
 * (`scenario/AuthoredScenarioOptions`), and a save written before either existed is completed from
 * the scenario XML by `scenario/AuthoredOptionsBackfill`.
 *
 * Optional keys throughout, on this file's byte-stability rule: 490 of the 502 deployed scenarios
 * author no cap, 497 author no list and 294 no Fronts/Factions mask, so their saves keep exactly the
 * shape they had.
 */
private fun serializeAuthoredPurchaseLimits(
    obj: Json,
    player: Player,
) {
    player.purchaseCap?.let { obj.asDynamic().purchaseCap = it }
    player.purchaseList?.let { obj.asDynamic().purchaseList = it.toTypedArray() }
    // The Fronts/Factions slots, in the same `country:fronts:factions` text the scenario XML uses,
    // so one parser serves both and a save stays readable by eye.
    if (player.frontFactionSlots.isNotEmpty()) {
        obj.asDynamic().frontFactionSlots = FrontFactionSlot.format(player.frontFactionSlots)
    }
    if (player.transportPoolsAuthored) obj.asDynamic().transportPoolsAuthored = true
    if (player.purchaseGrowthSpent != 0) obj.asDynamic().purchaseGrowthSpent = player.purchaseGrowthSpent
    if (player.replacementCredits != 0) obj.asDynamic().replacementCredits = player.replacementCredits
}

/**
 * The scenario-authored unit properties and the carrier hangar, split out of `serializeUnit` to
 * keep it inside detekt's complexity budget as the 2026-08-30 mechanics landed.
 *
 * Every key is optional, on the same byte-stability rule the rest of the record uses: a game with
 * no depots, no Must-Survive Units, no authored basic strength and no hangars serializes exactly as
 * it did before any of them existed.
 */
private fun serializeScenarioUnitProperties(
    obj: Json,
    unit: GameUnit,
) {
    if (unit.isScenarioDepot) obj.asDynamic().depot = true
    if (unit.mustSurvive) obj.asDynamic().msu = true
    // OG's authored class-attribute override (`.xscn` unit @37). Optional key on the same
    // byte-stability rule as the rest of this function: -1 is "derive it from the class", which is
    // every formation that has no override and every save written before the field existed.
    if (unit.leaderClassTrait > 0) obj.asDynamic().ldrclass = unit.leaderClassTrait
    if (unit.basicStrength != GameUnit.DEFAULT_BASIC_STRENGTH) {
        obj.asDynamic().basicStrength = unit.basicStrength
    }
    if (unit.landedTurn >= 0) obj.asDynamic().landedTurn = unit.landedTurn
    serializeAuthoredAiOrders(obj, unit)
    if (unit.hangar.isNotEmpty()) {
        obj.asDynamic().hangar = unit.hangar.map { GameStateSerializer.serializeUnit(it) }.toTypedArray()
    }
}
