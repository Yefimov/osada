package org.osada.model

import org.w3c.xhr.XMLHttpRequest

/**
 * Which merged country file each equipment id lives in.
 *
 * **The loader used to infer this from the scenario's player list, and that inference is wrong.**
 * `addPlayersEquipment` fetched `equipment-country-<N>.json` for each player's own `country` plus
 * its declared `support` nations, on the assumption that every equipment id a scenario places
 * belongs to one of those files. A placed formation's NATIONALITY (`<unit flag=...>`) is not the
 * country its equipment RECORD was merged under, so the assumption fails on real content:
 *
 * > Falciu 2 (`rcampfa2.xml`, the Black Sea Fleet campaign's second scenario) garrisons the
 * > Romanian objective at Tiganca with eqid 16132 -- OG's immobile "Entrenched" infantry, icon
 * > `sbe04` -- written `flag="14"` under a player whose `country="13"` and `support="14"`. Both
 * > resolve to country file 14; record 16132 lives in file **13**. The file was never fetched, so
 * > `Equipment.getEquipment(16132)` returned an empty [EquipmentData]: class 0, no icon, no name,
 * > zero attack and zero defence. The unit was invisible, could not be attacked, and held a
 * > victory hex for the whole battle.
 *
 * A repo-wide audit found 820 such references across 131 deployed scenarios, so this was never one
 * scenario's bug.
 *
 * The fix is to load by ID rather than by nationality, which needs this index: a small
 * generated sidecar (`tools/eqp-merge/build_equipment_index.py`, ~7 KB) listing, for each merged
 * country file, the contiguous eqid runs it owns. Loading equipment a scenario actually references
 * deliberately does **not** touch [Player.supportCountries] -- that list is nationality and feeds
 * the purchase/upgrade catalogue ([org.osada.ui.EquipmentCatalogStrip]), so widening it to fix a
 * fetch would hand the player another nation's shopping list.
 *
 * ### Absence is tolerated
 * A build without the sidecar (or a Karma run, where `resources/` is not served) resolves nothing
 * and every caller falls back to exactly the player-list behaviour it had before. The index can
 * only ever ADD country files to a load set.
 */
object EquipmentCountryIndex {
    /** A contiguous run of equipment ids owned by one merged country file. */
    private class Segment(
        val countryFile: Int,
        val firstEqid: Int,
        val lastEqid: Int,
    )

    private const val SEGMENT_FILE = 0
    private const val SEGMENT_FIRST = 1
    private const val SEGMENT_LAST = 2

    private val path: String
        get() = "${Equipment.EQUIPMENT_PATH}${Equipment.UNITED_NAME}/equipment-index.json"

    /** Sorted by [Segment.firstEqid], never overlapping. Null until a load has SUCCEEDED. */
    private var segments: List<Segment>? = null

    val isLoaded: Boolean
        get() = segments != null

    /**
     * Fetches the sidecar unless it is already held, then runs [onReady].
     *
     * Honours [Equipment.asyncLoad] exactly as the country files do, so the synchronous path used
     * by tests and by save restore stays synchronous end to end. [onReady] runs whether or not the
     * sidecar could be read -- a failed fetch is a fallback, not an error.
     *
     * A SUCCESSFUL read is cached for the session, so this costs one request per session rather
     * than one per scenario. A failed one is deliberately not cached: the cost of retrying is a
     * single small request per scenario load, and the cost of not retrying is a whole session
     * silently stuck on the old player-list behaviour because one fetch lost a race at boot.
     */
    fun ensureLoaded(onReady: () -> Unit) {
        if (segments != null) {
            onReady()
            return
        }
        if (Equipment.asyncLoad) {
            val request = XMLHttpRequest()
            request.onload = {
                acceptResponse(request.status.toInt(), request.responseText)
                onReady()
            }
            request.onerror = { onReady() }
            request.open("GET", path, true)
            request.send(null)
        } else {
            val request = XMLHttpRequest()
            request.open("GET", path, false)
            request.send(null)
            acceptResponse(request.status.toInt(), request.responseText)
            onReady()
        }
    }

    private fun acceptResponse(
        status: Int,
        text: String,
    ) {
        val usable = (status in Equipment.httpSuccessRange || status == 0) && text.isNotBlank()
        if (!usable) {
            console.warn("[osada] equipment-index.json unavailable (status=$status); loading by player list only")
            return
        }
        parse(text)
    }

    /**
     * The sidecar is generated, but it is still an external file: a truncated or hand-edited one
     * must leave the game on its old behaviour rather than take the scenario load down.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun parseJson(text: String): dynamic =
        try {
            JSON.parse<dynamic>(text)
        } catch (e: Throwable) {
            console.error("[osada] equipment-index.json is not valid JSON:", e.message)
            null
        }

    private fun parse(text: String) {
        val parsed = parseJson(text) ?: return
        val raw = parsed.segments
        if (raw == null || raw == undefined) {
            console.error("[osada] equipment-index.json has no `segments` array")
            return
        }
        val parsedSegments = mutableListOf<Segment>()
        for (i in 0 until (raw.length as? Int ?: 0)) {
            val row = raw[i]
            toSegment(row)?.let { parsedSegments.add(it) }
        }
        parsedSegments.sortBy { it.firstEqid }
        segments = parsedSegments
        console.log("[osada] equipment-index.json loaded: ${parsedSegments.size} segments")
    }

    /** One `[countryFile, firstEqid, lastEqid]` row, or null when it is malformed. */
    private fun toSegment(row: dynamic): Segment? {
        val file = row[SEGMENT_FILE] as? Int
        val first = row[SEGMENT_FIRST] as? Int
        val last = row[SEGMENT_LAST] as? Int
        val usable = file != null && first != null && last != null && last >= first
        return if (usable) Segment(file, first, last) else null
    }

    /** The merged country FILE NUMBER holding [eqid], or null when it is unknown. */
    fun countryFileFor(eqid: Int): Int? {
        val known = segments ?: return null
        var low = 0
        var high = known.size - 1
        var found: Int? = null
        while (low <= high && found == null) {
            val mid = (low + high) / 2
            val segment = known[mid]
            when {
                eqid < segment.firstEqid -> high = mid - 1
                eqid > segment.lastEqid -> low = mid + 1
                else -> found = segment.countryFile
            }
        }
        return found
    }

    /** Every country file needed to resolve [eqids]; empty when the index is unavailable. */
    fun countryFilesFor(eqids: Iterable<Int>): Set<Int> {
        if (segments == null) return emptySet()
        val files = mutableSetOf<Int>()
        eqids.forEach { eqid -> if (eqid > 0) countryFileFor(eqid)?.let { files.add(it) } }
        return files
    }

    /** Test hook: installs an index without a fetch. `segments` are `[file, first, last]` triples. */
    internal fun installForTest(segments: List<List<Int>>) {
        this.segments =
            segments
                .map { Segment(it[SEGMENT_FILE], it[SEGMENT_FIRST], it[SEGMENT_LAST]) }
                .sortedBy { it.firstEqid }
    }

    /** Test hook: forgets the index, so the next [ensureLoaded] fetches again. */
    internal fun resetForTest() {
        segments = null
    }
}
