package org.osada.ui

import kotlinx.browser.window
import org.osada.campaign.BriefingDynamic
import kotlin.js.json

/**
 * The cross-campaign Hall of Fame (design brief §14.6) — the only leader surface that persists
 * *between* campaigns, so it lives in `localStorage` rather than the per-campaign save. It collects
 * the officers worth remembering (heroes who reached renown, authored legendaries, and the fallen)
 * as each campaign ends. It is a collection screen, never the current campaign's management screen
 * — that is the in-campaign roster (§14.3).
 *
 * Storage is defensive: access is wrapped (private-mode browsers throw on `localStorage`), corrupt
 * data degrades to an empty hall, and entries de-duplicate on name + campaign so replaying a
 * campaign does not stack copies.
 */
internal object HallOfFame {
    private const val KEY = "osada_hall_of_fame"

    data class Entry(
        val name: String,
        val rank: String,
        val renown: String,
        val potential: String,
        val status: String,
        val campaign: String,
    )

    fun all(): List<Entry> {
        val raw = read() ?: return emptyList()
        return runCatching {
            BriefingDynamic.mapArray(js("JSON.parse")(raw)) { readEntry(it) }
        }.getOrElse { emptyList() }
    }

    fun isNotEmpty(): Boolean = all().isNotEmpty()

    /** Records [entries] not already present (by name + campaign). No-op when storage is unavailable. */
    fun harvest(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val merged = all().toMutableList()
        entries.forEach { entry ->
            if (merged.none { it.name == entry.name && it.campaign == entry.campaign }) merged += entry
        }
        write(merged)
    }

    fun clear() = runCatching { storage()?.removeItem(KEY) }.let {}

    private fun write(entries: List<Entry>) {
        runCatching {
            val json = js("JSON.stringify")(entries.map(::serializeEntry).toTypedArray()) as String
            storage()?.setItem(KEY, json)
        }
    }

    private fun read(): String? = runCatching { storage()?.getItem(KEY) }.getOrNull()

    private fun storage() = runCatching { window.localStorage }.getOrNull()

    private fun serializeEntry(entry: Entry): dynamic =
        json(
            Pair("name", entry.name),
            Pair("rank", entry.rank),
            Pair("renown", entry.renown),
            Pair("potential", entry.potential),
            Pair("status", entry.status),
            Pair("campaign", entry.campaign),
        )

    private fun readEntry(item: dynamic): Entry? {
        val name = BriefingDynamic.str(item?.name)?.takeIf { it.isNotBlank() } ?: return null
        return Entry(
            name = name,
            rank = BriefingDynamic.str(item?.rank).orEmpty(),
            renown = BriefingDynamic.str(item?.renown).orEmpty(),
            potential = BriefingDynamic.str(item?.potential).orEmpty(),
            status = BriefingDynamic.str(item?.status).orEmpty(),
            campaign = BriefingDynamic.str(item?.campaign).orEmpty(),
        )
    }
}
