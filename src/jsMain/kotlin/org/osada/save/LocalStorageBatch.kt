package org.osada.save

import org.osada.storage.localStorage
import kotlin.js.json

/** Undo journal for infrequent, multi-key file imports. Ordinary autosaves do not use it.
 * The journal must fit BEFORE the first change. Interrupted imports are rolled back at bootstrap.
 * This is a single-browser-tab protocol, not a cross-device or concurrent-tab transaction. */
@Suppress("TooGenericExceptionCaught")
internal class LocalStorageBatch(
    private val read: (String) -> String? = { localStorage.getItem(it) },
    private val write: (String, String) -> Unit = { key, value -> localStorage.setItem(key, value) },
    private val remove: (String) -> Unit = { localStorage.removeItem(it) },
) {
    fun apply(changes: Map<String, String?>): SaveResult {
        var journalWritten = false
        return try {
            check(read(JOURNAL_KEY) == null) { "An interrupted import must be recovered first" }
            val previous = changes.keys.associateWith(read)
            val rows = previous.map { (key, value) -> json("key" to key, "value" to value) }.toTypedArray()
            val journal = JSON.stringify(rows)
            write(JOURNAL_KEY, journal)
            journalWritten = true
            check(read(JOURNAL_KEY) == journal) { "Import journal readback failed" }
            changes.forEach { (key, value) ->
                if (value == null) remove(key) else write(key, value)
                check(read(key) == value) { "Import readback failed: $key" }
            }
            remove(JOURNAL_KEY)
            SaveResult.success()
        } catch (error: Throwable) {
            if (journalWritten && !recover().isSuccess) {
                return SaveResult(SaveResultKind.STORAGE_UNAVAILABLE, "Import recovery pending; reload before playing")
            }
            failure(error)
        }
    }

    fun recover(): SaveResult =
        try {
            val raw = read(JOURNAL_KEY)
            if (raw != null) {
                val rows = JSON.parse<Array<dynamic>>(raw)
                val previous =
                    rows.associate { row ->
                        val key = row.key as String
                        require(key.startsWith("osada-") && key != JOURNAL_KEY)
                        key to (row.value as? String)
                    }
                // Release the imported values first, so restoring the old profile cannot require
                // more space than the journal plus the old profile which already fitted.
                previous.keys.forEach(remove)
                previous.forEach { (key, value) -> if (value != null) write(key, value) }
                check(previous.all { (key, value) -> read(key) == value })
                remove(JOURNAL_KEY)
            }
            SaveResult.success()
        } catch (error: Throwable) {
            failure(error)
        }

    /** True/false when storage answered; null when it cannot even be read (denied/disabled). */
    @Suppress("SwallowedException")
    fun hasPendingJournal(): Boolean? =
        try {
            read(JOURNAL_KEY) != null
        } catch (error: Throwable) {
            null
        }

    /**
     * Bootstrap decision after a failed [recover]. Startup stops only when a journal is known to be
     * waiting: playing on would autosave over keys the journal still has to restore. When storage
     * cannot be read at all, guest play starts without persistence instead -- nothing can be
     * written over the journal in that state, and the next boot with storage access recovers it.
     */
    fun mustBlockStartup(recovery: SaveResult): Boolean = !recovery.isSuccess && hasPendingJournal() == true

    private fun failure(error: Throwable): SaveResult =
        SaveResult(
            if (error.asDynamic().name == "QuotaExceededError") {
                SaveResultKind.QUOTA_EXCEEDED
            } else {
                SaveResultKind.STORAGE_UNAVAILABLE
            },
            error.message,
        )

    companion object {
        internal const val JOURNAL_KEY = "osada-profile-import-journal-v1"
    }
}
