package org.osada

import kotlinx.browser.window
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.Leaders
import org.osada.model.UnitDescriptions
import org.osada.rules.GameRules
import org.w3c.dom.events.Event

fun main() {
    console.log("[osada] bootstrap loaded")
    window.addEventListener("load", { _: Event ->
        console.log("[osada] window.load event")
        I18n.initialize {
            val game = Game()
            val gameRules = GameRules
            val equipment = Equipment
            val leaders = Leaders
            val settings = uiSettings
            val eventHandler = EventHandler
            val combatLog = CombatLog
            js("window.game = game")
            console.log("[osada] window.game set", game)
            js("window.GameRules = gameRules")
            js("window.Equipment = equipment")
            js("window.Leaders = leaders")
            js("window.uiSettings = settings")
            js("window.EventHandler = eventHandler")
            js("window.CombatLog = combatLog")
            UnitDescriptions.load()
            console.log("[osada] calling game.init()")
            game.init()
            console.log("[osada] game.init() returned")
        }
    })
}
