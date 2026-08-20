package org.osada.model

import kotlin.js.Json

@JsExport
@JsName("EquipmentData")
class EquipmentData {
    /** Stable merged-equipment id. The loader assigns it from the JSON object's row key. */
    var eqid: Int = 0
    var gunrange: Int = 0
    var icon: String = ""
    var yearexpired: Int = 0
    var cost: Int = 0
    var initiative: Int = 0
    var spotrange: Int = 0
    var hardatk: Int = 0
    var softatk: Int = 0
    var uclass: Int = 0
    var airdef: Int = 0
    var fuel: Int = 0
    var airseaweight: Int = 0
    var rangedefmod: Int = 0
    var airatk: Int = 0
    var groundweight: Int = 0
    var movmethod: Int = 0
    var navalatk: Int = 0
    var movpoints: Int = 0
    var grounddef: Int = 0
    var target: Int = 0
    var yearavailable: Int = 0
    var name: String = ""
    var country: Int = 0
    var closedef: Int = 0
    var ammo: Int = 0
    var attr: Int = 0
    var embark: Int = 0

    /**
     * OG's `Special4` byte, added 2026-08-19 (`docs/og-fidelity-plan.md` §C). Bits 0..7:
     * Build/Repair, Dismount After Move, No Dirt Airfields, Rocket Bomber, Cut LOS, Allow LOF,
     * No ZOC, Evade. Defaults to 0 ("no data imported") for any equipment JSON whose parsehints
     * don't carry it -- see [EquipmentCombatEligibility.kt] for the full bit table and which of
     * these are read by anything today.
     */
    var attr2: Int = 0

    /**
     * OG's `SpecialEx` bytes 0..2, packed the same way `attr` packs `Special1..3`
     * (`byte0 + (byte1 shl 8) + (byte2 shl 16)`), added 2026-08-19. Bits 0..23: No Leader,
     * Lasting Sup., All Weather, Overrun toggle, No Ammo penalty, No Intercept Air, Clear mines,
     * NoNeedStation, Torpedo bomber, Counter Battery, Partizan, Exploit Success, Anti Sub (ASW),
     * AD Support, *(unused)*, SingleFireSup., Kamikaze, AirDropMines, Saboteur, Jet (Stealth),
     * Supply Unit, *(unused x3)*. Defaults to 0, same rule as [attr2].
     */
    var attrEx: Int = 0

    // 1-based (1=January), matching the OG CSV's own MonthAvail/MonthExpired convention. Default
    // to full-year coverage: any equipment JSON whose parsehints don't include these two fields
    // (PM's own original adlerkorps/pacific sets, never touched by the OG import) behaves exactly
    // as it did before month granularity existed.
    var monthavailable: Int = 1
    var monthexpired: Int = 12
}

/**
 * Returns an equipment view whose player-facing numeric capabilities are multiplied by [multiplier].
 * Identity, classification, price and availability stay unchanged: multiplying `uclass`, `target`,
 * `movmethod`, dates or cost would corrupt rule dispatch/economy rather than strengthen the unit.
 *
 * **`spotrange` is deliberately excluded.** It is not a strength stat — it is the fog-of-war input.
 * At ×10 every unit saw the whole map, so switching Stalin Regime on silently disabled fog of war,
 * and enemy units stayed revealed afterwards because the hexes had already been spotted. That made
 * a power toggle also an information toggle, which is what "Observer Mode" is separately for
 * (`noFOW`) — the two must stay independent, and the player must be able to run Stalin Regime with
 * fog intact. Reported 2026-07-31 ("Stalin Regime shouldn't disable fog of war"; "I can see enemy
 * units even when I disable Stalin Regime and Observer Mode").
 */
internal fun EquipmentData.withStatMultiplier(multiplier: Int): EquipmentData =
    EquipmentData().also { result ->
        result.eqid = eqid
        result.gunrange = gunrange * multiplier
        result.icon = icon
        result.yearexpired = yearexpired
        result.cost = cost
        result.initiative = initiative * multiplier
        result.spotrange = spotrange
        result.hardatk = hardatk * multiplier
        result.softatk = softatk * multiplier
        result.uclass = uclass
        result.airdef = airdef * multiplier
        result.fuel = fuel * multiplier
        result.airseaweight = airseaweight
        result.rangedefmod = rangedefmod * multiplier
        result.airatk = airatk * multiplier
        result.groundweight = groundweight
        result.movmethod = movmethod
        result.navalatk = navalatk * multiplier
        result.movpoints = movpoints * multiplier
        result.grounddef = grounddef * multiplier
        result.target = target
        result.yearavailable = yearavailable
        result.name = name
        result.country = country
        result.closedef = closedef * multiplier
        result.ammo = ammo * multiplier
        result.attr = attr
        result.embark = embark
        result.attr2 = attr2
        result.attrEx = attrEx
        result.monthavailable = monthavailable
        result.monthexpired = monthexpired
    }

/** Whether this equipment can be bought/found in [year]/[month] (1-based month, matching
 *  monthavailable/monthexpired). Shared by the equipment window, the unit-card tooltip, and the
 *  AI's own purchase filter so all three agree on the same availability window. */
fun EquipmentData.isAvailableIn(
    year: Int,
    month: Int,
): Boolean {
    val afterStart = year > yearavailable || (year == yearavailable && month >= monthavailable)
    val beforeEnd = year < yearexpired || (year == yearexpired && month <= monthexpired)
    return afterStart && beforeEnd
}

fun Json.toEquipmentData(parseHints: List<String>): EquipmentData {
    val data = EquipmentData()
    val values = this.unsafeCast<Array<dynamic>>()
    // The field set is split across three helpers purely to keep each `when` under detekt's
    // cyclomatic-complexity limit. Every hint matches at most one arm across all three, so
    // calling all three per field is behaviour-identical to the original single `when`.
    parseHints.forEachIndexed { index, hint ->
        val value = values[index]
        data.applyEquipmentFieldsA(hint, value)
        data.applyEquipmentFieldsB(hint, value)
        data.applyEquipmentFieldsC(hint, value)
        data.applyEquipmentFieldsD(hint, value)
    }
    return data
}

private fun EquipmentData.applyEquipmentFieldsA(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "gunrange" -> gunrange = value as Int
        "icon" -> icon = value as String
        "yearexpired" -> yearexpired = value as Int
        "cost" -> cost = value as Int
        "initiative" -> initiative = value as Int
        "spotrange" -> spotrange = value as Int
        "hardatk" -> hardatk = value as Int
        "softatk" -> softatk = value as Int
        "uclass" -> uclass = value as Int
        "airdef" -> airdef = value as Int
    }
}

private fun EquipmentData.applyEquipmentFieldsB(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "fuel" -> fuel = value as Int
        "airseaweight" -> airseaweight = value as Int
        "rangedefmod" -> rangedefmod = value as Int
        "airatk" -> airatk = value as Int
        "groundweight" -> groundweight = value as Int
        "movmethod" -> movmethod = value as Int
        "navalatk" -> navalatk = value as Int
        "movpoints" -> movpoints = value as Int
        "grounddef" -> grounddef = value as Int
        "target" -> target = value as Int
    }
}

private fun EquipmentData.applyEquipmentFieldsC(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "yearavailable" -> yearavailable = value as Int
        "name" -> name = value as String
        "country" -> country = value as Int
        "closedef" -> closedef = value as Int
        "ammo" -> ammo = value as Int
        "attr" -> attr = value as Int
        "embark" -> embark = value as Int
        "monthavailable" -> monthavailable = value as Int
        "monthexpired" -> monthexpired = value as Int
    }
}

/** OG's `Special4`/`SpecialEx`, added 2026-08-19 -- see [EquipmentData.attr2] / [EquipmentData.attrEx]. */
private fun EquipmentData.applyEquipmentFieldsD(
    hint: String,
    value: dynamic,
) {
    when (hint) {
        "attr2" -> attr2 = value as Int
        "attrEx" -> attrEx = value as Int
    }
}
