package org.osada.scenario

import org.osada.model.Player
import org.osada.model.getPlayers
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.parsing.DOMParser
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Date

/**
 * Recovers a scenario's authored options for a save written before they were serialized.
 *
 * `GameStateRestore` rebuilds the battle from the save alone and never re-reads the scenario XML,
 * which is why the options had to start being written into the save at all. Saves already on
 * players' machines carry none of them, and for those the XML is still the author's own record —
 * so a save with no `options` block is completed from it rather than left with 27 nulls.
 *
 * **This runs only when the save is silent.** A save that carries the block is authoritative and no
 * request is made; a save that carries a per-player limit keeps it, because
 * [ScenarioPlayerParser.applyAuthoredPurchaseLimits] fills only what is still unauthored. Nothing
 * here can therefore change how a modern save loads.
 *
 * A failed or missing fetch is not an error: the options stay `null`, which is exactly the state
 * the save loaded in before this existed, and [onDone] still runs so the load never stalls on it.
 */
internal object AuthoredOptionsBackfill {
    /**
     * Completes [scenario] from its XML when the save's scenario block carries no `options` key,
     * then calls [onDone]. A save that carries the key needs nothing and [onDone] runs at once, on
     * the same tick.
     *
     * An EMPTY options object is not the absent case: it is a scenario whose source this install
     * cannot read, which authored nothing and must stay unauthored.
     */
    fun completeIfAbsent(
        scenario: Scenario,
        scenarioData: dynamic,
        onDone: () -> Unit,
    ) {
        val options = scenarioData?.options
        if (options != null && options != undefined) {
            onDone()
            return
        }
        backfill(scenario, onDone)
    }

    private fun backfill(
        scenario: Scenario,
        onDone: () -> Unit,
    ) {
        val file = scenario.file?.takeIf { it.isNotBlank() }
        if (file == null) {
            onDone()
            return
        }
        val cached = ScenarioLoader.cachedDocument(file)
        if (cached != null) {
            applyDocument(scenario, cached)
            onDone()
            return
        }
        fetchDocument(file) { doc ->
            doc?.let { applyDocument(scenario, it) }
            onDone()
        }
    }

    /**
     * The `<map>` switches, then the `<player>` purchase limits, exactly as a fresh load reads them.
     *
     * Internal rather than private so the whole XML-to-scenario step is testable without a network
     * round trip — the fetch above adds nothing to it but the document.
     */
    internal fun applyDocument(
        scenario: Scenario,
        doc: Document,
    ) {
        val mapElement = doc.getElementsByTagName("map").item(0)
        if (mapElement == null) {
            console.log("[osada] authored options back-fill: no <map> element in", scenario.file)
            return
        }
        AuthoredScenarioOptions.parse(scenario, mapElement)
        applyPlayerLimits(scenario, doc)
        console.log("[osada] authored options back-filled from", scenario.file)
    }

    /**
     * The per-player half: the purchase cap, the `.buy4` whitelist and the Fronts/Factions slots,
     * matched to the restored players by the same `id` the XML and the save both use.
     *
     * **The transport POOL COUNTS are deliberately not touched** — they are live game state that
     * the save has always carried and that a battle spends down, so re-reading them from the XML
     * would refill a player's transports on every reload. Only `transportPoolsAuthored`, which is
     * the scenario's PRESENCE flag and not a count, is recovered.
     */
    private fun applyPlayerLimits(
        scenario: Scenario,
        doc: Document,
    ) {
        val elements = doc.getElementsByTagName("player")
        for (i in 0 until elements.length) {
            val el = elements.item(i) as? Element ?: continue
            // Matched by id, not by position: `GameMap.getPlayer` is an INDEX lookup that falls
            // back to player 0, and silently handing one player's authored list to another would
            // be worse than not back-filling at all.
            val player =
                el
                    .getAttribute("id")
                    ?.toIntOrNull()
                    ?.let { id -> scenario.map.getPlayers().firstOrNull { it.id == id } }
            if (player != null) applyAuthoredLimits(el, player)
        }
    }

    private fun applyAuthoredLimits(
        el: Element,
        player: Player,
    ) {
        ScenarioPlayerParser.applyAuthoredPurchaseLimits(el, player)
        if (!player.transportPoolsAuthored) {
            player.transportPoolsAuthored =
                el.hasAttribute("airtrans") ||
                el.hasAttribute("navaltrans") ||
                el.hasAttribute("railtrans")
        }
    }

    /** [ScenarioLoader.loadScenario]'s own request, without the parse that rebuilds the battle. */
    private fun fetchDocument(
        file: String,
        onLoaded: (Document?) -> Unit,
    ) {
        val path =
            ScenarioLoader.SCENARIO_PATH + file +
                if (ScenarioLoader.noCache) "?_=" + Date().getTime() else ""
        val request = XMLHttpRequest()
        request.onload = { _: org.w3c.dom.events.Event ->
            val ok =
                request.readyState == 4.toShort() &&
                    (request.status == 200.toShort() || request.status == 0.toShort())
            onLoaded(
                if (ok) DOMParser().parseFromString(request.responseText, "application/xml") else null,
            )
        }
        request.onerror = { _: org.w3c.dom.events.Event -> onLoaded(null) }
        request.open("GET", path, true)
        request.send(null)
    }
}
