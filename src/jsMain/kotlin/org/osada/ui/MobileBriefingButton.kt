package org.osada.ui

import org.osada.GameHolder
import org.osada.i18n.I18n

/** Compact phone entry point for the current operation's briefing. The scenario title itself is
 * omitted from the phone bar because truncating it to one letter communicates nothing; the full
 * title and orders are one tap away here instead. Desktop keeps the existing Turn Report briefing
 * action and hides this duplicate in CSS. */
internal object MobileBriefingButton {
    fun install(statusbar: org.w3c.dom.HTMLElement) {
        val button = addTag(statusbar, "div")
        button.id = "osadaBriefingBtn"
        button.className = "osada-tb-briefing"
        button.title = I18n.t("turn_report.briefing.help")
        val glyph = addTag(button, "span")
        glyph.className = "osada-tb-briefing__glyph"
        glyph.setAttribute("aria-hidden", "true")
        glyph.textContent = "▾"
        button.asButton(I18n.t("turn_report.briefing.help")) {
            val game = GameHolder.instance
            val reopened = game?.ui?.reopenScenarioBriefing() ?: false
            if (!reopened && game != null) {
                UIBuilder.message(
                    game.scenario?.name ?: "",
                    game.scenario?.getDescription() ?: "",
                    narrative = true,
                )
            }
        }
    }
}
