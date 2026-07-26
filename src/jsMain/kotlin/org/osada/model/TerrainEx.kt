package org.osada.model

import org.osada.terrainEntrenchment
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json

/**
 * Per-efile terrain entrenchment, imported from OG's `TerrainEx.txt`
 * (`tools/og-import/terrain_ex_to_json.py` -> `resources/terrain-ex/<tag>.json`).
 *
 * Falls back to PM's own baseline ([terrainEntrenchment], one shared list for every efile) for
 * any efile that ships no TerrainEx data -- GCE, OLGCW and OLGWW2 never had the file at all, and
 * not every efile that does has been run through the importer -- and for any terrain id the data
 * omits.
 */
object TerrainEx {
    private const val PATH = "resources/terrain-ex/"
    private val httpSuccessRange = 200..299

    private var loadedForEfile: String? = null
    private var baseEntrenchByTerrain: Map<Int, Int> = emptyMap()

    /** Base entrenchment for [terrain] under the currently active efile ([Equipment.name]). */
    fun baseEntrenchment(terrain: Int): Int {
        loadIfNeeded()
        return baseEntrenchByTerrain[terrain] ?: terrainEntrenchment.getOrElse(terrain) { 0 }
    }

    // Synchronous, like `EquipmentAvailability`'s allowlist fetch: a small per-efile file, read
    // lazily on first use after the efile changes rather than threaded through scenario loading.
    private fun loadIfNeeded() {
        val efile = Equipment.name
        if (efile == loadedForEfile) return
        loadedForEfile = efile
        baseEntrenchByTerrain = fetch(efile)?.let(::parseBaseEntrench) ?: emptyMap()
    }

    private fun fetch(efile: String): String? {
        val request = XMLHttpRequest()
        request.open("GET", "$PATH${efile.removePrefix("eqp-")}.json", false)
        request.send(null)
        val status = request.status.toInt()
        return if (status in httpSuccessRange || status == 0) request.responseText else null
    }

    /** `{"terrain": {"<id>": {"base_entrench": n, ...}, ...}}` -> `{id: base_entrench}`. Missing
     *  or malformed input yields whatever entries it does have, never throws. */
    internal fun parseBaseEntrench(text: String): Map<Int, Int> {
        val terrain = JSON.parse<Json>(text).asDynamic().terrain
        if (terrain == null || terrain == undefined) return emptyMap()
        val map = mutableMapOf<Int, Int>()
        js("Object.keys")(terrain).unsafeCast<Array<String>>().forEach { id ->
            val tid = id.toIntOrNull()
            val entrench = terrain[id]?.base_entrench as? Int
            if (tid != null && entrench != null) map[tid] = entrench
        }
        return map
    }

    // Defaults to the CURRENT [Equipment.name] rather than a fixed sentinel, so the next
    // [baseEntrenchment] call sees no efile change and does not clobber this with a real fetch.
    internal fun setForTest(
        map: Map<Int, Int>,
        efile: String = Equipment.name,
    ) {
        baseEntrenchByTerrain = map
        loadedForEfile = efile
    }

    internal fun resetForTest() {
        baseEntrenchByTerrain = emptyMap()
        loadedForEfile = null
    }
}
