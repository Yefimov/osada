package org.osada.ui

import org.osada.UnitClass
import org.osada.hero.CampaignHeroScope
import org.osada.i18n.GameText
import org.osada.i18n.I18n
import org.osada.model.GameUnit
import org.osada.rules.UnitCapabilities
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * The unit card's `{class} · {service}` chip (`#osadaUcClass`) and its explanation panel.
 *
 * The chip used to carry a one-line `title` that explained the chip itself ("unit class and
 * campaign-persistence status") rather than what it SAID. The panel now answers the two questions
 * a player actually has: will this unit still be mine next battle, and what is its class good for.
 * Same visual language as the weather panel (`.osada-wtip`), opened by hover or, on touch, a tap.
 *
 * [UnitIdentityPresenter] and [GameplayLocalization] both refresh the chip; both call [refresh] so
 * the service decision lives in one place.
 */
internal object UnitServiceChip {
    private const val TIP_ID = "osadaServiceTip"

    fun refresh(
        element: HTMLElement,
        unit: GameUnit,
    ) {
        val text = label(unit)
        element.textContent = text
        element.title = ""
        element.setAttribute("aria-label", label(unit, full = true))
        element.onmouseenter = { _: MouseEvent -> show(element, unit) }
        element.onmouseleave = { _: MouseEvent -> AnchoredTip.hide(TIP_ID) }
        TapTip.attach(element, TIP_ID) { anchor -> show(anchor, unit) }
    }

    private fun serviceKey(unit: GameUnit): String =
        when {
            unit.isTemporaryBorrowed || unit.nodossier -> "auxiliary"
            // Campaign carry-over follows player control, not the presence of a hero formation id.
            // A newly placed unit can be permanent before that id has been minted.
            CampaignHeroScope.unitEligible(unit) -> "core"
            else -> "scenario"
        }

    /** The chip carries the short service word (it shares one row with several other chips); the
     *  panel title spells it out. */
    private fun label(
        unit: GameUnit,
        full: Boolean = false,
    ): String =
        I18n.t(
            "unit_info.identity.class_service",
            mapOf(
                "class" to GameText.unitClass(unit.unitData(true).uclass),
                "service" to
                    I18n.t("unit_info.identity.service.${serviceKey(unit)}" + if (full) ".full" else ""),
            ),
        )

    private fun show(
        anchor: HTMLElement,
        unit: GameUnit,
    ) {
        AnchoredTip.show(TIP_ID, anchor, html(unit))
    }

    private fun html(unit: GameUnit): String {
        val data = unit.unitData(true)
        val service = serviceKey(unit)
        val lines = mutableListOf<Pair<String, String>>()
        roleKey(data.uclass)?.let { lines += "dim" to I18n.t("unit_info.identity.role.$it") }
        // Read from the same predicate CombatApplication consults, so a class default and an
        // efile's per-record `Capture Flag` are both reported as the rules will treat them.
        lines +=
            if (UnitCapabilities.canCaptureHex(data)) {
                "good" to I18n.t("unit_info.identity.capture.yes")
            } else {
                "bad" to I18n.t("unit_info.identity.capture.no")
            }
        return "<div class=\"osada-wtip__title\">${label(unit, full = true)}</div>" +
            "<div class=\"osada-wtip__story\">${I18n.t("unit_info.identity.service.$service.help")}</div>" +
            lines.joinToString("") { (kind, text) ->
                "<div class=\"osada-wtip__line osada-wtip__line--$kind\">$text</div>"
            }
    }

    private fun roleKey(uclass: Int): String? =
        UnitClass.entries
            .firstOrNull { it.value == uclass && it != UnitClass.NONE }
            ?.name
            ?.lowercase()
}
