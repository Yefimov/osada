package org.osada.scenario

import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * OG's per-turn scenario messages (`.tmsg`), imported by `tools/og-import/add_turn_messages.py`:
 *
 * > *"You can define some text that can appear in the turn start window."*
 * > — `Manual_OSuite-Scenario.pdf` §3.1
 *
 * ```xml
 * <turnmessages>
 *   <msg turn="2">Some farmers from Schl&#228;chtenhaus hear what happened and join our fight!</msg>
 * </turnmessages>
 * ```
 *
 * 31 deployed scenarios author one (2026-09-25). The text is authored content, not battle state, so
 * it is NOT written into saves: a restored battle reads it back from the scenario XML
 * ([completeIfAbsent]), the same way `AuthoredOptionsBackfill` recovers the capture goals.
 */
internal object ScenarioTurnMessages {
    fun parse(
        scenario: Scenario,
        doc: Document,
    ) {
        val elements = doc.getElementsByTagName("msg")
        scenario.turnMessages =
            (0 until elements.length)
                .mapNotNull { elements.item(it) as? Element }
                .filter { it.parentElement?.tagName == "turnmessages" }
                .mapNotNull { el ->
                    val turn = el.getAttribute("turn")?.toIntOrNull()?.takeIf { it > 0 }
                    val text = el.textContent?.trim()?.takeIf { it.isNotEmpty() }
                    if (turn == null || text == null) null else turn to text
                }.toMap()
    }

    /** Reads the messages of a restored battle from its XML, then calls [onDone]. A failed fetch
     *  leaves the battle without messages, which is what it had before they were imported. */
    fun completeIfAbsent(
        scenario: Scenario,
        onDone: () -> Unit,
    ) {
        val file = scenario.file?.takeIf { it.isNotBlank() }
        if (scenario.turnMessages != null || file == null) {
            onDone()
            return
        }
        val cached = ScenarioLoader.cachedDocument(file)
        if (cached != null) {
            parse(scenario, cached)
            onDone()
            return
        }
        AuthoredOptionsBackfill.fetchDocument(file) { doc ->
            doc?.let { parse(scenario, it) }
            onDone()
        }
    }
}
