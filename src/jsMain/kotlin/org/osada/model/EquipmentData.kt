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
 */
internal fun EquipmentData.withStatMultiplier(multiplier: Int): EquipmentData =
    EquipmentData().also { result ->
        result.eqid = eqid
        result.gunrange = gunrange * multiplier
        result.icon = icon
        result.yearexpired = yearexpired
        result.cost = cost
        result.initiative = initiative * multiplier
        result.spotrange = spotrange * multiplier
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
