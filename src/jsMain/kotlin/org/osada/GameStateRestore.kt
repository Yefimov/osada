package org.osada

import org.osada.model.Equipment
import org.osada.model.Hex
import org.osada.scenario.Campaign
import org.osada.scenario.Scenario
import kotlin.js.Date

/**
 * Rebuilds the live game graph from parsed save data and applies UI settings.
 *
 * Extracted from the former `GameState` god-class. Unlike [GameStateDeserializer] (which
 * only builds leaf objects), this collaborator is stateful: it owns a [Game] reference and
 * mutates it — loading equipment, recreating the scenario/map/players, restoring
 * reinforcements and (optionally) the campaign before invoking the ready callback.
 */
class GameStateRestore(private val game: Game) {

    fun restoreGame(scenarioData: dynamic, playersData: dynamic, campaignData: dynamic?, onReady: () -> Unit) {
        console.log("[osada] restoreGame start", scenarioData.file)
        game.cleanup()
        val newScenario = Scenario(scenarioData.file as String)
        Equipment.name = scenarioData.eqp as? String ?: Equipment.defaultName
        console.log("[osada] restoreGame equipment set to", Equipment.name)

        newScenario.name = scenarioData.name as? String ?: newScenario.name
        newScenario.setDescription(scenarioData.description as? String ?: "")
        newScenario.maxTurns = scenarioData.maxTurns as? Int ?: newScenario.maxTurns
        newScenario.date = Date((scenarioData.date as? Double) ?: Date().getTime())
        newScenario.dayTurn = scenarioData.dayTurn as? Int ?: 0
        newScenario.turnsPerDay = scenarioData.turnsPerDay as? Int ?: 1
        newScenario.atmosferic = scenarioData.atmosferic as? Int ?: 0
        newScenario.latitude = scenarioData.latitude as? Int ?: 0
        newScenario.ground = scenarioData.ground as? Int ?: 0
        newScenario.eqp = scenarioData.eqp as? String ?: Equipment.defaultName

        val rawPlayers = playersData.unsafeCast<Array<dynamic>>()
        val typedPlayers = rawPlayers.map { GameStateDeserializer.deserializePlayer(it) }.toTypedArray()
        Equipment.addPlayersEquipment(typedPlayers.toList()) {
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
            val currentPlayerId = (scenarioData.currentPlayerId as? Int)
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

            console.log("[osada] restoreGame setting game.scenario")
            game.scenario = newScenario
            if (campaignData != null) {
                val data = campaignData
                val campaignId = data.id as Int
                val file = data.file as String
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
                        onReady()
                    }
            } else {
                onReady()
            }
        }
    }

    private fun restoreMap(scenario: Scenario, mapData: dynamic) {
        val map = scenario.map
        map.rows = mapData.rows as? Int ?: 0
        map.cols = mapData.cols as? Int ?: 0
        map.terrainImage = mapData.terrainImage as? String ?: ""
        map.name = mapData.name as? String ?: ""
        map.maxTurns = mapData.maxTurns as? Int ?: 1
        map.allocMap()

        val hexRows = if (js("typeof mapData.hexes !== 'undefined'") as Boolean) mapData.hexes else mapData.map
        // Two passes, matching ScenarioLoader's contract with GameMap.setHex: the grid must hold
        // its FINAL hexes before any cell is registered, because setHex→addUnit computes spot/ZOC
        // ranges by flagging NEIGHBOURING grid hexes — in a single pass those flags landed on
        // blank alloc'd hexes that the very next iteration replaced, silently losing them.
        val grid = map.map ?: return
        for (r in 0 until map.rows) {
            val row = hexRows[r]
            for (c in 0 until map.cols) {
                try {
                    val hexData = row[c]
                    val hex = Hex(r, c)
                    hex.terrain = hexData.terrain as? Int ?: TerrainType.CLEAR.value
                    hex.road = hexData.road as? Int ?: RoadType.NONE.value
                    hex.rail = hexData.rail as? Int ?: RoadType.NONE.value
                    hex.owner = hexData.owner as? Int ?: -1
                    hex.flag = hexData.flag as? Int ?: -1
                    hex.isDeployment = hexData.isDeployment as? Int ?: -1
                    hex.victorySide = hexData.victorySide as? Int ?: -1
                    hex.name = hexData.name as? String ?: ""
                    val unitData = hexData.unit
                    if (unitData != null) {
                        val unit = GameStateDeserializer.deserializeUnit(unitData)
                        unit.player = map.getPlayer(unit.owner)
                        hex.setUnit(unit)
                    }
                    val airunitData = hexData.airunit
                    if (airunitData != null) {
                        val unit = GameStateDeserializer.deserializeUnit(airunitData)
                        unit.player = map.getPlayer(unit.owner)
                        hex.setUnit(unit)
                    }
                    grid[r][c] = hex
                } catch (e: Throwable) {
                    console.error("[osada] restoreMap error at cell", r, c, "message:", e.message, e)
                    throw e
                }
            }
        }
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                map.setHex(r, c)
            }
        }
    }

    private fun restoreReinforcements(scenario: Scenario, data: dynamic) {
        if (data == null) return
        if (js("Array.isArray(data)") as Boolean) {
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
            return
        }
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

    fun applySettings(data: dynamic) {
        console.log("[osada] applySettings start")
        if (data == null) {
            console.log("[osada] applySettings: data is null, skipping")
            return
        }
        console.log("[osada] applySettings data keys:", js("Object.keys(data)"))
        uiSettings.airMode = data.airMode as? Boolean ?: false
        uiSettings.strategicZoom = data.strategicZoom as? Boolean ?: false
        uiSettings.strategicZoomLevel = (data.strategicZoomLevel as? Number)?.toDouble() ?: 1.0
        uiSettings.mapZoom = data.mapZoom as? Boolean ?: false
        uiSettings.zoomLevel = (data.zoomLevel as? Number)?.toDouble() ?: 1.0
        uiSettings.uiScale = (data.uiScale as? Number)?.toDouble() ?: 1.0
        uiSettings.uiSize = (data.uiSize as? Number)?.toInt() ?: 840
        uiSettings.uiSmallSize = (data.uiSmallSize as? Number)?.toInt() ?: 470
        uiSettings.hexGrid = data.hexGrid as? Boolean ?: false
        uiSettings.showGridTerrain = data.showGridTerrain as? Boolean ?: false
        uiSettings.muteUnitSounds = data.muteUnitSounds as? Boolean ?: false
        uiSettings.soundVolume = (data.soundVolume as? Number)?.toDouble() ?: 0.5
        uiSettings.ambientVolume = (data.ambientVolume as? Number)?.toDouble() ?: 0.4
        uiSettings.deployMode = data.deployMode as? Boolean ?: false
        uiSettings.markCombatUnits = data.markCombatUnits as? Boolean ?: true
        uiSettings.markOwnUnits = data.markOwnUnits as? Boolean ?: false
        uiSettings.markEnemyUnits = data.markEnemyUnits as? Boolean ?: false
        uiSettings.markFOW = data.markFOW as? Boolean ?: false
        uiSettings.noFOW = data.noFOW as? Boolean ?: false
        uiSettings.quickAnimation = data.quickAnimation as? Boolean ?: false
        uiSettings.hasTouch = data.hasTouch as? Boolean ?: false
        uiSettings.use3D = data.use3D as? Boolean ?: false
        uiSettings.useRetina = data.useRetina as? Boolean ?: false
        uiSettings.allowZoom = data.allowZoom as? Boolean ?: false
        uiSettings.shownEndTurnTip = data.shownEndTurnTip as? Boolean ?: false
        // Always restore as pinned-on: older builds cleared this flag as a deselect side effect
        // and persisted it, which made the unit card permanently invisible on restored saves.
        // The flag is a session-level Inspect toggle now, not a durable preference.
        uiSettings.unitInfoVisibility = true
        uiSettings.showInfoToolTips = data.showInfoToolTips as? Boolean ?: true
        uiSettings.showDetailInfoToolTips = data.showDetailInfoToolTips as? Boolean ?: false
        // Fallback FALSE, matching UiSettings' own field default: `?: true` meant any settings
        // blob missing this key (older saves) silently re-enabled observer mode on every load.
        uiSettings.showHiddenVictoryHexes = data.showHiddenVictoryHexes as? Boolean ?: false
        uiSettings.confirmEndTurn = data.confirmEndTurn as? Boolean ?: true
        val isAI = data.isAI
        if (isAI != null) {
            val isAILength = isAI.length as? Int ?: 0
            console.log("[osada] applySettings isAI type:", js("typeof isAI"), "length:", isAILength)
            for (i in 0 until minOf(isAILength, uiSettings.isAI.size)) {
                uiSettings.isAI[i] = isAI[i] as? Int ?: 0
            }
        } else {
            console.log("[osada] applySettings isAI is null")
        }
        console.log("[osada] applySettings done")
    }
}
