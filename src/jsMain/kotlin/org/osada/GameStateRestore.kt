package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.model.ALL_VICTORY_TIERS
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addPlayersEquipment
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.setHex
import org.osada.rules.Engineering
import org.osada.scenario.AuthoredOptionsBackfill
import org.osada.scenario.AuthoredScenarioOptions
import org.osada.scenario.Campaign
import org.osada.scenario.Scenario
import org.osada.scenario.addReinforcement
import kotlin.js.Date

/**
 * Rebuilds the live game graph from parsed save data and applies UI settings.
 *
 * Extracted from the former `GameState` god-class. Unlike [GameStateDeserializer] (which
 * only builds leaf objects), this collaborator is stateful: it owns a [Game] reference and
 * mutates it — loading equipment, recreating the scenario/map/players, restoring
 * reinforcements and (optionally) the campaign before invoking the ready callback.
 */
class GameStateRestore(
    private val game: Game,
) {
    fun restoreGame(
        scenarioData: dynamic,
        playersData: dynamic,
        campaignData: dynamic?,
        onReady: () -> Unit,
    ) {
        console.log("[osada] restoreGame start", scenarioData.file)
        game.cleanup()
        val newScenario = Scenario(scenarioData.file as String)
        Equipment.name = scenarioData.eqp as? String ?: Equipment.DEFAULT_NAME
        console.log("[osada] restoreGame equipment set to", Equipment.name)
        applyScenarioMetadata(newScenario, scenarioData)

        val rawPlayers = playersData.unsafeCast<Array<dynamic>>()
        val typedPlayers = rawPlayers.map { GameStateDeserializer.deserializePlayer(it) }.toTypedArray()
        addCampaignCoreEquipmentCountries(typedPlayers, campaignData)
        addSavedUnitEquipmentCountries(typedPlayers, scenarioData)
        Equipment.addPlayersEquipment(typedPlayers.toList()) {
            restorePlayersAndFinish(newScenario, scenarioData, typedPlayers, campaignData, onReady)
        }
    }

    /**
     * Campaign core units are restored only after equipment loading, so their country files must be
     * added to the player load set directly from save metadata first. `flag` is a migration fallback
     * for fmt=2 saves written before `equipmentCountry` was introduced. `continue` per
     * missing/unmatched field reads more plainly than nesting these as an `if`.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun addCampaignCoreEquipmentCountries(
        players: Array<Player>,
        campaignData: dynamic?,
    ) {
        val coreUnits = campaignData?.coreUnits ?: return
        for (i in 0 until coreUnits.length) {
            val unit = coreUnits[i]
            val owner = unit.owner as? Int ?: continue
            val player = players.firstOrNull { it.id == owner } ?: continue
            val countries =
                listOfNotNull(
                    unit.equipmentCountry as? Int,
                    unit.transportEquipmentCountry as? Int,
                    unit.carrierEquipmentCountry as? Int,
                    unit.flag as? Int,
                )

            countries.filter { it > 0 }.forEach { country ->
                if (country !in player.supportCountries) player.supportCountries.add(country)
            }
        }
    }

    /**
     * Adds the nationality of every unit ALREADY ON THE SAVED MAP to its owner's equipment load set.
     *
     * A player's `supportCountries` list normally says which extra nations' equipment files a
     * scenario needs, and the save carries that list verbatim. Saves written before the
     * `support="a,b,c,d"` parse fix ([org.osada.scenario.ScenarioPlayerParser]) carry an EMPTY list,
     * so restoring one fetched only the two primary nations' equipment and every support-nation unit
     * on the map came back as an unnamed, iconless, immovable shell with movpoints 0.
     *
     * A unit's own `flag` is the same 1-based country code the `support` attribute uses, so the map
     * itself is a sufficient source to repair those saves — and once the parser fix is in, this
     * simply re-adds ids that are already present. Deliberately map-wide rather than
     * campaign-core-only: the broken units are ordinary scenario units, which
     * [addCampaignCoreEquipmentCountries] never sees.
     */
    private fun addSavedUnitEquipmentCountries(
        players: Array<Player>,
        scenarioData: dynamic,
    ) {
        val mapData = scenarioData.map ?: return
        val hexes = resolveHexRows(mapData) ?: return
        for (r in 0 until (hexes.length as? Int ?: 0)) {
            val row = hexes[r] ?: continue
            for (c in 0 until (row.length as? Int ?: 0)) {
                val hex = row[c] ?: continue
                addUnitCountry(players, hex.unit)
                addUnitCountry(players, hex.airunit)
            }
        }
    }

    private fun addUnitCountry(
        players: Array<Player>,
        unit: dynamic,
    ) {
        if (unit == null || unit == undefined) return
        val flag = (unit.flag as? Int)?.takeIf { it > 0 } ?: return
        val owner = unit.owner as? Int
        players
            .firstOrNull { it.id == owner }
            ?.takeIf { flag !in it.supportCountries }
            ?.supportCountries
            ?.add(flag)
    }

    private fun applyScenarioMetadata(
        newScenario: Scenario,
        scenarioData: dynamic,
    ) {
        newScenario.name = scenarioData.name as? String ?: newScenario.name
        newScenario.setDescription(scenarioData.description as? String ?: "")
        newScenario.maxTurns = scenarioData.maxTurns as? Int ?: newScenario.maxTurns
        newScenario.date = Date((scenarioData.date as? Double) ?: Date().getTime())
        newScenario.dayTurn = scenarioData.dayTurn as? Int ?: 0
        newScenario.turnsPerDay = scenarioData.turnsPerDay as? Int ?: 1
        newScenario.atmosferic = scenarioData.atmosferic as? Int ?: 0
        newScenario.latitude = scenarioData.latitude as? Int ?: 0
        newScenario.ground = scenarioData.ground as? Int ?: 0
        newScenario.iconset = scenarioData.iconset as? Int ?: 0
        newScenario.lockedEffectiveIconset =
            scenarioData.effectiveIconset as? Int ?: newScenario.effectiveIconset
        newScenario.eqp = scenarioData.eqp as? String ?: Equipment.DEFAULT_NAME
        restoreVictoryMetadata(newScenario, scenarioData)
        // OG's authored per-scenario options. Absence-preserving: a key the save does not carry
        // leaves the field alone, so a save written before the block existed reaches
        // `AuthoredOptionsBackfill` with its 27 nulls intact and is completed from the scenario XML
        // (`docs/og-import-rules-backlog.md` — this is the gap that entry recorded).
        AuthoredScenarioOptions.restore(newScenario, scenarioData.options)
    }

    private fun restorePlayersAndFinish(
        newScenario: Scenario,
        scenarioData: dynamic,
        typedPlayers: Array<Player>,
        campaignData: dynamic?,
        onReady: () -> Unit,
    ) {
        console.log("[osada] restoreGame adding players", typedPlayers.size)
        typedPlayers.forEach {
            console.log("[osada] restoreGame addPlayer id", it.id, "side", it.side, "country", it.country)
            newScenario.map.addPlayer(it)
        }
        console.log("[osada] restoreGame players after add", newScenario.map.getPlayers().size)

        newScenario.isLoaded = true
        console.log("[osada] restoreGame restoreMap start")
        restoreMap(newScenario, scenarioData.map)
        console.log("[osada] restoreGame restoreReinforcements start")
        restoreReinforcements(newScenario, scenarioData.reinforcements)
        // Absent in saves written before scenario events existed -> empty list, no events to fire.
        restoreScenarioEvents(newScenario, scenarioData.events)

        newScenario.map.turn = (scenarioData.turn as? Int)
            ?: (scenarioData.map?.turn as? Int)
            ?: 1
        val currentPlayerId =
            (scenarioData.currentPlayerId as? Int)
                ?: (scenarioData.currentPlayer?.id as? Int)
                ?: 0
        console.log(
            "[osada] restoreGame currentPlayerId",
            currentPlayerId,
            "players",
            newScenario.map.getPlayers().size,
        )
        newScenario.map.currentPlayer = newScenario.map.getPlayer(currentPlayerId)

        console.log("[osada] restoreGame setMoveTable")
        newScenario.setMoveTable()

        restoreScenarioArrays(newScenario, scenarioData)

        console.log("[osada] restoreGame setting game.scenario")
        game.scenario = newScenario
        // Saves written before the authored options were serialized carry none of them, and the
        // scenario XML is still the author's own record -- so those are completed from it before
        // the campaign (and then the game) is handed the scenario. Costs one request, and only for
        // those saves; a modern save continues on this same tick.
        AuthoredOptionsBackfill.completeIfAbsent(newScenario, scenarioData) {
            restoreCampaign(campaignData, onReady)
        }
    }

    private fun restoreScenarioArrays(
        newScenario: Scenario,
        scenarioData: dynamic,
    ) {
        val savedVictoryTurns = scenarioData.victoryTurns ?: scenarioData.map?.victoryTurns
        if (savedVictoryTurns != null) {
            newScenario.map.victoryTurns.clear()
            for (i in 0 until savedVictoryTurns.length) {
                newScenario.map.victoryTurns.add(savedVictoryTurns[i] as Int)
            }
        }
        val savedExp = scenarioData.expPerSide
        if (savedExp != null) {
            newScenario.expPerSide.clear()
            for (i in 0 until savedExp.length) {
                newScenario.expPerSide.add(savedExp[i])
            }
        }
        val savedCost = scenarioData.unitsCostPerSide
        if (savedCost != null) {
            newScenario.unitsCostPerSide.clear()
            for (i in 0 until savedCost.length) {
                newScenario.unitsCostPerSide.add(savedCost[i] as Int)
            }
        }
    }

    private fun restoreCampaign(
        campaignData: dynamic?,
        onReady: () -> Unit,
    ) {
        if (campaignData == null) {
            onReady()
            return
        }
        val campaignId = campaignData.id as Int
        val file = campaignData.file as String
        // Old saves have no `narrative` key: deserialize(null) yields empty state, so a
        // pre-narrative save loads with no callbacks rather than failing.
        CampaignNarrative.restore(campaignData.narrative)
        // Same absence rule: a save written before the hero system has no `heroes` key and
        // restores to an empty roster, which the migration below then populates.
        HeroCampaign.restore(campaignData.heroes)
        val campaignIndex = Campaign.findCampaignByFile(file)
        game.campaign =
            Campaign(if (campaignIndex >= 0) campaignIndex else campaignId, campaignData.difficulty as Int) {
                game.campaign?.setScenarioById(campaignData.scenario as Int)
                // DEFERRED, not applied here: `Game.campaignPlayer` is assigned by `setupPlayers()`,
                // which runs from `setupGameState()` AFTER this callback -- `onReady()` below is what
                // reaches it. Restoring the roster here therefore always saw a null campaign player
                // and silently dropped the whole saved core list: an undeployed pre-deployment
                // reserve vanished outright, and a deployed core came back as on-map units that
                // belonged to no core roster (so carry-over and the reserve tray saw nothing).
                // `handleCampaignScenarioLoaded` consumes this once the player exists.
                val savedCoreUnits: dynamic = campaignData.coreUnits
                game.pendingCoreUnitRestore =
                    if (savedCoreUnits == null || savedCoreUnits == undefined) {
                        null
                    } else {
                        PendingCoreUnitRestore(savedCoreUnits.unsafeCast<Array<dynamic>>().toList(), file)
                    }
                onReady()
            }
    }

    // Save data is an external, untrusted JSON blob (may be hand-edited or from an older
    // format) -- any per-cell field can be malformed, so a broad catch-log-rethrow at the cell
    // boundary is intentional (it names which (r,c) failed before propagating).
    @Suppress("TooGenericExceptionCaught")
    private fun restoreMap(
        scenario: Scenario,
        mapData: dynamic,
    ) {
        val map = scenario.map
        configureMapDimensions(map, mapData)
        map.allocMap()

        val hexRows = resolveHexRows(mapData)
        // Two passes, matching ScenarioLoader's contract with GameMap.setHex: the grid must hold
        // its FINAL hexes before any cell is registered, because setHex→addUnit computes spot/ZOC
        // ranges by flagging NEIGHBOURING grid hexes — in a single pass those flags landed on
        // blank alloc'd hexes that the very next iteration replaced, silently losing them.
        val grid = map.map ?: return
        populateHexGrid(map, grid, hexRows)
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                map.setHex(r, c)
            }
        }
    }

    private fun restoreReinforcements(
        scenario: Scenario,
        data: dynamic,
    ) {
        if (data == null) return
        if (js("Array.isArray(data)") as Boolean) {
            restoreReinforcementsFromArray(scenario, data)
        } else {
            restoreReinforcementsFromMap(scenario, data)
        }
    }

    fun applySettings(data: dynamic) {
        console.log("[osada] applySettings start")
        if (data == null) {
            console.log("[osada] applySettings: data is null, skipping")
            return
        }
        console.log("[osada] applySettings data keys:", js("Object.keys(data)"))
        applyDisplaySettings(data)
        applyMarkerSettings(data)
        applyMiscSettings(data)
        applySettingsIsAI(data)
        console.log("[osada] applySettings done")
    }
}

private fun configureMapDimensions(
    map: GameMap,
    mapData: dynamic,
) {
    map.rows = mapData.rows as? Int ?: 0
    map.cols = mapData.cols as? Int ?: 0
    map.terrainImage = mapData.terrainImage as? String ?: ""
    map.name = mapData.name as? String ?: ""
    map.maxTurns = mapData.maxTurns as? Int ?: 1
}

private fun resolveHexRows(mapData: dynamic): dynamic =
    if (js("typeof mapData.hexes !== 'undefined'") as Boolean) mapData.hexes else mapData.map

@Suppress("TooGenericExceptionCaught")
private fun populateHexGrid(
    map: GameMap,
    grid: Array<Array<Hex>>,
    hexRows: dynamic,
) {
    for (r in 0 until map.rows) {
        val row = hexRows[r]
        for (c in 0 until map.cols) {
            try {
                grid[r][c] = buildHex(r, c, row[c], map)
            } catch (e: Throwable) {
                console.error("[osada] restoreMap error at cell", r, c, "message:", e.message, e)
                throw e
            }
        }
    }
}

private fun buildHex(
    r: Int,
    c: Int,
    hexData: dynamic,
    map: GameMap,
): Hex {
    val hex = Hex(r, c)
    hex.terrain = hexData.terrain as? Int ?: TerrainType.CLEAR.value
    hex.road = hexData.road as? Int ?: RoadType.NONE.value
    hex.rail = hexData.rail as? Int ?: RoadType.NONE.value
    // Absent in every save written before minefields existed, and in every save of a game played
    // without them -- 0 is exactly "no minefield here" (`rules/Minefields`).
    hex.mines = hexData.mines as? Int ?: 0
    hex.minesDetected = hexData.minesDetected as? Int ?: 0
    restoreEngineering(hex, hexData)
    // Absent in every save written without `spotting_memory`; 0 is exactly "remembers nothing".
    // `installationSpotted` is deliberately not read -- `recomputeSpotting` derives it from
    // ownership, so a stored copy could only ever go stale (`rules/SpottingModel`).
    hex.spotMemory = hexData.spotMemory as? Int ?: 0
    hex.owner = hexData.owner as? Int ?: -1
    hex.flag = hexData.flag as? Int ?: -1
    hex.isDeployment = hexData.isDeployment as? Int ?: -1
    hex.victorySide = hexData.victorySide as? Int ?: -1
    restoreTypedVictoryHexes(hex, hexData)
    hex.name = hexData.name as? String ?: ""
    applyHexUnitIfPresent(hexData.unit, hex, map)
    applyHexUnitIfPresent(hexData.airunit, hex, map)
    return hex
}

/** Optional because every save predating Typed VH correctly means ordinary mask 7 for both sides. */
internal fun restoreTypedVictoryHexes(
    hex: Hex,
    hexData: dynamic,
) {
    hex.victoryTiersSide0 = hexData.victoryTiersSide0 as? Int ?: ALL_VICTORY_TIERS
    hex.victoryTiersSide1 = hexData.victoryTiersSide1 as? Int ?: ALL_VICTORY_TIERS
}

private fun applyHexUnitIfPresent(
    unitData: dynamic,
    hex: Hex,
    map: GameMap,
) {
    if (unitData != null) {
        val unit = GameStateDeserializer.deserializeUnit(unitData)
        unit.player = map.getPlayer(unit.owner)
        hex.setUnit(unit)
    }
}

private fun restoreReinforcementsFromArray(
    scenario: Scenario,
    data: dynamic,
) {
    for (i in 0 until data.length) {
        val entry = data[i]
        val turn = entry.turn as? Int ?: continue
        val units = entry.units
        for (j in 0 until units.length) {
            val r = units[j]
            val unit = GameStateDeserializer.deserializeUnit(r.unit)
            scenario.addReinforcement(turn, r.row as? Int ?: 0, r.col as? Int ?: 0, unit)
        }
    }
}

private fun restoreReinforcementsFromMap(
    scenario: Scenario,
    data: dynamic,
) {
    val keys = js("Object.keys(data)") as Array<String>
    for (i in 0 until keys.size) {
        val turnKey = keys[i]
        val turn = turnKey.toIntOrNull() ?: continue
        val units = data[turnKey]
        for (j in 0 until units.length) {
            val r = units[j]
            val unit = GameStateDeserializer.deserializeUnit(r.unit)
            scenario.addReinforcement(turn, r.row as? Int ?: 0, r.col as? Int ?: 0, unit)
        }
    }
}

/**
 * The campaign core roster read out of a save, waiting for the campaign player to exist.
 *
 * Carries [campaignFile] as well as the units because the legacy leader migration is keyed to the
 * campaign and was gated behind the same null check, so it never ran on a restore either.
 */
internal data class PendingCoreUnitRestore(
    val savedUnits: List<dynamic>,
    val campaignFile: String,
)

/** OG 9.3's per-hex engineering state. Split out of `buildHex` purely for its complexity
 *  budget; every field is an optional save key that defaults to "nothing here".
 *
 *  `internal` rather than private so `OgOptionalRulesTest` can round-trip a job through
 *  `serializeHex` and back: the pair is the thing worth locking, and asserting on the emitted
 *  JSON alone would not catch a reader that stopped reading a key the writer still writes. */
internal fun restoreEngineering(
    hex: Hex,
    hexData: dynamic,
) {
    // Written as a name since 2026-08-25 (see the serializer). An unknown name -- a job this
    // build does not have -- restores as "nothing in progress" rather than as job zero, which is
    // the whole reason the format is a name.
    hex.construction = Engineering.workOrdinal(hexData.construction as? String)
    hex.constructionTurns = hexData.constructionTurns as? Int ?: 0
    hex.constructionSide = hexData.constructionSide as? Int ?: -1
    // Absent in saves written before 2026-08-26; -1 is "builder unknown", which is what
    // `Engineering.advanceTurn` falls back to `constructionSide` for.
    hex.constructionPlayer = hexData.constructionPlayer as? Int ?: -1
    hex.constructionCountry = hexData.constructionCountry as? Int ?: -1
    hex.razedTerrain = hexData.razedTerrain as? Int ?: -1
    hex.blownRoad = hexData.blownRoad as? Int ?: 0
    hex.sapperBuilt = (hexData.sapperBuilt as? Int ?: 0) != 0
    hex.station = (hexData.station as? Int ?: 0) != 0
    hex.dirt = (hexData.dirt as? Int ?: 0) != 0
    restoreHexTrigger(hex, hexData)
    hex.rubble = (hexData.rubble as? Int ?: 0) != 0
    hex.crater = (hexData.crater as? Int ?: 0) != 0
}

/**
 * An OG trigger hex's four authored fields plus its live fired flag, split from
 * [restoreEngineering] to keep that function inside detekt's complexity budget.
 *
 * The authored half travels so a save does not disarm the hex; `triggerFired` travels so a reload
 * does not re-arm one the player already spent (`rules/TriggerHexes`).
 */
private fun restoreHexTrigger(
    hex: Hex,
    hexData: dynamic,
) {
    hex.trigger = hexData.trigger as? Int ?: 0
    hex.triggerParam = hexData.triggerParam as? Int ?: 0
    hex.triggerEquip = hexData.triggerEquip as? Int ?: 0
    hex.triggerMessage = hexData.triggerMessage as? String ?: ""
    hex.triggerFired = (hexData.triggerFired as? Int ?: 0) != 0
}

/** A serialized `Int` array as a list, or null when the key was absent from the save. */
private fun intList(raw: dynamic): List<Int>? =
    if (raw == null) null else (0 until (raw.length as Int)).mapNotNull { i -> raw[i] as? Int }

/**
 * OG's per-side objective-hold thresholds.
 *
 * A save written before the counts became per-side (2026-08-30) carries only the one array, and
 * side 1 correctly restores empty -- which is the same "no hold requirement" it had then.
 */
internal fun restoreVictoryMetadata(
    newScenario: Scenario,
    scenarioData: dynamic,
) {
    newScenario.victoryHoldCounts =
        intList(scenarioData.victoryHoldCounts) ?: newScenario.victoryHoldCounts
    newScenario.victoryHoldCountsSide1 =
        intList(scenarioData.victoryHoldCountsSide1) ?: newScenario.victoryHoldCountsSide1
    intList(scenarioData.unitsWithdrawn)?.let { newScenario.unitsWithdrawn = it.toMutableList() }
    intList(scenarioData.unitsKilled)?.let { newScenario.unitsKilled = it.toMutableList() }
    intList(scenarioData.retreatUnitsPerSide)?.let { newScenario.retreatUnitsPerSide = it }
    intList(scenarioData.killUnitsPerSide)?.let { newScenario.killUnitsPerSide = it }
    intList(scenarioData.mustSurvivePerSide)?.let { newScenario.mustSurvivePerSide = it }
    // `typedvh` travels in the authored-options block now, which can say "the author said
    // nothing"; this top-level key cannot -- it was written as `== true` and folds unauthored into
    // forbidden -- so it is read only by the saves that have nothing better. Those are then
    // completed from the scenario XML by `AuthoredOptionsBackfill`, which overrides this.
    val options = scenarioData.options
    if (options == null || options == undefined) {
        newScenario.typedVictoryHexes = scenarioData.typedVictoryHexes as? Boolean
    }
}
