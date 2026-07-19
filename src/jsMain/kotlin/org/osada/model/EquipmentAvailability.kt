package org.osada.model

import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json

// Per-campaign purchase/upgrade/prototype allowlist loading, split out of [Equipment].
//
// Loaded once ever (not per scenario): a single availability.json holds every efile's
// allowlist. Synchronous like the non-async equipment load path -- it's a small file and
// this only runs once per session.
private fun Equipment.loadAvailabilityIfNeeded() {
    if (availabilityLoadAttempted) return
    availabilityLoadAttempted = true
    val text = fetchAvailabilityText()
    if (text.isNullOrBlank()) return
    availabilityMap = parseAvailabilityJson(text)
}

private fun Equipment.fetchAvailabilityText(): String? {
    val path = "${Equipment.EQUIPMENT_PATH}${Equipment.UNITED_NAME}/availability.json"
    val request = XMLHttpRequest()
    request.open("GET", path, false)
    request.send(null)
    val status = request.status.toInt()
    return if (status in httpSuccessRange || status == 0) request.responseText else null
}

private fun parseAvailabilityJson(text: String): Map<String, Set<Int>> {
    val json = JSON.parse<Json>(text)
    val keys = js("Object.keys")(json).unsafeCast<Array<String>>()
    val map = mutableMapOf<String, Set<Int>>()
    keys.forEach { key ->
        val ids = json.asDynamic()[key].unsafeCast<Array<Int>>()
        map[key] = ids.toSet()
    }
    return map
}

/** The current campaign's purchase/upgrade/prototype allowlist, or null if filtering is
 *  off/unavailable (fail-open: an efile with no availability.json entry, e.g. a future
 *  standalone scenario, sees everything rather than an empty buy list). */
private fun Equipment.currentAllowlist(): Set<Int>? {
    if (!availabilityFilterEnabled) return null
    loadAvailabilityIfNeeded()
    val allowlist = availabilityMap?.get(name)
    if (allowlist == null) {
        console.warn("[osada] no availability entry for '$name' -- purchase list is unfiltered")
    }
    return allowlist
}

internal fun Equipment.applyAvailabilityFilter(ids: List<Int>): List<Int> {
    val allowlist = currentAllowlist() ?: return ids
    return ids.filter { it in allowlist }
}
