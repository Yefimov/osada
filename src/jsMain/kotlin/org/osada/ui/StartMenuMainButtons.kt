package org.osada.ui

import org.osada.i18n.I18n

/**
 * [StartMenuBuilder]'s main-menu button column (Continue/New Campaign/Single Scenario/Load
 * Game/Settings/Tutorial). Split out purely to keep [StartMenuBuilder] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object StartMenuMainButtons {
    fun buildMainButtons() {
        buildLanguageSwitch()
        // OSADA main column: fixed order, condensed uppercase labels, routed to the existing
        // startMenuButton actions. Label and subtitle fields are stable localization IDs.
        val mainButtons =
            listOf(
                Triple("continuegame", "menu.main.continue", "primary"),
                Triple("newcampaign", "menu.main.new_campaign", ""),
                Triple("newscenario", "menu.main.single_scenario", ""),
                Triple("saveload", "menu.main.load_game", ""),
                Triple("settings", "menu.main.settings", ""),
                Triple("tutorial", "menu.main.tutorial", "muted"),
            )
        val menuIcons =
            mapOf(
                "continuegame" to "star",
                "newcampaign" to "map",
                "newscenario" to "attack",
                "saveload" to "supply",
                "settings" to "settings",
                "tutorial" to "info",
            )
        mainButtons.forEach { (id, keyPrefix, variant) ->
            val title = I18n.t("$keyPrefix.label")
            val subtitle = I18n.t("$keyPrefix.subtitle")
            val button = addTag("smButtons", "div")
            button.id = id
            button.title = subtitle
            button.className = "smMainButton osada-menu-btn" +
                when (variant) {
                    "primary" -> " osada-menu-btn--primary"
                    "muted" -> " osada-menu-btn--muted"
                    else -> ""
                }
            val ico = addTag(button, "span")
            ico.className = "osada-menu-btn__ico osada-ico osada-ico--${menuIcons[id] ?: "star"}"
            val text = addTag(button, "span")
            text.className = "osada-menu-btn__text"
            val label = addTag(text, "span")
            label.className = "osada-menu-btn__label"
            label.textContent = title
            val sub = addTag(text, "span")
            sub.className = "osada-menu-btn__sub"
            sub.textContent = subtitle
            button.onclick = { _: org.w3c.dom.events.MouseEvent ->
                gameRef()?.ui?.startMenuButton(id)
            }
        }

        buildHallOfFameButton()

        byId("smLogoText")?.textContent = I18n.t("menu.main.tagline")
        // Display version only — decoupled from the engine VERSION constant, which is baked
        // into the localStorage save keys and must not change (it would orphan existing saves).
        byId("smCredits")?.innerHTML = "v0.5"
    }

    private fun buildLanguageSwitch() {
        LanguageSelector.buildMainMenuControl()
    }

    // Hall of Fame (§14.6): a cross-campaign collection, shown only once legends exist. Wired
    // directly (not through the startMenuButton action router) since it opens its own overlay.
    private fun buildHallOfFameButton() {
        if (!HallOfFame.isNotEmpty()) return
        val hof = addTag("smButtons", "div")
        hof.id = "hallOfFame"
        hof.className = "smMainButton osada-menu-btn osada-menu-btn--muted"
        hof.title = I18n.t("menu.main.hall_of_fame.help")
        val ico = addTag(hof, "span")
        ico.className = "osada-menu-btn__ico osada-ico osada-ico--star"
        val text = addTag(hof, "span")
        text.className = "osada-menu-btn__text"
        val label = addTag(text, "span")
        label.className = "osada-menu-btn__label"
        label.textContent = I18n.t("menu.main.hall_of_fame.label")
        val sub = addTag(text, "span")
        sub.className = "osada-menu-btn__sub"
        sub.textContent = I18n.t("menu.main.hall_of_fame.subtitle")
        hof.onclick = { _: org.w3c.dom.events.MouseEvent -> HallOfFamePresenter.open() }
    }
}
