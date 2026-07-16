package org.osada.model

import org.w3c.xhr.XMLHttpRequest

object UnitDescriptions {
    private var map: Map<String, String>? = null
    private var loadStarted = false

    fun load() {
        if (loadStarted) return
        loadStarted = true

        val path = "${Equipment.equipmentPath}${Equipment.unitedName}/unit-descriptions.json"
        val request = XMLHttpRequest()
        request.open("GET", path, async = true)
        request.onload = {
            if (request.status.toInt() == 200 || request.status.toInt() == 0) {
                try {
                    val data: dynamic = js("JSON.parse")(request.responseText)
                    if (data != null) {
                        val mutableMap = mutableMapOf<String, String>()
                        js("Object.keys")(data).unsafeCast<Array<String>>().forEach { key ->
                            val value = data[key]
                            if (value != null) {
                                mutableMap[key] = value.toString()
                            }
                        }
                        map = mutableMap
                        console.log("[UnitDescriptions] loaded", mutableMap.size, "entries")
                    }
                } catch (e: Exception) {
                    console.error("[UnitDescriptions] parse error:", e)
                }
            } else {
                console.warn("[UnitDescriptions] load failed, status", request.status)
            }
        }
        request.onerror = {
            console.error("[UnitDescriptions] network error")
        }
        request.send()
    }

    fun get(name: String): String? = map?.get(name.trim())?.takeIf { it.isNotBlank() }

    internal fun setForTest(m: Map<String, String>?) {
        map = m
        loadStarted = true
    }
}
