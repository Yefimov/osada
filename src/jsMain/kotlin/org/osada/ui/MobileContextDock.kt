package org.osada.ui

import org.osada.i18n.I18n

/**
 * Quiet phone-only status rail. It occupies the bottom-zone slot while no unit card is open and
 * disappears as soon as [BottomZoneBuilder] enters a visible unit/forecast state.
 */
internal object MobileContextDock {
    fun install() {
        if (byId("osadaMobileContextDock") != null) return
        val bottomZone = byId("osada-bottomzone") ?: return
        val dock = addTag(bottomZone, "div")
        dock.id = "osadaMobileContextDock"
        dock.className = "osada-mobile-context"

        val turn = addTag(dock, "div")
        turn.id = "osadaMobileTurn"
        turn.className = "osada-mobile-context__turn"

        val weather = addTag(dock, "div")
        weather.id = "osadaMobileWeather"
        weather.className = "osada-mobile-context__weather"

        val location = addTag(dock, "div")
        location.id = "osadaMobileLocation"
        location.className = "osada-mobile-context__location"
        location.title = I18n.t("hud.status.location.help")

        // Second rail line: the scenario's in-game date and name. The phone top bar hides
        // `.osada-tb-op` because a title truncated to one letter is worse than no title, so this
        // is the only place the operation identifies itself during play.
        val scenario = addTag(dock, "div")
        scenario.id = "osadaMobileScenario"
        scenario.className = "osada-mobile-context__scenario"
        scenario.title = I18n.t("hud.status.scenario.help")

        val heroes = addTag(dock, "div")
        heroes.id = "osadaMobileHeroes"
        heroes.className = "osada-mobile-context__heroes osada-ico osada-ico--star"
        heroes.title = I18n.t("hud.headquarters.help")
        heroes.asButton(I18n.t("hud.headquarters.help")) { CommanderRosterPresenter.open() }
    }

    fun updateTurn(
        turn: Int,
        maxTurns: Int,
    ) {
        byId("osadaMobileTurn")?.textContent =
            "${I18n.t("hud.turn.label")} ${I18n.formatNumber(turn)}/${I18n.formatNumber(maxTurns)}"
    }

    fun updateWeather(
        html: String,
        label: String,
    ) {
        byId("osadaMobileWeather")?.apply {
            innerHTML = html
            title = label
            setAttribute("aria-label", label)
        }
    }

    fun updateLocation(html: String) {
        byId("osadaMobileLocation")?.innerHTML = html
    }

    /**
     * [dateText] is the already-localized in-game date and [name] the scenario title, both taken
     * from the same refresh that fills the desktop status line so the two can never disagree.
     */
    fun updateScenario(
        dateText: String,
        name: String,
    ) {
        val element = byId("osadaMobileScenario") ?: return
        clearTag(element)
        val date = addTag(element, "span")
        date.className = "osada-mobile-context__date"
        date.textContent = dateText
        addTag(element, "span").textContent = " · $name"
        element.title = "$dateText · $name"
        element.setAttribute("aria-label", "$dateText · $name")
    }
}
