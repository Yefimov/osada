package org.osada.ui

import org.osada.CURRENCY_MULTIPLIER
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.getCountryName
import org.osada.movMethodNames
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/** Fills the right detail column for the selected equipment entry. */
internal fun EquipmentWindowBuilder.renderEquipmentDetail(eq: EquipmentData?) {
    val body = byId("eqDetailBody") ?: return
    clearTag(body)
    if (eq == null) {
        val empty = addTag(body, "div")
        empty.className = "osada-eqd-empty"
        empty.textContent = I18n.t("equipment.detail.select_prompt")
        return
    }
    buildEqDetailHeader(body, eq)
    buildEqDetailStats(body, eq)
    buildEqDetailDescription(body, eq)
    equipmentMechanicsNote(eq)?.let { note ->
        val mechanics = addTag(body, "div")
        mechanics.className = "osada-eqd-desc osada-eqd-mechanics"
        mechanics.textContent = note
    }
}

private fun EquipmentWindowBuilder.buildEqDetailHeader(
    body: HTMLElement,
    eq: EquipmentData,
) {
    val portrait = addTag(body, "div")
    portrait.className = "osada-eqd-portrait"
    val img = addTag(portrait, "div")
    img.className = "osada-eqd-portrait__img"
    img.style.backgroundImage = "url(${UnitIconResolver.forCurrentScenario(eq.eqid, eq.icon)})"
    val name = addTag(body, "div")
    name.className = "osada-eqd-name"
    // Country flag left of the name (user request): same flags_med.png sprite + 0-based
    // slot convention the start-menu list rows use (eq.country is 1-based, hence -1).
    if (eq.country > 0) {
        val flag = addTag(name, "span")
        flag.className = "osadaFlag osada-eqd-flag"
        flag.style.backgroundImage = "url('resources/ui/flags/${Equipment.UNITED_NAME}/flags_med.png')"
        flag.style.backgroundPosition = "${-FLAG_SPRITE_WIDTH * (eq.country - 1)}px 0px"
        flag.title = Equipment.getCountryName(eq.country - 1)
    }
    val nameText = addTag(name, "span")
    nameText.textContent = eq.name
    val markings = addTag(name, "span")
    markings.className = "osada-capability-marks"
    EquipmentMarkings.render(markings, eq)
    val cls = addTag(body, "div")
    cls.className = "osada-eqd-class"
    cls.textContent = "${unitClassNames.getOrNull(eq.uclass) ?: ""} · ${Equipment.getCountryName(eq.country - 1)}"
    val avail = addTag(body, "div")
    avail.className = "osada-eqd-avail"
    avail.textContent = equipmentAvailabilityText(eq)
    val cost = addTag(body, "div")
    cost.className = "osada-eqd-cost"
    cost.innerHTML = "${eq.cost * CURRENCY_MULTIPLIER}${UIBuilder.currencyIcon}"
}

private fun EquipmentWindowBuilder.buildEqDetailStats(
    body: HTMLElement,
    eq: EquipmentData,
) {
    val grid = addTag(body, "div")
    grid.className = "osada-eqd-stats"

    fun stat(
        label: String,
        value: Any,
        help: String,
    ) {
        val row = addTag(grid, "div")
        row.className = "osada-eqd-stat"
        row.title = help
        row.innerHTML = "<b>$label</b><span>$value</span>"
    }
    stat("Soft attack", eq.softatk, "Attack power vs soft targets — infantry, artillery, unarmoured vehicles.")
    stat("Hard attack", eq.hardatk, "Attack power vs hard targets — tanks and other armoured vehicles.")
    stat("Air attack", eq.airatk, "Attack power vs aircraft.")
    stat(
        "Naval attack",
        eq.navalatk,
        "Attack power vs ships — used when firing on naval targets (e.g. coastal guns, or infantry " +
            "engaging a landing craft).",
    )
    stat("Ground def", eq.grounddef, "Defence when attacked by a ground unit.")
    stat("Air def", eq.airdef, "Defence when attacked from the air.")
    stat(
        "Close def",
        eq.closedef,
        "Defence in close combat — when an adjacent enemy attacks at melee range, as opposed to ranged fire.",
    )
    stat(
        "Range def",
        eq.rangedefmod,
        "Defence bonus against ground fire from outside melee range — halved for an attacker within " +
            "its own gun range, and waived against a moved Anti-Tank unit without a Tank Killer leader.",
    )
    stat(
        "Initiative",
        eq.initiative,
        "Higher initiative strikes first in combat, often before the enemy can return fire.",
    )
    stat("Movement", eq.movpoints, "Movement points per turn.")
    stat(
        "Movement type",
        movMethodNames.getOrNull(eq.movmethod) ?: "Unknown",
        "How this unit moves — determines terrain cost, and whether it needs a road or (for Rail) " +
            "is confined to the rail network.",
    )
    stat("Spotting", eq.spotrange, "How many hexes away this unit reveals hidden enemies.")
    stat("Range", if (eq.gunrange == 0) 1 else eq.gunrange, "Firing range in hexes (1 = must be adjacent).")
    stat("Ammo", eq.ammo, "Rounds of ammunition before the unit must resupply.")
    if (eq.fuel > 0) stat("Fuel", eq.fuel, "Fuel available before the unit must resupply.")
}

private fun EquipmentWindowBuilder.buildEqDetailDescription(
    body: HTMLElement,
    eq: EquipmentData,
) {
    // Narrative hook: only rendered when a reviewed description exists for this unit.
    val description = equipmentDescriptionOrNull(eq) ?: return
    val desc = addTag(body, "div")
    desc.className = "osada-eqd-desc"
    desc.textContent = description
}
