package org.osada.model

import org.w3c.xhr.XMLHttpRequest

object UnitDescriptions {
    private const val HTTP_OK = 200

    private var byName: Map<String, String>? = null
    private var byId: Map<Int, String>? = null
    private var loadStarted = false

    fun load() {
        if (loadStarted) return
        loadStarted = true

        val path = "${Equipment.EQUIPMENT_PATH}${Equipment.UNITED_NAME}/unit-descriptions.json"
        val request = XMLHttpRequest()
        request.open("GET", path, async = true)
        request.onload = { handleLoad(request) }
        request.onerror = {
            console.error("[UnitDescriptions] network error")
        }
        request.send()
    }

    private fun handleLoad(request: XMLHttpRequest) {
        if (request.status.toInt() == HTTP_OK || request.status.toInt() == 0) {
            parseResponse(request.responseText)
        } else {
            console.warn("[UnitDescriptions] load failed, status", request.status)
        }
    }

    // The XHR response is an external, untrusted JSON blob -- any parse error must be logged
    // and swallowed rather than crash the load callback, so a broad catch is intentional here.
    @Suppress("TooGenericExceptionCaught")
    private fun parseResponse(responseText: String) {
        try {
            val data: dynamic = js("JSON.parse")(responseText)
            if (data != null) {
                val maps = buildDescriptionMaps(data)
                byName = maps.first
                byId = maps.second
                console.log(
                    "[UnitDescriptions] loaded",
                    maps.first.size,
                    "name entries and",
                    maps.second.size,
                    "equipment entries",
                )
            }
        } catch (e: Exception) {
            console.error("[UnitDescriptions] parse error:", e)
        }
    }

    private fun buildDescriptionMaps(data: dynamic): Pair<Map<String, String>, Map<Int, String>> {
        val namesData: dynamic = if (data.byName != undefined) data.byName else data
        val idsData: dynamic = if (data.byId != undefined) data.byId else null
        val mutableMap = mutableMapOf<String, String>()
        js("Object.keys")(namesData).unsafeCast<Array<String>>().forEach { key ->
            val value = namesData[key]
            if (value != null) {
                mutableMap[key] = value.toString()
            }
        }
        val idMap = mutableMapOf<Int, String>()
        if (idsData != null) {
            js("Object.keys")(idsData).unsafeCast<Array<String>>().forEach { key ->
                val value = idsData[key]
                val id = key.toIntOrNull()
                if (id != null && value != null) idMap[id] = value.toString()
            }
        }
        return mutableMap to idMap
    }

    fun get(name: String): String? = byName?.get(name.trim())?.takeIf { it.isNotBlank() }

    /** Exact row-level prose wins. Name-only text remains as a compatibility fallback. */
    fun get(equipment: EquipmentData): String? =
        byId
            ?.get(equipment.eqid)
            ?.takeIf { it.isNotBlank() }
            ?: get(equipment.name)

    internal fun setForTest(
        names: Map<String, String>?,
        ids: Map<Int, String>? = null,
    ) {
        byName = names
        byId = ids
        loadStarted = true
    }

    internal fun parseForTest(responseText: String) {
        byName = null
        byId = null
        parseResponse(responseText)
        loadStarted = true
    }
}
