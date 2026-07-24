package org.osada.ui

/**
 * [StartMenuBuilder]'s main-menu button column (Continue/New Campaign/Single Scenario/Load
 * Game/Settings/Tutorial). Split out purely to keep [StartMenuBuilder] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object StartMenuMainButtons {
    fun buildMainButtons() {
        // OSADA main column: fixed order, condensed uppercase labels, routed to the existing
        // startMenuButton actions. The third field is a visual variant, not a new action.
        // "New Game" is intentionally dropped as a button (it only opened a sub-panel that
        // duplicated Campaigns/Scenarios); its handler in StartMenuButtonHandler is left untouched.
        val mainButtons =
            listOf(
                Triple("continuegame", "Continue", "primary"),
                Triple("newcampaign", "New Campaign", ""),
                Triple("newscenario", "Single Scenario", ""),
                Triple("saveload", "Load Game", ""),
                Triple("settings", "Settings", ""),
                Triple("tutorial", "Tutorial", "muted"),
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
        val menuSubs =
            mapOf(
                "continuegame" to "Resume your campaign",
                "newcampaign" to "Lead a nation through the war",
                "newscenario" to "Fight a standalone battle",
                "saveload" to "Restore a saved battle",
                "settings" to "Options & display",
                "tutorial" to "Learn the basics",
            )
        mainButtons.forEach { (id, title, variant) ->
            val button = addTag("smButtons", "div")
            button.id = id
            button.title = title
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
            sub.textContent = menuSubs[id] ?: ""
            button.onclick = { _: org.w3c.dom.events.MouseEvent ->
                gameRef()?.ui?.startMenuButton(id)
            }
        }

        buildHallOfFameButton()

        byId("smLogoText")?.innerHTML = "Turn-based strategy of great battles"
        // Display version only — decoupled from the engine VERSION constant, which is baked
        // into the localStorage save keys and must not change (it would orphan existing saves).
        byId("smCredits")?.innerHTML = "v0.5"
    }

    // Hall of Fame (§14.6): a cross-campaign collection, shown only once legends exist. Wired
    // directly (not through the startMenuButton action router) since it opens its own overlay.
    private fun buildHallOfFameButton() {
        if (!HallOfFame.isNotEmpty()) return
        val hof = addTag("smButtons", "div")
        hof.id = "hallOfFame"
        hof.className = "smMainButton osada-menu-btn osada-menu-btn--muted"
        val ico = addTag(hof, "span")
        ico.className = "osada-menu-btn__ico osada-ico osada-ico--star"
        val text = addTag(hof, "span")
        text.className = "osada-menu-btn__text"
        val label = addTag(text, "span")
        label.className = "osada-menu-btn__label"
        label.textContent = "Hall of Fame"
        val sub = addTag(text, "span")
        sub.className = "osada-menu-btn__sub"
        sub.textContent = "Legends across your campaigns"
        hof.onclick = { _: org.w3c.dom.events.MouseEvent -> HallOfFamePresenter.open() }
    }
}
