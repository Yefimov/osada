package org.osada.save

import kotlinx.browser.localStorage
import org.osada.VERSION
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the commit/rotation/eviction protocol from `docs/design/save-recovery.md` sections 4/6
 *  against the real (browser) `localStorage`, since this store is a thin synchronous wrapper over
 *  it -- an in-memory fake would not exercise the actual API this adapter depends on. */
class LocalStorageSaveSnapshotStoreTest {
    private val store = LocalStorageSaveSnapshotStore()
    private val runA = "test-camp-a.json"
    private val runB = "test-camp-b.json"

    @BeforeTest
    fun clearAll() {
        wipeTestKeys()
    }

    @AfterTest
    fun cleanup() {
        wipeTestKeys()
    }

    private fun wipeTestKeys() {
        store.deleteCampaignRun(runA)
        store.deleteCampaignRun(runB)
    }

    private fun snapshot(
        campaignRunId: String,
        turn: Int = 1,
        kind: String = "autosave",
    ) = SaveSnapshot(
        id = "id-$campaignRunId-$turn-$kind",
        campaignRunId = campaignRunId,
        kind = kind,
        createdAt = turn.toDouble() * 1000.0,
        gameVersion = "test",
        saveFormat = 4,
        scenarioFile = "test.xml",
        scenarioName = "Test Scenario",
        turn = turn,
        phase = "playerTurn",
        campaignFile = campaignRunId,
        campaignScenario = 0,
        payload =
            """{"fmt":4,"scenario":{"file":"test.xml","turn":$turn},"players":[],""" +
                """"campaign":{"id":0,"file":"$campaignRunId","scenario":0}}""",
    )

    @Test
    fun commitAutosaveSucceedsAndReadsBack() {
        val result = store.commitAutosave(runA, snapshot(runA))
        assertTrue(result.isSuccess, "commit should succeed: $result")
        val current = store.readCurrent(runA)
        assertNotNull(current)
        assertEquals(runA, current.campaignRunId)
        assertEquals(1, current.turn)
    }

    @Test
    fun secondCommitPromotesPreviousCurrentToRecovery() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2))

        val current = store.readCurrent(runA)
        val recovery = store.readRecovery(runA)
        assertEquals(2, current?.turn, "current should be the latest commit")
        assertEquals(1, recovery?.turn, "recovery should be the generation current just replaced")
    }

    @Test
    fun thirdCommitKeepsOnlyOneRecoveryGeneration() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2))
        store.commitAutosave(runA, snapshot(runA, turn = 3))

        assertEquals(3, store.readCurrent(runA)?.turn)
        assertEquals(
            2,
            store.readRecovery(runA)?.turn,
            "recovery must be the IMMEDIATELY previous generation, not turn 1",
        )
    }

    @Test
    fun listCampaignRunsOrdersByMostRecentlyPlayedFirst() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runB, snapshot(runB, turn = 5))

        val runs = store.listCampaignRuns().filter { it.campaignRunId == runA || it.campaignRunId == runB }
        assertEquals(listOf(runB, runA), runs.map { it.campaignRunId }, "runB was committed with a later createdAt")
    }

    @Test
    fun deleteCampaignRunRemovesBothGenerationsAndIndexRow() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2))
        assertNotNull(store.readCurrent(runA))
        assertNotNull(store.readRecovery(runA))

        val result = store.deleteCampaignRun(runA)
        assertTrue(result.isSuccess)
        assertNull(store.readCurrent(runA))
        assertNull(store.readRecovery(runA))
        assertTrue(store.listCampaignRuns().none { it.campaignRunId == runA })
    }

    @Test
    fun deletingOneRunNeverTouchesAnother() {
        store.commitAutosave(runA, snapshot(runA))
        store.commitAutosave(runB, snapshot(runB))

        store.deleteCampaignRun(runA)

        assertNull(store.readCurrent(runA))
        assertNotNull(store.readCurrent(runB), "deleting run A must not remove run B")
    }

    @Test
    fun evictOldestRecoveryNeverTouchesTheActiveRun() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2)) // runA now has a recovery generation
        store.commitAutosave(runB, snapshot(runB, turn = 1))
        store.commitAutosave(runB, snapshot(runB, turn = 2)) // runB now has a recovery generation too

        // runA is excluded from candidacy purely by being the active run -- it would otherwise be
        // an equally valid pick, so this only proves the active-run guard, not a timing detail.
        val evicted = store.evictOldestRecovery(activeCampaignRunId = runA)
        assertTrue(evicted, "should have evicted something")
        assertNotNull(store.readCurrent(runA), "active run's current generation must survive")
        assertNotNull(store.readRecovery(runA), "active run's recovery must be protected from eviction")
        assertNull(store.readRecovery(runB), "the non-active run's recovery is the only eligible target")
    }

    @Test
    fun replaceCampaignRunOverwritesOnlyThatRun() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runB, snapshot(runB, turn = 1))

        val bundle =
            CampaignRunBundle(
                metadata =
                    CampaignRunMetadata(
                        campaignRunId = runA,
                        campaignFile = runA,
                        campaignName = "Imported A",
                        scenarioName = "Imported Scenario",
                        campaignScenario = 2,
                        phase = "playerTurn",
                        lastPlayedAt = 9999.0,
                        completed = false,
                    ),
                current = snapshot(runA, turn = 99),
                recovery = null,
            )
        val result = store.replaceCampaignRun(bundle)
        assertTrue(result.isSuccess)
        assertEquals(99, store.readCurrent(runA)?.turn)
        assertEquals(1, store.readCurrent(runB)?.turn, "importing run A must not touch run B")
    }

    @Test
    fun pruneOrphansRemovesIndexRowsWithNoCurrentGeneration() {
        store.commitAutosave(runA, snapshot(runA))
        // Simulate an interrupted delete: remove the raw current key but leave the index row,
        // exactly the failure mode pruneOrphans exists to clean up (design doc sec 6 step 1).
        localStorage.removeItem("osada-save-run-${majorVersionForTest()}-$runA-current")
        assertTrue(
            store.listCampaignRuns().any { it.campaignRunId == runA },
            "orphan row should still be indexed before pruning",
        )

        store.pruneOrphans()

        assertTrue(store.listCampaignRuns().none { it.campaignRunId == runA }, "orphan row must be gone after pruning")
    }

    /** The reverse orphan the KDoc always promised but the implementation never handled: a
     *  generation key whose index row is gone holds ~130 KB of quota that nothing can ever list,
     *  restore or evict. */
    @Test
    fun pruneOrphansRemovesGenerationKeysWithNoIndexRow() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2)) // gives runA a recovery generation too
        val currentKey = "osada-save-run-${majorVersionForTest()}-$runA-current"
        val recoveryKey = "osada-save-run-${majorVersionForTest()}-$runA-recovery"
        assertNotNull(localStorage.getItem(currentKey))
        assertNotNull(localStorage.getItem(recoveryKey))

        // Simulate an interrupted delete from the other side: index row dropped, payloads left.
        store.deleteCampaignRun(runA)
        localStorage.setItem(currentKey, "{\"id\":\"x\",\"campaignRunId\":\"$runA\",\"payload\":\"{}\"}")
        localStorage.setItem(recoveryKey, "{\"id\":\"y\",\"campaignRunId\":\"$runA\",\"payload\":\"{}\"}")

        store.pruneOrphans()

        assertNull(localStorage.getItem(currentKey), "unreferenced current generation must be pruned")
        assertNull(localStorage.getItem(recoveryKey), "unreferenced recovery generation must be pruned")
    }

    @Test
    fun pruneOrphansKeepsGenerationsThatAreStillIndexed() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.commitAutosave(runA, snapshot(runA, turn = 2))

        store.pruneOrphans()

        assertEquals(2, store.readCurrent(runA)?.turn, "a live run's current generation must survive pruning")
        assertEquals(1, store.readRecovery(runA)?.turn, "a live run's recovery generation must survive pruning")
    }

    @Test
    fun markCompletedFlagsTheRunAndRecordsItsOutcome() {
        store.commitAutosave(runA, snapshot(runA))
        val before = store.listCampaignRuns().single { it.campaignRunId == runA }
        assertTrue(before.completed.not(), "a freshly committed run is in progress, not completed")

        val result = store.markCompleted(runA, "briliant")

        assertTrue(result.isSuccess, "markCompleted should succeed: $result")
        val row = store.listCampaignRuns().single { it.campaignRunId == runA }
        assertTrue(row.completed, "run must read back as completed")
        assertEquals("briliant", row.outcome)
    }

    /** A campaign lost at its final scenario is finished, but it is NOT one the player completed --
     *  the register needs the outcome to say so rather than printing "Completed"/"Пройдена". */
    @Test
    fun markCompletedPreservesALosingOutcome() {
        store.commitAutosave(runA, snapshot(runA))

        store.markCompleted(runA, "lose")

        val row = store.listCampaignRuns().single { it.campaignRunId == runA }
        assertTrue(row.completed)
        assertEquals("lose", row.outcome)
    }

    @Test
    fun markCompletedReportsNotFoundForAnUnknownRun() {
        assertEquals(SaveResultKind.NOT_FOUND, store.markCompleted("no-such-run.json", "victory").kind)
    }

    /** Playing on after a campaign was marked finished (a replay writes fresh autosaves) must clear
     *  the finished state again, otherwise the row would claim completion mid-run. */
    @Test
    fun aLaterAutosaveClearsTheCompletedFlag() {
        store.commitAutosave(runA, snapshot(runA, turn = 1))
        store.markCompleted(runA, "victory")

        store.commitAutosave(runA, snapshot(runA, turn = 2))

        val row = store.listCampaignRuns().single { it.campaignRunId == runA }
        assertTrue(row.completed.not(), "an in-progress run must not report itself completed")
        assertEquals("", row.outcome)
    }

    private fun majorVersionForTest(): String = VERSION.split(".").take(2).joinToString(".")
}
