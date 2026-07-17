package org.osada.scenario

import org.osada.GameHolder
import org.osada.difficultyModifiers
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.rules.GameRules
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.parsing.DOMParser
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Date

object ScenarioLoader {
    const val scenarioPath = "resources/scenarios/data/"
    var noCache: Boolean = true

    private val loadedScenarios: MutableMap<String, Document> = mutableMapOf()

    fun loadScenario(scenario: Scenario) {
        val file = scenario.file ?: return onLoadError(scenario)
        val path = scenarioPath + file + if (noCache) "?_=" + Date().getTime() else ""
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

    private fun parseScenarioDocument(scenario: Scenario, doc: Document) {
        val mapElement = doc.getElementsByTagName("map").item(0) as? Element
        if (mapElement == null) {
            onLoadError(scenario)
            return
        }
        val difficultyMultiplier = if (GameHolder.instance?.campaign != null) {
            difficultyModifiers[GameHolder.instance?.campaign?.difficulty ?: 0]?.extraTurns ?: 1.0
        } else {
            1.0
        }

        scenario.map.rows = mapElement.getAttribute("rows")?.toIntOrNull() ?: 0
        scenario.map.cols = mapElement.getAttribute("cols")?.toIntOrNull() ?: 0
        scenario.eqp = mapElement.getAttribute("eqp") ?: Equipment.defaultName
        Equipment.name = scenario.eqp
        val turns = mapElement.getAttribute("turns")?.split(", ")?.map { it.toIntOrNull() ?: 0 } ?: listOf(0, 0, 0)
        scenario.map.victoryTurns = turns.toMutableList()
        scenario.map.victoryTurns[2] = kotlin.math.round(turns[2] * difficultyMultiplier).toInt()
        scenario.map.maxTurns = scenario.map.victoryTurns[2]
        scenario.maxTurns = scenario.map.maxTurns
        scenario.date = Date(Date.parse(mapElement.getAttribute("date") ?: ""))
        scenario.atmosferic = mapElement.getAttribute("atmosferic")?.toIntOrNull() ?: 0
        scenario.latitude = mapElement.getAttribute("latitude")?.toIntOrNull() ?: 0
        scenario.ground = mapElement.getAttribute("ground")?.toIntOrNull() ?: 0
        scenario.weatherCanChangeGround = (mapElement.getAttribute("weatherchg")?.toIntOrNull() ?: 0) != 0
        scenario.iconset = mapElement.getAttribute("iconset")?.toIntOrNull() ?: 0
        scenario.turnsPerDay = (mapElement.getAttribute("dayturns")?.toIntOrNull() ?: 1) * 2
        scenario.map.terrainImage = mapElement.getAttribute("image") ?: ""

        // The scenario/operation name lives on the <map name="…"> attribute. Standalone scenarios
        // already got a friendly name from scenariolist.js (kept), but campaign scenarios have no
        // scenariolist entry — without this their name is blank in the top bar. (Task 1: show the
        // OPERATION name, not the campaign name.)
        val mapName = mapElement.getAttribute("name")
        if (!mapName.isNullOrBlank()) {
            scenario.map.name = mapName
            if (scenario.name.isBlank()) scenario.name = mapName
        }

        loadTerrainImage(scenario, doc)
    }

    private fun loadTerrainImage(scenario: Scenario, doc: Document) {
        val image: HTMLImageElement = org.w3c.dom.Image()
        image.src = scenario.map.terrainImage
        image.asDynamic().onload = {
            val imgHeight = image.height
            val imgWidth = image.width
            var cols = 1
            var width = imgWidth
            while (width >= 30) {
                width = if (cols % 2 == 0) width - 60 else width - 30
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
            parsePlayersAndEquipment(scenario, doc)
        }
        image.asDynamic().onerror = { onLoadError(scenario) }
    }

    private fun parsePlayersAndEquipment(scenario: Scenario, doc: Document) {
        val playerElements = doc.getElementsByTagName("player")
        val players = mutableListOf<Player>()
        val minTurnPrestige = if (GameHolder.instance?.campaign != null) {
            kotlin.math.round(
                GameHolder.instance!!.campaign!!.startprestige *
                    (difficultyModifiers[GameHolder.instance!!.campaign!!.difficulty]?.turnPrestige ?: 0.0),
            ).toInt()
        } else {
            0
        }

        for (i in 0 until playerElements.length) {
            val el = playerElements.item(i) as? Element ?: continue
            val player = Player()
            player.id = el.getAttribute("id")?.toIntOrNull() ?: 0
            player.side = el.getAttribute("side")?.toIntOrNull() ?: 0
            player.country = el.getAttribute("country")?.toIntOrNull() ?: 0
            player.airTransports = el.getAttribute("airtrans")?.toIntOrNull() ?: 0
            player.navalTransports = el.getAttribute("navaltrans")?.toIntOrNull() ?: 0
            player.prestigePerTurn = el.getAttribute("turnprestige")?.split(", ")?.map { value ->
                val v = value.toIntOrNull() ?: 0
                if (v < minTurnPrestige) minTurnPrestige else v
            }?.toMutableList() ?: mutableListOf()
            player.prestige = player.prestigePerTurn.getOrElse(0) { 0 }
            player.supportCountries =
                el.getAttribute("support")?.split(", ")?.map {
                    it.toIntOrNull() ?: 0
                }?.filter { it > 0 }?.toMutableList()
                    ?: mutableListOf()
            players.add(player)
        }

        Equipment.addPlayersEquipment(players) {
            players.forEach { scenario.map.addPlayer(it) }
            parseReinforcements(scenario, doc)
            parseHexes(scenario, doc)
            scenario.isLoaded = true
            scenario.onLoadFinished()
        }
    }

    private fun parseReinforcements(scenario: Scenario, doc: Document) {
        val reinforceElements = doc.getElementsByTagName("reinforce")
        for (i in 0 until reinforceElements.length) {
            val el = reinforceElements.item(i) as? Element ?: continue
            val turn = el.getAttribute("turn")?.toIntOrNull() ?: continue
            for (j in 0 until el.childNodes.length) {
                val atNode = el.childNodes.item(j) as? Element ?: continue
                if (atNode.nodeName != "at") continue
                val row = atNode.getAttribute("row")?.toIntOrNull() ?: continue
                val col = atNode.getAttribute("col")?.toIntOrNull() ?: continue
                for (k in 0 until atNode.childNodes.length) {
                    val unitNode = atNode.childNodes.item(k) as? Element ?: continue
                    if (unitNode.nodeName != "unit") continue
                    val unit = parseUnit(unitNode, scenario)
                    if (unit != null) {
                        scenario.addReinforcement(turn, row, col, unit)
                    }
                }
            }
        }
    }

    private fun parseHexes(scenario: Scenario, doc: Document) {
        val hexElements = doc.getElementsByTagName("hex")
        for (i in 0 until hexElements.length) {
            val el = hexElements.item(i) as? Element ?: continue
            val row = el.getAttribute("row")?.toIntOrNull() ?: continue
            val col = el.getAttribute("col")?.toIntOrNull() ?: continue
            if (row >= scenario.map.rows || col >= scenario.map.cols) continue
            val hex = scenario.map.map!![row][col]
            el.getAttribute("terrain")?.toIntOrNull()?.let { hex.terrain = it }
            el.getAttribute("road")?.toIntOrNull()?.let { hex.road = it }
            el.getAttribute("rail")?.toIntOrNull()?.let { hex.rail = it }
            el.getAttribute("name")?.let { hex.name = it }
            el.getAttribute("flag")?.toIntOrNull()?.let { hex.flag = it }
            el.getAttribute("owner")?.toIntOrNull()?.let { hex.owner = it }
            el.getAttribute("victory")?.toIntOrNull()?.let { hex.victorySide = it }
            el.getAttribute("deploy")?.toIntOrNull()?.let { hex.isDeployment = it }
            el.getAttribute("supply")?.toIntOrNull()?.let { hex.isDeployment = it }

            for (j in 0 until el.childNodes.length) {
                val unitNode = el.childNodes.item(j) as? Element ?: continue
                if (unitNode.nodeName != "unit") continue
                val unit = parseUnit(unitNode, scenario)
                if (unit != null) hex.setUnit(unit)
            }
            scenario.map.setHex(row, col)
        }
    }

    private fun parseUnit(el: Element, scenario: Scenario): GameUnit? {
        val eqid = el.getAttribute("id")?.toIntOrNull() ?: return null
        val owner = el.getAttribute("owner")?.toIntOrNull() ?: return null
        if (eqid < 0 || owner < 0) return null
        val unit = GameUnit(eqid)
        unit.owner = owner
        el.getAttribute("face")?.toIntOrNull()?.let { unit.facing = it }
        el.getAttribute("flag")?.toIntOrNull()?.let { unit.flag = it }
        el.getAttribute("transport")?.toIntOrNull()?.let { unit.setTransport(it) }
        el.getAttribute("carrier")?.toIntOrNull()?.let { unit.carrier = it }
        el.getAttribute("exp")?.toIntOrNull()?.let { unit.experience = it }
        el.getAttribute("ent")?.toIntOrNull()?.let { unit.entrenchment = it }
        el.getAttribute("str")?.toIntOrNull()?.let { unit.strength = it }
        if (el.hasAttribute("ldr")) {
            unit.leader = Leaders.generateLeader(unit)
        }
        val unitSide = scenario.map.getPlayer(owner).side
        if (!GameRules.isSea(unit)) {
            scenario.unitsCostPerSide[unitSide] += GameRules.calculateUnitCosts(unit.eqid, unit.transport?.eqid ?: -1)
            scenario.expPerSide[unitSide].exp = (scenario.expPerSide[unitSide].exp as Int) + unit.experience
            scenario.expPerSide[unitSide].count = (scenario.expPerSide[unitSide].count as Int) + 1
        }
        return unit
    }

    fun getScenarioDataByFileName(file: String): Array<dynamic>? =
        js("scenariolist").unsafeCast<Array<Array<dynamic>>>().find {
            it[0] == file
        }
}
