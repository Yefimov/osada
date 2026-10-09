package org.osada

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.osada.model.Equipment
import org.osada.model.getPlayers
import org.osada.model.resetEquipment
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.storage.BrowserStorage
import org.osada.storage.IndexedDbProfileStore
import org.osada.ui.ProfileBackupCodec
import kotlin.js.Promise
import kotlin.js.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Restart Mission's opening checkpoint through the real restore path rather than a manual
 * `capture()` on an already-running battle: a save written while the opening briefing was up must
 * come back as that opening (and be checkpointed when it ends), a refused checkpoint write must be
 * reported, and retention must follow the campaign runs. The browser half -- reload with the
 * briefing on screen, BEGIN, reload again -- is `scripts/verify/restart-checkpoint-probe.mjs`.
 */
class MissionRestartCheckpointTest {
    private var previousGame: Game? = null
    private var previousStorage = emptyMap<String, String>()

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        previousStorage =
            (0 until localStorage.length)
                .mapNotNull { localStorage.key(it) }
                .associateWith { localStorage.getItem(it)!! }
        localStorage.clear()
        Equipment.resetEquipment()
        Equipment.asyncLoad = false
        js(
            """
            if (typeof window.scenariolist === 'undefined') {
                window.scenariolist = [
                    ['Test Theater'],
                    ['bizerte.xml','Bizerte','Bizerte scenario',[],[],'eqp-adlerkorps']
                ];
            }
        """,
        )
    }

    @AfterTest
    fun cleanup() {
        BrowserStorage.simulateDeniedForTests(false)
        localStorage.clear()
        previousStorage.forEach { (key, value) -> localStorage.setItem(key, value) }
        GameHolder.instance = previousGame
    }

    private fun fixture(): String {
        val parsed = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        parsed.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        return JSON.stringify(parsed)
    }

    /** Restores [data] into a fresh game and runs [check] once the restore has completed. */
    private fun restored(
        data: String = fixture(),
        check: (Game) -> Unit,
    ): Promise<Unit> {
        val game = Game()
        game.state = GameState(game)
        return Promise { resolve, reject ->
            val loaded =
                game.state?.restoreFromString(data) {
                    try {
                        check(game)
                        resolve(Unit)
                    } catch (error: Throwable) {
                        reject(error)
                    }
                }
            if (loaded != true) reject(Throwable("restoreFromString returned false"))
        }
    }

    private fun storedOpening(): String? = localStorage.getItem(MissionCheckpointStore.slotKey(null))

    @Test
    fun aSaveWrittenDuringTheBriefingComesBackAsThatOpening(): Promise<Unit> {
        var duringBriefing = ""
        return restored { first ->
            // What onScenarioLoadFinished's autosave holds: the battle, before its briefing ends.
            first.openingPending = true
            duringBriefing = GameStateSerializer.exportGameState(first)
            assertEquals(true, JSON.parse<dynamic>(duringBriefing)[MissionRestartCheckpoint.OPENING_PENDING_KEY])
            first.missionRestartCheckpoint.clear()
        }.then {
            restored(duringBriefing) { game ->
                assertTrue(game.openingPending, "the restored battle must still be in its opening")
                assertTrue(game.openingReplay, "its briefing replays in full")
                assertTrue(game.takeScenarioBriefingEnabled(), "the briefing is shown again")
                assertNull(storedOpening(), "no checkpoint before the opening ends")
                assertFalse(game.missionRestartCheckpoint.isAvailable())
                // The release step (UI.releaseToBattle): marker off, then the capture.
                game.openingPending = false
                assertEquals(MissionRestartCheckpoint.Outcome.SAVED, game.missionRestartCheckpoint.capture())
                val opening = assertNotNull(storedOpening())
                assertNull(JSON.parse<dynamic>(opening)[MissionRestartCheckpoint.OPENING_PENDING_KEY])
                assertTrue(game.missionRestartCheckpoint.isAvailable())
            }
        }.then { }
    }

    @Test
    fun aSaveWrittenAfterTheOpeningResumesWithoutItsBriefing(): Promise<Unit> =
        restored { game ->
            assertFalse(game.openingPending)
            assertFalse(game.openingReplay)
            assertFalse(game.takeScenarioBriefingEnabled(), "a battle in progress must not replay its briefing")
            val saved = JSON.parse<dynamic>(GameStateSerializer.exportGameState(game))
            assertNull(saved[MissionRestartCheckpoint.OPENING_PENDING_KEY])
        }

    @Test
    fun aPayloadStillInItsBriefingIsNeverOfferedAsTheOpening(): Promise<Unit> =
        restored { game ->
            game.missionRestartCheckpoint.clear()
            val pending = JSON.parse<dynamic>(GameStateSerializer.exportGameState(game))
            pending[MissionRestartCheckpoint.OPENING_PENDING_KEY] = true
            game.missionRestartCheckpoint.adopt(JSON.stringify(pending))
            assertFalse(game.missionRestartCheckpoint.isAvailable())
            assertNull(game.missionRestartCheckpoint.exportForCurrent())
        }

    @Test
    fun aRefusedCheckpointWriteIsReportedAndKeptForThisPage(): Promise<Unit> =
        restored { game ->
            game.missionRestartCheckpoint.clear()
            val reasons = mutableListOf<String>()
            game.missionRestartCheckpoint.onFailure = { reasons += it }
            // Every write now throws, as a full or fenced store does.
            BrowserStorage.suspendWrites()
            assertEquals(MissionRestartCheckpoint.Outcome.FAILED, game.missionRestartCheckpoint.capture())
            assertEquals(1, reasons.size, "the failure must reach the player, not only the console")
            BrowserStorage.simulateDeniedForTests(false)
            assertNull(storedOpening())
            assertTrue(game.missionRestartCheckpoint.isAvailable(), "Restart still works in this page")
            assertNotNull(game.missionRestartCheckpoint.exportForCurrent(), "and a backup made now carries it")
        }

    @Test
    fun aSessionOnlyCheckpointIsReported(): Promise<Unit> =
        restored { game ->
            game.missionRestartCheckpoint.clear()
            val reasons = mutableListOf<String>()
            game.missionRestartCheckpoint.onFailure = { reasons += it }
            BrowserStorage.simulateDeniedForTests(true)
            assertEquals(MissionRestartCheckpoint.Outcome.FAILED, game.missionRestartCheckpoint.capture())
            assertEquals(listOf(org.osada.save.SaveStatus.SESSION_ONLY), reasons)
        }

    @Test
    fun checkpointsAreKeptForEveryRetainedCampaignRunAndDroppedWithIt() {
        val store = LocalStorageSaveSnapshotStore()
        val files = (1..6).map { "run-$it.json" }
        val payloads =
            files.associateWith { file ->
                val raw = JSON.parse<dynamic>(fixture())
                raw.campaign = json("file" to file, "scenario" to 0)
                JSON.stringify(raw)
            }
        files.forEach { file ->
            assertTrue(store.replaceCampaignRun(ProfileBackupCodec.runFromSave(payloads.getValue(file), 1.0)).isSuccess)
            assertNull(MissionCheckpointStore.write(file, payloads.getValue(file)))
        }
        // Six campaigns: the old four-slot cap dropped the two captured first.
        files.forEach { assertEquals(payloads[it], MissionCheckpointStore.read(it), "$it lost its opening") }
        assertTrue(store.deleteCampaignRun("run-1.json").isSuccess)
        assertNull(MissionCheckpointStore.write("run-6.json", payloads.getValue("run-6.json")))
        assertNull(
            localStorage.getItem(MissionCheckpointStore.slotKey("run-1.json")),
            "a cleared run's opening goes with it",
        )
        assertEquals(payloads["run-2.json"], MissionCheckpointStore.read("run-2.json"))
    }

    private fun Game.firstPlayer() = scenario!!.map.getPlayers()[0]

    private fun openingPrestige(): Int? = storedOpening()?.let { JSON.parse<dynamic>(it).players[0].prestige as? Int }

    @Test
    fun aReplayedOpeningReplacesTheCheckpointOfAnEarlierCompletion(): Promise<Unit> {
        var duringBriefing = ""
        return restored { first ->
            first.openingPending = true
            duringBriefing = GameStateSerializer.exportGameState(first)
            first.missionRestartCheckpoint.clear()
        }.then {
            restored(duringBriefing) { game ->
                // First completion: one briefing choice (its effect stands in as prestige 111).
                game.firstPlayer().prestige = 111
                assertEquals(MissionRestartCheckpoint.Outcome.SAVED, game.completeOpening())
                assertEquals(111, openingPrestige())
            }
        }.then {
            // Its post-BEGIN autosave is lost: recovery brings back the save written before BEGIN.
            restored(duringBriefing) { game ->
                assertTrue(game.openingReplay)
                game.firstPlayer().prestige = 222
                assertEquals(MissionRestartCheckpoint.Outcome.SAVED, game.completeOpening())
                assertEquals(222, openingPrestige(), "Restart must reproduce the opening just completed")
                // Ordinary play after that keeps the checkpoint immutable.
                game.firstPlayer().prestige = 333
                assertEquals(MissionRestartCheckpoint.Outcome.ALREADY_TAKEN, game.completeOpening())
                assertEquals(222, openingPrestige())
            }
        }.then { }
    }

    /**
     * IndexedDB refuses the checkpoint for space asynchronously, and keeps refusing while another
     * run's recovery generation exists. Relief must free that generation and land the checkpoint.
     */
    @Test
    fun anAsynchronousQuotaRefusalFreesARecoveryGenerationAndRetries(): Promise<Unit> =
        quotaScenario(withRecovery = true).then { outcome ->
            assertEquals(emptyList<String>(), outcome.failures)
            assertFalse(outcome.recoveryLeft, "the other run's recovery generation was the price")
            assertEquals(quotaPayload, outcome.stored)
        }

    @Test
    fun anAsynchronousQuotaRefusalWithNothingToFreeIsReported(): Promise<Unit> =
        quotaScenario(withRecovery = false).then { outcome ->
            assertEquals(listOf("QuotaExceededError"), outcome.failures)
        }

    private class QuotaOutcome(
        val failures: List<String>,
        val recoveryLeft: Boolean,
        val stored: String?,
    )

    private val quotaPayload: String by lazy {
        val raw = JSON.parse<dynamic>(fixture())
        raw.campaign = json("file" to "target.json", "scenario" to 0)
        JSON.stringify(raw)
    }

    private fun quotaScenario(withRecovery: Boolean): Promise<QuotaOutcome> {
        IndexedDbProfileStore.databaseName = "osada-checkpoint-quota-${kotlin.js.Date.now()}"
        js("window.__osadaPut = window.__osadaPut || IDBObjectStore.prototype.put")
        val store = LocalStorageSaveSnapshotStore()
        val failures = mutableListOf<String>()
        return Promise<QuotaOutcome> { resolve, reject ->
            val finish = {
                val recoveryLeft = store.readRecovery("other.json") != null
                resolve(QuotaOutcome(failures, recoveryLeft, MissionCheckpointStore.read("target.json")))
            }
            IndexedDbProfileStore.open("quota-test") { profile ->
                if (profile == null) return@open reject(Error("IndexedDB unavailable"))
                BrowserStorage.useProfileStoreForTests(profile)
                val other = ProfileBackupCodec.runFromSave(payloadFor("other.json"), 1.0)
                val withGenerations =
                    if (withRecovery) other.copy(recovery = other.current.copy(id = "older")) else other
                store.replaceCampaignRun(withGenerations)
                store.replaceCampaignRun(ProfileBackupCodec.runFromSave(quotaPayload, 2.0))
                // Without a recovery generation the disk is simply full: nothing relief could free.
                val recoveryPresent = { !withRecovery || store.readRecovery("other.json") != null }
                window.asDynamic().__osadaRecoveryPresent = recoveryPresent
                BrowserStorage.whenDurable {
                    js(
                        """
                        IDBObjectStore.prototype.put = function (value, key) {
                            if (String(key).indexOf('osada-mission-start-') === 0 && window.__osadaRecoveryPresent()) {
                                throw new DOMException('full', 'QuotaExceededError');
                            }
                            return window.__osadaPut.call(this, value, key);
                        };
                        """,
                    )
                    assertNull(MissionCheckpointStore.write("target.json", quotaPayload))
                    MissionCheckpointStore.confirmDurable(
                        "target.json",
                        quotaPayload,
                        onFailed = {
                            failures += it
                            finish()
                        },
                        onDurable = finish,
                    )
                }
            }
        }.then({ result ->
            restoreIndexedDb()
            result
        }, { error ->
            restoreIndexedDb()
            throw error
        })
    }

    private fun restoreIndexedDb() {
        js("IDBObjectStore.prototype.put = window.__osadaPut")
        BrowserStorage.useProfileStoreForTests(null)
        IndexedDbProfileStore.databaseName = IndexedDbProfileStore.DB_NAME
    }

    private fun payloadFor(file: String): String {
        val raw = JSON.parse<dynamic>(fixture())
        raw.campaign = json("file" to file, "scenario" to 0)
        return JSON.stringify(raw)
    }
}
