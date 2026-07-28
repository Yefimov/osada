package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.hero.HeroCampaign
import org.osada.hero.LeaderMigration
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addPlayersEquipment
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.getPlayers
import org.osada.model.restoreCoreUnitList
import org.osada.model.setHex
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
                listOf(
                    unit.equipmentCountry as? Int,
                    unit.transportEquipmentCountry as? Int,
                    unit.carrierEquipmentCountry as? Int,
                    unit.flag as? Int,
                ).filterNotNull()

            countries.filter { it > 0 }.forEach { country ->
                if (country !in player.supportCountries) player.supportCountries.add(country)
            }
        }
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
        val holdCounts = scenarioData.victoryHoldCounts
        if (holdCounts != null) {
            newScenario.victoryHoldCounts =
                (0 until holdCounts.length).mapNotNull { i -> holdCounts[i] as? Int }
        }
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
        restoreCampaign(newScenario, campaignData, onReady)
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
        newScenario: Scenario,
        campaignData: dynamic?,
        onReady: () -> Unit,
    ) {
        if (campaignData == null) {
            onReady()
            return
        }
        val data = campaignData
        val campaignId = data.id as Int
        val file = data.file as String
        // Old saves have no `narrative` key: deserialize(null) yields empty state, so a
        // pre-narrative save loads with no callbacks rather than failing.
        CampaignNarrative.restore(data.narrative)
        // Same absence rule: a save written before the hero system has no `heroes` key and
        // restores to an empty roster, which the migration below then populates.
        HeroCampaign.restore(data.heroes)
        val campaignIndex = Campaign.findCampaignByFile(file)
        game.campaign =
            Campaign(if (campaignIndex >= 0) campaignIndex else campaignId, data.difficulty as Int) {
                game.campaign?.setScenarioById(data.scenario as Int)
                val campaignPlayer = game.getCampaignPlayer()
                val savedCoreUnits = data.coreUnits
                if (campaignPlayer != null && savedCoreUnits != null) {
                    newScenario.map.restoreCoreUnitList(
                        campaignPlayer,
                        savedCoreUnits.unsafeCast<Array<dynamic>>().toList(),
                    )
                }
                // After the core roster exists, so every core unit has a formation id to key on.
                // Idempotent, so running it on an already-migrated save changes nothing.
                campaignPlayer?.let { LeaderMigration.migrate(it, file) }
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
    hex.owner = hexData.owner as? Int ?: -1
    hex.flag = hexData.flag as? Int ?: -1
    hex.isDeployment = hexData.isDeployment as? Int ?: -1
    hex.victorySide = hexData.victorySide as? Int ?: -1
    hex.name = hexData.name as? String ?: ""
    applyHexUnitIfPresent(hexData.unit, hex, map)
    applyHexUnitIfPresent(hexData.airunit, hex, map)
    return hex
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
