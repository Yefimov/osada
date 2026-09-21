package org.osada.model

import org.w3c.xhr.XMLHttpRequest

/** Equipment DB loading (network/XHR), split out of [Equipment] to keep its function count in bounds. */
fun Equipment.resetEquipment() {
    equipmentMap.clear()
    equipmentIndexes.clear()
    equipmentToLoadSet.clear()
    equipmentToLoad = 0
}

/**
 * Loads every merged country file this scenario needs, then runs [onComplete].
 *
 * Two independent sources decide which files those are, and both are required:
 *
 * * **The player list** -- each player's own `country` plus its declared `support` nations. This
 *   is what the game may BUY and UPGRADE, so it has to be loaded whether or not anything from
 *   those nations is on the map.
 * * **[requiredEqids]** -- the equipment ids the scenario (or the save, or the campaign core)
 *   actually references, resolved through [EquipmentCountryIndex]. A placed formation's `flag` is
 *   its nationality and does **not** have to be the country its equipment record was merged under,
 *   so the player list alone silently misses records: 820 references across 131 deployed scenarios,
 *   of which Falciu 2's invisible Tiganca garrison was the reported one.
 *
 * The second source is deliberately kept out of [Player.supportCountries]: that list is what the
 * purchase catalogue and the side's country banner read, so widening it to satisfy a fetch would
 * change what the player may buy and how the side is labelled. Nothing here changes a unit's
 * nationality either -- only which files are fetched.
 *
 * With no index available (a build without the sidecar, or a Karma run with no served resources)
 * [requiredEqids] resolves to nothing and this is exactly the player-list-only loader it replaced.
 */
fun Equipment.addPlayersEquipment(
    players: List<Player>,
    requiredEqids: Set<Int> = emptySet(),
    onComplete: () -> Unit,
) {
    resetEquipment()
    loadCallback = onComplete
    val fromPlayers = mutableSetOf(-1)
    players.forEach { player ->
        fromPlayers.add(player.country)
        player.supportCountries.forEach { sc ->
            if (sc > 0) fromPlayers.add(sc - 1)
        }
    }
    // The index is a one-off fetch cached for the session, so this is synchronous after the first
    // scenario; the callback shape is what keeps the very first load correct.
    EquipmentCountryIndex.ensureLoaded {
        // `countryFilesFor` answers in FILE numbers and `addCountryEquipment` takes the 0-based
        // country id it derives them from, hence the -1. See [Equipment.EQUIPMENT_PATH].
        EquipmentCountryIndex.countryFilesFor(requiredEqids).forEach { fromPlayers.add(it - 1) }
        equipmentToLoadSet.addAll(fromPlayers)
        equipmentToLoad = equipmentToLoadSet.size
        equipmentToLoadSet.toList().forEach { country ->
            addCountryEquipment(country) { checkComplete() }
        }
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
            // Per-efile content overrides, applied here so every later reader (combat, purchase
            // window, unit card, AI buy filter) sees one consistent record. `Equipment.name` is
            // set from the scenario's `eqp` before any country file is fetched. See
            // [EquipmentOverrides] for why a shared merged record needs this instead of an edit.
            EquipmentOverrides.apply(eqid, equipment)
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
