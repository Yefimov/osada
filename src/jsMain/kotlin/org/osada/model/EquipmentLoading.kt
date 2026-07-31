package org.osada.model

import org.w3c.xhr.XMLHttpRequest

/** Equipment DB loading (network/XHR), split out of [Equipment] to keep its function count in bounds. */
fun Equipment.resetEquipment() {
    equipmentMap.clear()
    equipmentIndexes.clear()
    equipmentToLoadSet.clear()
    equipmentToLoad = 0
}

fun Equipment.addPlayersEquipment(
    players: List<Player>,
    onComplete: () -> Unit,
) {
    resetEquipment()
    loadCallback = onComplete
    equipmentToLoadSet.add(-1)
    players.forEach { player ->
        equipmentToLoadSet.add(player.country)
        player.supportCountries.forEach { sc ->
            if (sc > 0) equipmentToLoadSet.add(sc - 1)
        }
    }
    equipmentToLoad = equipmentToLoadSet.size
    equipmentToLoadSet.forEach { country ->
        addCountryEquipment(country) { checkComplete() }
    }
}

fun Equipment.addCountryEquipment(
    country: Int,
    onComplete: (() -> Unit)? = null,
) {
    loadCountryEquipment(country + 1, onComplete)
}

private fun Equipment.loadCountryEquipment(
    country: Int,
    onComplete: (() -> Unit)?,
) {
    val path = "${Equipment.EQUIPMENT_PATH}${Equipment.UNITED_NAME}/${Equipment.FILE_PREFIX}$country.json"
    if (asyncLoad) {
        val request = XMLHttpRequest()
        request.onload = {
            if (request.readyState == 4.toShort() &&
                (request.status == 200.toShort() || request.status == 0.toShort())
            ) {
                parseCountryEquipment(country, JSON.parse(request.responseText))
                onComplete?.invoke()
            }
        }
        request.open("GET", path, true)
        request.send(null)
    } else {
        val request = XMLHttpRequest()
        request.open("GET", path, false)
        request.send(null)
        val status = request.status.toInt()
        if (status in httpSuccessRange || status == 0) {
            val text = request.responseText
            if (text.isNotBlank()) {
                parseCountryEquipment(country, JSON.parse(text))
            }
        }
        onComplete?.invoke()
    }
}

fun Equipment.parseCountryEquipment(
    country: Int,
    data: kotlin.js.Json,
) {
    val indexes = data["indexes"]
    val units = data["units"]
    val parseHints = data["parsehints"].unsafeCast<Array<String>>()
    equipmentIndexes[country] = indexes
    val unitKeys = js("Object.keys")(units).unsafeCast<Array<String>>()
    val unitsDynamic = units.asDynamic()
    var loaded = 0
    unitKeys.forEach { key ->
        val eqid = key.toIntOrNull()
        val unitJson = unitsDynamic[key]
        if (eqid != null && unitJson != undefined) {
            val equipment = unitJson.unsafeCast<kotlin.js.Json>().toEquipmentData(parseHints.toList())
            equipment.eqid = eqid
            equipmentMap[eqid] = equipment
            loaded++
        }
    }
    console.log(
        "[osada] parseCountryEquipment country=$country unitsLoaded=$loaded totalEquipment=${equipmentMap.size}",
    )
}

private fun Equipment.checkComplete() {
    equipmentToLoad--
    if (equipmentToLoad <= 0) {
        equipmentToLoad = 0
        loadCallback?.invoke()
        loadCallback = null
    }
}
