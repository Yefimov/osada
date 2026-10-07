package org.osada.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** online-saves-and-player-profile.md §4: ancestry decides, clocks never do. */
class CloudSyncRulesTest {
    private val account = "a_me"

    private fun copy(
        revision: Int,
        hash: String,
        id: String = "p_one",
        deleted: Boolean = false,
        // Deliberately implausible clocks: a PC two days ahead must not win by timestamp (§8 case 5).
        savedAt: Double = 9.9e15,
    ) = CloudCopy(id, revision, hash, deleted, CopySummary(), savedAt, clientPlayedAt = null)

    private fun bound(
        revision: Int,
        hash: String,
        id: String = "p_one",
        accountId: String = account,
        epoch: Int = 1,
        paused: Boolean = false,
    ) = SyncBinding(accountId, epoch, id, revision, hash, paused = paused)

    private fun decide(
        binding: SyncBinding?,
        localHash: String,
        cloud: CloudCopy?,
        empty: Boolean = false,
        epoch: Int = 1,
    ) = CloudSyncRules.decide(account, epoch, binding, LocalCopy(localHash, empty), cloud)

    @Test
    fun sameContentEverywhereIsInSyncWithoutAnyAction() {
        assertEquals(SyncDecision.InSync, decide(bound(12, "h12"), "h12", copy(12, "h12")))
    }

    @Test
    fun theSamePayloadOnAnUnlinkedDeviceIsAdoptedWithoutADialog() {
        val decision = decide(null, "h5", copy(5, "h5"))
        assertIs<SyncDecision.Adopt>(decision)
    }

    @Test
    fun aDirtyDeviceWhoseBaseIsStillCurrentUploadsConditionally() {
        assertEquals(SyncDecision.Upload("p_one", 12), decide(bound(12, "h12"), "h13-local", copy(12, "h12")))
    }

    @Test
    fun aCleanDeviceTakesACloudDescendant() {
        val decision = decide(bound(12, "h12"), "h12", copy(15, "h15"))
        assertIs<SyncDecision.Download>(decision)
        assertEquals(15, decision.copy.revision)
    }

    @Test
    fun bothSidesMovedIsAChoiceNeverAnOverwrite() {
        // PC offline after revision 12, phone reached 15, PC saved more meanwhile (§8 case 3).
        val decision = decide(bound(12, "h12"), "h12-pc-edits", copy(15, "h15"))
        assertIs<SyncDecision.Choose>(decision)
        assertTrue(decision.sharedBase)
    }

    @Test
    fun guestProgressIsNeverSilentlyAttachedToAnAccount() {
        assertEquals(SyncDecision.OfferFirstUpload, decide(null, "guest", null))
        val decision = decide(null, "guest", copy(3, "account-copy"))
        assertIs<SyncDecision.Choose>(decision)
        assertTrue(!decision.sharedBase)
    }

    @Test
    fun anotherAccountsLinkIsIgnored() {
        // Logout/account switch: this profile was linked to someone else (§8 case 10).
        val decision = decide(bound(4, "h4", accountId = "a_other"), "h4", null)
        assertEquals(SyncDecision.OfferFirstUpload, decision)
    }

    @Test
    fun anEmptyDeviceIsOfferedTheOnlineCopy() {
        assertIs<SyncDecision.OfferDownload>(decide(null, "empty", copy(2, "h2"), empty = true))
        assertEquals(SyncDecision.Empty, decide(null, "empty", null, empty = true))
    }

    @Test
    fun anExpiredEpochOrDeletedCopyCannotBeRevivedSilently() {
        assertEquals(
            SyncDecision.CloudGone("epoch-expired"),
            decide(bound(9, "h9", epoch = 1), "h10", copy(9, "h9"), epoch = 2),
        )
        assertEquals(
            SyncDecision.CloudGone("run-deleted"),
            decide(bound(9, "h9"), "h10", copy(10, "-", deleted = true)),
        )
        assertEquals(SyncDecision.CloudGone("missing"), decide(bound(9, "h9"), "h10", null))
    }

    @Test
    fun aDifferentLiveCopyFromAnotherDeviceIsAChoice() {
        val decision = decide(bound(9, "h9", id = "p_old"), "h9", copy(1, "fresh", id = "p_new"))
        assertIs<SyncDecision.Choose>(decision)
    }

    @Test
    fun reopeningTheGameIsNotMistakenForPlayingIt() {
        // A restore rewrites the standalone battle's savedAt: new bytes, same content key.
        val binding = SyncBinding(account, 1, "p_one", 4, "bytes-at-4", ackContentKey = "content-4")
        val reopened = LocalCopy(hash = "bytes-after-reload", empty = false, contentKey = "content-4")
        assertEquals(SyncDecision.InSync, CloudSyncRules.decide(account, 1, binding, reopened, copy(4, "bytes-at-4")))
        assertIs<SyncDecision.Download>(CloudSyncRules.decide(account, 1, binding, reopened, copy(5, "bytes-at-5")))
        val played = reopened.copy(contentKey = "content-new")
        val decision = CloudSyncRules.decide(account, 1, binding, played, copy(4, "bytes-at-4"))
        assertEquals(SyncDecision.Upload("p_one", 4), decision)
    }

    @Test
    fun decideLaterHoldsEveryAutomaticAction() {
        val decision = decide(bound(12, "h12", paused = true), "h13", copy(12, "h12"))
        assertIs<SyncDecision.Choose>(decision)
        assertTrue(decision.paused)
    }

    @Test
    fun manifestPrefersTheLinkedCopyAndSkipsTombstonesOtherwise() {
        val manifest =
            CloudManifest(
                dataEpoch = 1,
                usedBytes = 0.0,
                quotaBytes = 1.0,
                uploadsEnabled = true,
                copies =
                    listOf(
                        copy(3, "x", id = "p_dead", deleted = true),
                        copy(2, "y", id = "p_live"),
                        copy(1, "z", id = "p_mine"),
                    ),
            )
        assertEquals("p_mine", manifest.profileCopy(bound(1, "z", id = "p_mine"))?.runInstanceId)
        assertEquals("p_live", manifest.profileCopy(null)?.runInstanceId)
    }
}
