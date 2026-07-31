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
            val globals = window.asDynamic()
            globals.game = game
            console.log("[osada] window.game set", game)
            globals.GameRules = GameRules
            globals.Equipment = Equipment
            globals.Leaders = Leaders
            globals.uiSettings = uiSettings
            globals.EventHandler = EventHandler
            globals.CombatLog = CombatLog
            UnitDescriptions.load()
            console.log("[osada] calling game.init()")
            game.init()
            console.log("[osada] game.init() returned")
        }
    })
}
