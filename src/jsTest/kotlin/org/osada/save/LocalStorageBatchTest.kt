package org.osada.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalStorageBatchTest {
    @Test
    fun everyWriteFailureRestoresTheEntirePreviousProfile() {
        for (failAt in 1..4) {
            val initial = mapOf("osada-a" to "old campaign", "osada-heroes" to "old heroes")
            val data = initial.toMutableMap()
            var writes = 0
            val batch =
                LocalStorageBatch(data::get, { key, value ->
                    if (++writes == failAt) error("injected quota/storage failure")
                    data[key] = value
                }, { data.remove(it) })
            val result =
                batch.apply(
                    mapOf(
                        "osada-a" to "new campaign",
                        "osada-heroes" to "new heroes",
                        "osada-new" to "new run",
                    ),
                )
            assertFalse(result.isSuccess, "failure at $failAt must not report success")
            assertEquals(initial, data, "all domains must roll back, failure at $failAt")
        }
    }

    @Test
    fun successfulCommitRemovesJournalAndHonoursDeletions() {
        val data = mutableMapOf("osada-a" to "old", "osada-removed" to "old")
        val batch = LocalStorageBatch(data::get, { k, v -> data[k] = v }, { data.remove(it) })
        assertTrue(batch.apply(mapOf("osada-a" to "new", "osada-removed" to null)).isSuccess)
        assertEquals(mapOf("osada-a" to "new"), data)
    }

    @Test
    fun bootstrapRecoversAnInterruptedImportIdempotently() {
        val data =
            mutableMapOf(
                "osada-a" to "partially imported",
                "osada-new" to "partially imported",
                LocalStorageBatch.JOURNAL_KEY to
                    """[{"key":"osada-a","value":"original"},{"key":"osada-new","value":null}]""",
            )
        val batch = LocalStorageBatch(data::get, { k, v -> data[k] = v }, { data.remove(it) })
        assertTrue(batch.recover().isSuccess)
        assertTrue(batch.recover().isSuccess)
        assertEquals(mapOf("osada-a" to "original"), data)
    }

    @Test
    fun readbackMismatchCannotCommit() {
        val data = mutableMapOf("osada-a" to "old")
        val batch = LocalStorageBatch(data::get, { k, v -> if (v != "new") data[k] = v }, { data.remove(it) })
        assertFalse(batch.apply(mapOf("osada-a" to "new")).isSuccess)
        assertEquals(mapOf("osada-a" to "old"), data)
    }

    @Test
    fun deniedStorageLetsGuestPlayStartWithoutAbandoningAJournal() {
        val denied =
            LocalStorageBatch(
                { error("SecurityError: storage denied") },
                { _, _ -> error("denied") },
                { error("denied") },
            )
        val recovery = denied.recover()
        assertFalse(recovery.isSuccess)
        assertEquals(null, denied.hasPendingJournal(), "an unreadable store cannot say whether a journal exists")
        assertFalse(denied.mustBlockStartup(recovery), "nothing can overwrite the journal while storage is denied")
    }

    @Test
    fun aKnownJournalThatCannotBeRestoredBlocksStartup() {
        val data = mutableMapOf(LocalStorageBatch.JOURNAL_KEY to """[{"key":"osada-a","value":"original"}]""")
        val batch = LocalStorageBatch(data::get, { _, _ -> error("QuotaExceededError") }, { data.remove(it) })
        val recovery = batch.recover()
        assertFalse(recovery.isSuccess)
        assertTrue(batch.mustBlockStartup(recovery), "autosaves must not run over a journal still owed a rollback")
    }

    @Test
    fun noJournalNeverBlocksStartup() {
        val data = mutableMapOf<String, String>()
        val batch = LocalStorageBatch(data::get, { k, v -> data[k] = v }, { data.remove(it) })
        assertFalse(batch.mustBlockStartup(batch.recover()))
    }
}
