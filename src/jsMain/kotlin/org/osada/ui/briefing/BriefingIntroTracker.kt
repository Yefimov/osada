package org.osada.ui.briefing

import kotlinx.browser.localStorage

/**
 * Remembers which campaign scenarios have already played their intro ceremony (dialogue +
 * briefing) within the CURRENT campaign run, so a defeat→retry or replay of the same scenario
 * goes straight to gameplay. Persisted in localStorage (NOT part of the save-game format) so
 * the fast-path survives a page reload; [reset] is called whenever a new campaign starts.
 *
 * Storage failures (private mode, quota) degrade to "ceremony every time" — never crash.
 */
internal object BriefingIntroTracker {
    private const val STORAGE_KEY = "osada-briefing-seen"

    fun isSeen(
        campaignFile: String,
        scenarioFile: String,
    ): Boolean {
        if (campaignFile.isBlank() || scenarioFile.isBlank()) return false
        return readSeen(campaignFile, scenarioFile)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readSeen(
        campaignFile: String,
        scenarioFile: String,
    ): Boolean =
        try {
            val record = read()
            record != null && record.campaign == campaignFile && seenList(record).contains(scenarioFile)
        } catch (e: Throwable) {
            console.warn("[OSADA] briefing intro tracker read failed", e)
            false
        }

    @Suppress("TooGenericExceptionCaught")
    fun markSeen(
        campaignFile: String,
        scenarioFile: String,
    ) {
        if (campaignFile.isBlank() || scenarioFile.isBlank()) return
        try {
            val record = read()
            val seen =
                if (record != null && record.campaign == campaignFile) {
                    seenList(record).toMutableList()
                } else {
                    // A different campaign started without reset() (defensive): drop its record.
                    mutableListOf()
                }
            if (scenarioFile !in seen) seen.add(scenarioFile)
            val payload = js("{}")
            payload.campaign = campaignFile
            payload.seen = seen.toTypedArray()
            localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
        } catch (e: Throwable) {
            console.warn("[OSADA] briefing intro tracker write failed", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun reset() {
        try {
            localStorage.removeItem(STORAGE_KEY)
        } catch (e: Throwable) {
            console.warn("[OSADA] briefing intro tracker reset failed", e)
        }
    }

    private fun read(): dynamic {
        val raw = localStorage.getItem(STORAGE_KEY) ?: return null
        return JSON.parse(raw)
    }

    private fun seenList(record: dynamic): List<String> {
        val seen = record.seen
        if (seen == null || seen == undefined || !BriefingParsingUtils.isArray(seen)) return emptyList()
        return seen.unsafeCast<Array<dynamic>>().filterIsInstance<String>()
    }
}
