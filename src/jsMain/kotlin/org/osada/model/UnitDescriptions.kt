package org.osada.model

import org.w3c.xhr.XMLHttpRequest

object UnitDescriptions {
    private const val HTTP_OK = 200

    private var map: Map<String, String>? = null
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
                val mutableMap = buildDescriptionMap(data)
                map = mutableMap
                console.log("[UnitDescriptions] loaded", mutableMap.size, "entries")
            }
        } catch (e: Exception) {
            console.error("[UnitDescriptions] parse error:", e)
        }
    }

    private fun buildDescriptionMap(data: dynamic): Map<String, String> {
        val mutableMap = mutableMapOf<String, String>()
        js("Object.keys")(data).unsafeCast<Array<String>>().forEach { key ->
            val value = data[key]
            if (value != null) {
                mutableMap[key] = value.toString()
            }
        }
        return mutableMap
    }

    fun get(name: String): String? = map?.get(name.trim())?.takeIf { it.isNotBlank() }

    internal fun setForTest(m: Map<String, String>?) {
        map = m
        loadStarted = true
    }
}
