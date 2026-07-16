package org.osada

import kotlinx.browser.window
import org.osada.model.Equipment
import org.osada.model.Leaders
import org.osada.model.UnitDescriptions
import org.osada.rules.GameRules
import org.w3c.dom.events.Event

fun main() {
    console.log("[OpenPanzer] bootstrap loaded")
    window.addEventListener("load", { _: Event ->
        console.log("[OpenPanzer] window.load event")
        val game = Game()
        val gameRules = GameRules
        val equipment = Equipment
        val leaders = Leaders
        val settings = uiSettings
        val eventHandler = EventHandler
        val combatLog = CombatLog
        js("window.game = game")
        console.log("[OpenPanzer] window.game set", game)
        js("window.GameRules = gameRules")
        js("window.Equipment = equipment")
        js("window.Leaders = leaders")
        js("window.uiSettings = settings")
        js("window.EventHandler = eventHandler")
        js("window.CombatLog = combatLog")
        UnitDescriptions.load()
        console.log("[OpenPanzer] calling game.init()")
        game.init()
        console.log("[OpenPanzer] game.init() returned")
    })
}