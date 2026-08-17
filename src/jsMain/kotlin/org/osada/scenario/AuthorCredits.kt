package org.osada.scenario

import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json

/**
 * Structured campaign/scenario authorship
 * (`docs/player-comfort-roadmap.md`, "Authorship metadata contract").
 *
 * Credits live in a hand-authored sidecar keyed by stable file id
 * (`resources/credits/authors.json`), NOT inside the description prose and NOT inside the generated
 * positional arrays in `campaignlist.js` / `scenariolist.js`. The OG importer is untouched, so a
 * re-import cannot erase a credit, and a description stays a pure player-facing synopsis.
 *
 * Absent credits are the normal case: the overwhelming majority of imported content carries no
 * attribution at all, and inventing one would be worse than showing none.
 */
object AuthorCredits {
    private const val PATH = "resources/credits/authors.json"
    private val httpSuccessRange = 200..299

    /** Authorship roles worth keeping apart. Unknown values from the sidecar fall back to
     *  [ORIGINAL] rather than being dropped -- a credit with a mislabelled role is still a credit. */
    enum class Role {
        /** Wrote the campaign/scenario in the first place. */
        ORIGINAL,

        /** Converted or imported it into another engine's format. */
        CONVERSION,

        /** Reworked balance, map or briefings for this game. */
        ADAPTATION,

        /** Translated its authored text. */
        TRANSLATION,
    }

    data class Credit(
        val name: String,
        val role: Role,
    )

    private var loaded = false
    private var byFile: Map<String, List<Credit>> = emptyMap()

    /** Credits for a campaign (`camp6.json`) or a scenario (`n_kiel.xml`) file id; empty when the
     *  sidecar names none, which is most content. */
    fun forFile(file: String?): List<Credit> {
        if (file.isNullOrBlank()) return emptyList()
        loadIfNeeded()
        return byFile[file].orEmpty()
    }

    fun hasCredits(file: String?): Boolean = forFile(file).isNotEmpty()

    // Synchronous, like TerrainEx's per-efile fetch: one small file, read once, and the selection
    // screens need it while they are being built.
    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        byFile = fetch()?.let(::parse) ?: emptyMap()
    }

    private fun fetch(): String? {
        val request = XMLHttpRequest()
        request.open("GET", PATH, false)
        request.send(null)
        val status = request.status.toInt()
        return if (status in httpSuccessRange || status == 0) request.responseText else null
    }

    /** Malformed input yields whatever entries it does have and never throws: a broken credits
     *  file must not stop the player from starting a campaign. */
    internal fun parse(text: String): Map<String, List<Credit>> {
        val entries = JSON.parse<Json>(text).asDynamic().entries
        if (entries == null || entries == undefined) return emptyMap()
        val result = mutableMapOf<String, List<Credit>>()
        js("Object.keys")(entries).unsafeCast<Array<String>>().forEach { file ->
            val credits = parseCredits(entries[file])
            if (credits.isNotEmpty()) result[file] = credits
        }
        return result
    }

    private fun parseCredits(value: dynamic): List<Credit> {
        val length = (value?.length as? Int) ?: return emptyList()
        val credits = mutableListOf<Credit>()
        for (index in 0 until length) {
            val item: dynamic = value[index]
            val name = (item?.name as? String)?.trim()
            val roleName = if (item == null || item == undefined) null else item.role as? String
            if (!name.isNullOrBlank()) credits += Credit(name, role(roleName))
        }
        return credits
    }

    private fun role(value: String?): Role =
        when (value?.lowercase()) {
            "conversion" -> Role.CONVERSION
            "adaptation" -> Role.ADAPTATION
            "translation" -> Role.TRANSLATION
            else -> Role.ORIGINAL
        }

    internal fun setForTest(entries: Map<String, List<Credit>>) {
        byFile = entries
        loaded = true
    }

    internal fun resetForTest() {
        byFile = emptyMap()
        loaded = false
    }
}
