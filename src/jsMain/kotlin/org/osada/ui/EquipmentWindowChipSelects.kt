@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package org.osada.ui

import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

// The two dropdowns in the equipment window's class-tab row: the country filter and the sort
// order. Split out of restructureEquipmentWindow's file purely to keep it under the detekt
// TooManyFunctions limit — no behavior split intended.

/**
 * A <select> with a short stand-in chip for the phone/compact layouts.
 *
 * The collapsed text of these two dropdowns ("Sort: Close defence", "All Countries") is wider than
 * the whole class-tab row on a portrait phone, where the tabs and the reverse-order button have to
 * share it. There, CSS shows the chip and stretches the select transparently over it: the tap still
 * opens the native picker, and the option names stay descriptive in the one place there is room for
 * them — the opened list. The window heading already names the active class and country, so nothing
 * is actually lost by not repeating the current value in the control. Desktop keeps the select
 * itself, where the current value is worth reading at a glance.
 *
 * The wrapper, not the select, is what [EquipmentWindowController.syncCountrySelect] hides for a
 * single-country side: hiding the select alone would leave its chip behind.
 */
internal fun buildChipSelect(
    parent: HTMLElement,
    id: String,
    shortText: String,
): HTMLElement {
    val wrap = addTag(parent, "div")
    wrap.id = id + "Wrap"
    wrap.className = "osada-eq-chipselect"
    val short = addTag(wrap, "span")
    short.id = id + "Short"
    short.className = "osada-eq-chipselect__short"
    short.setAttribute("aria-hidden", "true")
    short.textContent = shortText
    val select = addTag(wrap, "select")
    select.id = id
    return select
}

/** Country selector for sides with support countries (e.g. Germany + Romania). Populated by
 *  EquipmentWindowController.syncCountrySelect; hidden when the side has a single country. */
internal fun buildCountrySelect(parent: HTMLElement) {
    val select = buildChipSelect(parent, "osadaEqCountry", I18n.t("equipment.country.short"))
    select.title = I18n.t("equipment.country_filter.help")
    select.setAttribute("aria-label", I18n.t("equipment.country_filter.help"))
    byId("osadaEqCountryWrap")?.style?.display = "none"
    select.asDynamic().onchange = {
        // The option's own VALUE (-1 = "All Countries", 0..N-1 = country), not .selectedIndex
        // (a DOM position — "All" sits at position 0 ahead of the real countries, so position
        // and value only agree for "All"; everything else is off by one against it).
        val idx = (select.asDynamic().value as? String)?.toIntOrNull() ?: -1
        // Same state changes as the legacy "changecountry" action, minus the blind cycling.
        byId("eqSelCountry")?.asDynamic()?.country = idx
        val userSel = byId("eqUserSel")?.asDynamic()
        userSel?.userunit = -1
        userSel?.equnit = -1
        // Was left stale here (only userunit/equnit reset) — a transport picked for a unit in
        // the PREVIOUS country stayed selected after switching country/to "All Countries",
        // which could resurface as an unfiltered transport list on the next render.
        userSel?.eqtransport = -1
        GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
    }
}

/** Compact sort control in the class-tabs row — replaces the broken #eqSortOptions panel.
 *  See [buildChipSelect] for why it carries a short label of its own on a phone. */
internal fun buildSortSelect(parent: HTMLElement) {
    val select = buildChipSelect(parent, "osadaEqSort", I18n.t("equipment.sort.short"))
    select.title = I18n.t("equipment.sort.help")
    select.setAttribute("aria-label", I18n.t("equipment.sort.prompt"))
    addSelectOption(select, "Sort: Cost", "cost", true)
    UIBuilder.unitStats.forEach { stat ->
        val property = stat.property ?: return@forEach
        if (!stat.isSortable) return@forEach
        addSelectOption(select, "Sort: ${stat.title}", property, false)
    }
    select.asDynamic().onchange = {
        val userSel = byId("eqUserSel")?.asDynamic()
        val next = select.asDynamic().value as? String ?: "cost"
        if (next != (userSel?.sortproperty as? String ?: "cost")) {
            userSel?.sortproperty = next
            GameHolder.instance?.ui?.updateEquipmentWindow(userSel?.eqclass as? Int ?: UnitClass.TANK.value)
        }
    }
}
