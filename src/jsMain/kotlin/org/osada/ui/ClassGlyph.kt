package org.osada.ui

import org.osada.UnitClass
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * The icon of an equipment-window class tab, drawn wherever a class needs one: the tabs themselves,
 * the reserve strip's unit cards and the dossier's casualty grid.
 *
 * Most tabs have an `osada-menu` font glyph ([UIBuilder.eqClassButtons]). Fortification never had
 * one -- the font's glyph map is not recovered, and guessing a codepoint is how the wrong picture
 * ends up on a button -- so it gets an inline SVG tower instead (2026-09-26). It is drawn in
 * `currentColor` at `1em`, so it takes the colour and size of whatever glyph it stands beside.
 *
 * The SVG is not stored in [UIBuilder.eqClassButtons] because the dossier writes those glyphs with
 * `textContent`, which would print the markup.
 */
internal object ClassGlyph {
    /** A crenellated tower with a gate and an arrow slit; evenodd cuts the openings out. */
    private const val FORTIFICATION_SVG =
        "<svg class=\"osada-class-svg\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<path fill=\"currentColor\" fill-rule=\"evenodd\" d=\"" +
            "M4 2h3v2h2V2h2v2h2V2h2v2h2V2h3v6l-2 2v10h2v2H4v-2h2V10L4 8z" +
            "M10 20v-4a2 2 0 0 1 4 0v4z" +
            "M11 11h2v3h-2z" +
            "\"/></svg>"

    /** Tab id -> the `equipment.class_tab.<key>` I18n keys (the same table the window's own
     *  localization pass uses for the tab labels). */
    private val labelKeys =
        mapOf(
            "1" to "infantry",
            "2" to "tanks",
            "3" to "recon",
            "4" to "anti_tank",
            "6" to "fortification",
            "8" to "artillery",
            "9" to "air_defence",
            "10" to "fighters",
            "11" to "bombers",
            "15" to "naval",
        )

    /** The tab [uclass] is listed under -- its own, or the one it is merged into (Flak under Air
     *  defence, the naval classes under Naval). Null for a class with no tab (the transports). */
    fun tabFor(uclass: Int): String? =
        UIBuilder.eqClassButtons.keys.firstOrNull { key ->
            key != UnitClass.NONE.value.toString() &&
                UIBuilder.classesForTab(key.toIntOrNull() ?: 0).any { it.value == uclass }
        }

    /** Draws tab [tabKey]'s icon into [target]. False when that tab has no icon at all. */
    fun render(
        target: HTMLElement,
        tabKey: String,
    ): Boolean {
        val glyph = UIBuilder.eqClassButtons[tabKey]?.first
        when {
            tabKey == UnitClass.FORTIFICATION.value.toString() -> target.innerHTML = FORTIFICATION_SVG
            !glyph.isNullOrEmpty() -> target.textContent = glyph
            else -> return false
        }
        return true
    }

    /** The tab's localized name, for a tooltip. */
    fun label(tabKey: String): String? = labelKeys[tabKey]?.let { I18n.t("equipment.class_tab.$it.label") }
}
