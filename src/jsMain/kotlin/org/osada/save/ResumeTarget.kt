package org.osada.save

import org.osada.VERSION
import org.osada.storage.localStorage

/**
 * Which save boot/Continue should open next, when recency alone would pick the wrong one.
 *
 * A file import keeps the file's honest timestamps (an old raw battle file has none at all), so an
 * unrelated newer local campaign would win [org.osada.GameStatePersistence.restore]'s recency
 * comparison and the battle just imported would not open. The import writes this pointer in the
 * same [LocalStorageBatch] as the imported data, so a rolled-back import also rolls it back.
 *
 * It is not consumed by reading. A successful restore ends with a save (`Game.setupGameState`),
 * which stamps the imported game as the newest and clears this pointer -- from then on ordinary
 * recency picks the same game. If that save fails (quota, denied storage), the pointer survives so
 * the next boot still opens what was imported rather than older local progress.
 */
internal object ResumeTarget {
    private val version = VERSION.split('.').take(2).joinToString(".")
    internal val key = "osada-resume-target-$version"
    private const val STANDALONE = "standalone"
    private const val CAMPAIGN_PREFIX = "campaign:"

    sealed interface Target

    data object Standalone : Target

    data class Campaign(
        val campaignRunId: String,
    ) : Target

    fun encode(target: Target): String =
        when (target) {
            Standalone -> STANDALONE
            is Campaign -> CAMPAIGN_PREFIX + target.campaignRunId
        }

    fun decode(raw: String?): Target? =
        when {
            raw == STANDALONE -> Standalone
            raw != null && raw.startsWith(CAMPAIGN_PREFIX) && raw.length > CAMPAIGN_PREFIX.length ->
                Campaign(raw.removePrefix(CAMPAIGN_PREFIX))
            else -> null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun read(): Target? =
        try {
            decode(localStorage.getItem(key))
        } catch (error: Throwable) {
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun clear() {
        try {
            localStorage.removeItem(key)
        } catch (error: Throwable) {
            // Storage denied: nothing was persisted, so there is nothing to clear.
        }
    }
}
