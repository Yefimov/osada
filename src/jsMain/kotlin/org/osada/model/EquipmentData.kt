package org.osada.model

import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Json

@JsExport
@JsName("EquipmentData")
class EquipmentData {
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

/** Whether this equipment can be bought/found in [year]/[month] (1-based month, matching
 *  monthavailable/monthexpired). Shared by the equipment window, the unit-card tooltip, and the
 *  AI's own purchase filter so all three agree on the same availability window. */
fun EquipmentData.isAvailableIn(year: Int, month: Int): Boolean {
    val afterStart = year > yearavailable || (year == yearavailable && month >= monthavailable)
    val beforeEnd = year < yearexpired || (year == yearexpired && month <= monthexpired)
    return afterStart && beforeEnd
}

fun Json.toEquipmentData(parseHints: List<String>): EquipmentData {
    val data = EquipmentData()
    val values = this.unsafeCast<Array<dynamic>>()
    parseHints.forEachIndexed { index, hint ->
        val value = values[index]
        when (hint) {
            "gunrange" -> data.gunrange = value as Int
            "icon" -> data.icon = value as String
            "yearexpired" -> data.yearexpired = value as Int
            "cost" -> data.cost = value as Int
            "initiative" -> data.initiative = value as Int
            "spotrange" -> data.spotrange = value as Int
            "hardatk" -> data.hardatk = value as Int
            "softatk" -> data.softatk = value as Int
            "uclass" -> data.uclass = value as Int
            "airdef" -> data.airdef = value as Int
            "fuel" -> data.fuel = value as Int
            "airseaweight" -> data.airseaweight = value as Int
            "rangedefmod" -> data.rangedefmod = value as Int
            "airatk" -> data.airatk = value as Int
            "groundweight" -> data.groundweight = value as Int
            "movmethod" -> data.movmethod = value as Int
            "navalatk" -> data.navalatk = value as Int
            "movpoints" -> data.movpoints = value as Int
            "grounddef" -> data.grounddef = value as Int
            "target" -> data.target = value as Int
            "yearavailable" -> data.yearavailable = value as Int
            "name" -> data.name = value as String
            "country" -> data.country = value as Int
            "closedef" -> data.closedef = value as Int
            "ammo" -> data.ammo = value as Int
            "attr" -> data.attr = value as Int
            "embark" -> data.embark = value as Int
            "monthavailable" -> data.monthavailable = value as Int
            "monthexpired" -> data.monthexpired = value as Int
        }
    }
    return data
}
