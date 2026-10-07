package org.osada.multiplayer.ui

import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.parsing.DOMParser
import org.w3c.xhr.XMLHttpRequest

/** Workload hints use only the chosen army's opening units, never enemies or future arrivals. */
internal object CoopScenarioAdvice {
    private const val MAX_CACHED = 32

    data class Opening(
        val deployed: Int,
        val reserve: Int,
    )

    private val cache = linkedMapOf<String, Opening>()

    fun append(
        card: HTMLElement,
        file: String?,
        playerId: Int,
        campaign: Boolean,
    ) {
        val box = MultiplayerScreen.panel("osada-coop-scenario-advice")
        box.id = "coopScenarioAdvice"
        card.appendChild(box)
        if (campaign || file == null) {
            box.appendChild(MultiplayerScreen.text(I18n.t("multiplayer.coop.advice.campaign")))
            return
        }
        val key = "$file:$playerId"
        val cached = cache[key]
        if (cached != null) {
            render(box, cached)
            return
        }
        box.textContent = I18n.t("multiplayer.coop.advice.loading")
        val request = XMLHttpRequest()
        request.onload = {
            val opening = if (request.status == 200.toShort()) count(request.responseText, playerId) else null
            if (opening != null) {
                cache[key] = opening
                if (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
                render(box, opening)
            } else {
                box.textContent = I18n.t("multiplayer.coop.advice.unavailable")
            }
        }
        request.onerror = { box.textContent = I18n.t("multiplayer.coop.advice.unavailable") }
        request.open("GET", "resources/scenarios/data/$file", true)
        request.send(null)
    }

    internal fun count(
        source: String,
        playerId: Int,
    ): Opening? {
        val doc = DOMParser().parseFromString(source, "application/xml")
        if (doc.querySelector("parsererror") != null || doc.documentElement?.tagName != "map") return null
        val own = "unit[owner=\"$playerId\"]"
        return Opening(
            doc.querySelectorAll("map > hex > $own").length,
            doc.querySelectorAll("map > reserve > $own").length,
        )
    }

    private fun render(
        box: HTMLElement,
        opening: Opening,
    ) {
        box.innerHTML = ""
        box.appendChild(
            MultiplayerScreen.text(
                I18n.t(
                    "multiplayer.coop.advice.opening",
                    mapOf(
                        "deployed" to opening.deployed,
                        "reserve" to opening.reserve,
                    ),
                ),
            ),
        )
    }
}
