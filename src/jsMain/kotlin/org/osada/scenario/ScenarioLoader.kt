package org.osada.scenario

import org.osada.GameHolder
import org.osada.difficultyModifiers
import org.osada.model.Equipment
import org.osada.model.allocMap
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.parsing.DOMParser
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Date

/**
 * Fetches and parses a scenario's XML file: map dimensions/turns/atmosphere, then (once the
 * terrain image loads, so map row/col counts can be corrected from it) players, reinforcements
 * and hexes. Player parsing lives in [ScenarioPlayerParser], reinforcements in
 * [ScenarioReinforcementParser], hexes in [ScenarioHexParser], and the shared `<unit>` element
 * parser in [ScenarioUnitParser].
 */
object ScenarioLoader {
    const val SCENARIO_PATH = "resources/scenarios/data/"
    var noCache: Boolean = true

    private const val HEX_COLUMN_WIDTH = 30
    private const val HEX_COLUMN_WIDTH_PAIR = 60

    private val loadedScenarios: MutableMap<String, Document> = mutableMapOf()

    fun loadScenario(scenario: Scenario) {
        val file = scenario.file ?: return onLoadError(scenario)
        val path = SCENARIO_PATH + file + if (noCache) "?_=" + Date().getTime() else ""
        val request = XMLHttpRequest()
        request.onload = { _: org.w3c.dom.events.Event ->
            if (request.readyState == 4.toShort() &&
                (request.status == 200.toShort() || request.status == 0.toShort())
            ) {
                val doc = DOMParser().parseFromString(request.responseText, "application/xml")
                loadedScenarios[file] = doc
                parseScenarioDocument(scenario, doc)
            } else {
                onLoadError(scenario)
            }
        }
        request.onerror = { _: org.w3c.dom.events.Event -> onLoadError(scenario) }
        request.open("GET", path, true)
        request.send(null)
    }

    private fun onLoadError(scenario: Scenario) {
        scenario.isLoaded = false
        scenario.onLoadFinished()
    }

    private fun parseScenarioDocument(
        scenario: Scenario,
        doc: Document,
    ) {
        val mapElement = doc.getElementsByTagName("map").item(0) as? Element
        if (mapElement == null) {
            onLoadError(scenario)
            return
        }
        parseMapTurnsAndDates(scenario, mapElement)
        parseMapAtmosphereAndDisplay(scenario, mapElement)
        parseMapName(scenario, mapElement)
        loadTerrainImage(scenario, doc)
    }

    private fun parseMapTurnsAndDates(
        scenario: Scenario,
        mapElement: Element,
    ) {
        val difficultyMultiplier =
            if (GameHolder.instance?.campaign != null) {
                difficultyModifiers[GameHolder.instance?.campaign?.difficulty ?: 0]?.extraTurns ?: 1.0
            } else {
                1.0
            }

        scenario.map.rows = mapElement.getAttribute("rows")?.toIntOrNull() ?: 0
        scenario.map.cols = mapElement.getAttribute("cols")?.toIntOrNull() ?: 0
        scenario.eqp = mapElement.getAttribute("eqp") ?: Equipment.DEFAULT_NAME
        Equipment.name = scenario.eqp
        val turns = mapElement.getAttribute("turns")?.split(", ")?.map { it.toIntOrNull() ?: 0 } ?: listOf(0, 0, 0)
        scenario.map.victoryTurns = turns.toMutableList()
        scenario.map.victoryTurns[2] = kotlin.math.round(turns[2] * difficultyMultiplier).toInt()
        scenario.map.maxTurns = scenario.map.victoryTurns[2]
        scenario.maxTurns = scenario.map.maxTurns
        scenario.date = Date(Date.parse(mapElement.getAttribute("date") ?: ""))
    }

    private fun parseMapAtmosphereAndDisplay(
        scenario: Scenario,
        mapElement: Element,
    ) {
        scenario.atmosferic = mapElement.getAttribute("atmosferic")?.toIntOrNull() ?: 0
        scenario.latitude = mapElement.getAttribute("latitude")?.toIntOrNull() ?: 0
        scenario.ground = mapElement.getAttribute("ground")?.toIntOrNull() ?: 0
        scenario.weatherCanChangeGround = (mapElement.getAttribute("weatherchg")?.toIntOrNull() ?: 0) != 0
        scenario.iconset = mapElement.getAttribute("iconset")?.toIntOrNull() ?: 0
        scenario.turnsPerDay = (mapElement.getAttribute("dayturns")?.toIntOrNull() ?: 1) * 2
        scenario.map.terrainImage = mapElement.getAttribute("image") ?: ""
    }

    /** The scenario/operation name lives on the <map name="…"> attribute. Standalone scenarios
     *  already got a friendly name from scenariolist.js (kept), but campaign scenarios have no
     *  scenariolist entry — without this their name is blank in the top bar. (Task 1: show the
     *  OPERATION name, not the campaign name.) */
    private fun parseMapName(
        scenario: Scenario,
        mapElement: Element,
    ) {
        val mapName = mapElement.getAttribute("name")
        if (!mapName.isNullOrBlank()) {
            scenario.map.name = mapName
            if (scenario.name.isBlank()) scenario.name = mapName
        }
    }

    private fun loadTerrainImage(
        scenario: Scenario,
        doc: Document,
    ) {
        val image: HTMLImageElement = org.w3c.dom.Image()
        image.src = scenario.map.terrainImage
        image.asDynamic().onload = {
            val imgHeight = image.height
            val imgWidth = image.width
            var cols = 1
            var width = imgWidth
            while (width >= HEX_COLUMN_WIDTH) {
                width = if (cols % 2 == 0) width - HEX_COLUMN_WIDTH_PAIR else width - HEX_COLUMN_WIDTH
                cols++
            }
            val isLastColPartial = width < 0
            val heightRatio = imgHeight / 50.0
            val rows = kotlin.math.floor(heightRatio).toInt()
            val isLastRowPartial = (heightRatio - rows) in 0.39..0.8
            scenario.map.cols = cols - 1
            scenario.map.rows =
                if (rows > 0 && (rows < scenario.map.rows || scenario.map.rows == 0)) rows else scenario.map.rows
            scenario.map.isLastColPartial = isLastColPartial
            scenario.map.isLastRowPartial = isLastRowPartial
            scenario.map.allocMap()
            ScenarioPlayerParser.parse(scenario, doc)
        }
        image.asDynamic().onerror = { onLoadError(scenario) }
    }

    fun getScenarioDataByFileName(file: String): Array<dynamic>? =
        js("scenariolist").unsafeCast<Array<Array<dynamic>>>().find {
            it[0] == file
        }
}
