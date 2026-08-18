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
                // Its own brass "caution" plate: it sits directly under the red CONTINUE plate and
                // throws away the whole battle, so it must not read as one more neutral grey entry
                // next to New Campaign / Load Game (2026-08-16 user request).
                Triple("restartmission", "menu.main.restart_mission", "caution"),
                Triple("newcampaign", "menu.main.new_campaign", ""),
                Triple("newscenario", "menu.main.single_scenario", ""),
                Triple("multiplayer", "menu.main.multiplayer", ""),
                Triple("saveload", "menu.main.load_game", ""),
                Triple("settings", "menu.main.settings", ""),
                Triple("tutorial", "menu.main.tutorial", "muted"),
            )
        val menuIcons =
            mapOf(
                "continuegame" to "star",
                "restartmission" to "supply",
                "newcampaign" to "map",
                "newscenario" to "attack",
                "multiplayer" to "map",
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
                    "caution" -> " osada-menu-btn--caution"
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

        buildHeroDeskButton()

        byId("smLogoText")?.textContent = I18n.t("menu.main.tagline")
        // Display version only — decoupled from the engine VERSION constant, which is baked
        // into the localStorage save keys and must not change (it would orphan existing saves).
        byId("smCredits")?.innerHTML = "v0.5"
    }

    private fun buildLanguageSwitch() {
        LanguageSelector.buildMainMenuControl()
    }

    /**
     * Hero Desk (`docs/design/hero-desk-and-profile-archive.md` §6): the cross-campaign commander
     * archive, and the surface that replaced the old Hall of Fame button — the Hall of Fame is now
     * one of the desk's filters, not a separate summary-only collection.
     *
     * **Always present**, unlike the button it replaces, which appeared only once legends existed.
     * A player with no archived career needs to be told the desk exists and is empty; a menu entry
     * that materializes later cannot be found by anyone looking for it. Wired directly (not through
     * the startMenuButton action router) since it opens its own overlay.
     */
    private fun buildHeroDeskButton() {
        val desk = addTag("smButtons", "div")
        desk.id = "heroDesk"
        desk.className = "smMainButton osada-menu-btn osada-menu-btn--muted"
        desk.title = I18n.t("menu.main.hero_desk.help")
        val ico = addTag(desk, "span")
        ico.className = "osada-menu-btn__ico osada-ico osada-ico--star"
        val text = addTag(desk, "span")
        text.className = "osada-menu-btn__text"
        val label = addTag(text, "span")
        label.className = "osada-menu-btn__label"
        label.textContent = I18n.t("menu.main.hero_desk.label")
        val sub = addTag(text, "span")
        sub.className = "osada-menu-btn__sub"
        sub.textContent = I18n.t("menu.main.hero_desk.subtitle")
        desk.asButton(ariaLabel = I18n.t("menu.main.hero_desk.label")) { HeroDeskPresenter.open() }
    }
}
