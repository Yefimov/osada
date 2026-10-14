package org.osada.ui

/**
 * The in-battle pause menu (`#startmenu` opened over a battle, and the Settings screen reached from
 * it) as one state: while it is up the battle is paused and nothing of the battle may open above it.
 *
 * Before this, the menu paused nothing. `Game.processTurn` kept driving the AI underneath it, so the
 * AI's modal messages, the end-of-battle message, the defeat question and the next campaign mission
 * (with its briefing) could all open over the menu (found in review, 2026-10-14). The queued popups
 * were already held ([MessageDialogs.suspendDynamicMessages]); the promotion dialog was not.
 *
 * [opened] and [closed] are called where the menu is shown over a battle and on each way back to it.
 * [isPausing] reads the DOM itself rather than a flag, so a missed [closed] can never freeze a battle.
 */
internal object PauseMenu {
    fun opened() {
        MessageDialogs.suspendDynamicMessages()
        HeroPromotionPresenter.holdForMenu()
    }

    fun closed() {
        MessageDialogs.resumeDynamicMessages()
        HeroPromotionPresenter.resumeAfterMenu()
    }

    /** Whether a menu surface covers the battle. Read by `Game.processTurn`. */
    fun isPausing(): Boolean = isVisible("startmenu") || isVisible("smSettings")
}
