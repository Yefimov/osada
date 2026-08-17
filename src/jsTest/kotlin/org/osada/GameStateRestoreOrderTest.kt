package org.osada

import kotlinx.browser.localStorage
import org.osada.model.Equipment
import org.osada.model.resetEquipment
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunMetadata
import org.osada.save.ProfileBundle
import org.osada.save.SaveResult
import org.osada.save.SaveSnapshot
import org.osada.save.SaveSnapshotStore
import kotlin.js.Promise
import kotlin.js.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GameStatePersistence.restore()`'s source selection -- the boot/Continue path.
 *
 * Two properties are asserted here, and NEITHER held before: the sources are tried in recency
 * order (so refreshing during a standalone scenario comes back to that scenario rather than to an
 * unrelated campaign), and a failing source falls through to the next (so a campaign run with two
 * unloadable generations does not take a perfectly good standalone snapshot down with it).
 *
 * Uses a fake [SaveSnapshotStore] so a campaign run can be made deliberately unloadable, and the
 * real Bizerte save fixture for the payloads so restores actually complete.
 */
class GameStateRestoreOrderTest {
    private val majorVersion = VERSION.split(".").take(2).joinToString(".")
    private val standaloneKey = "osada-standalone-session-$majorVersion"

    @BeforeTest
    fun setUp() {
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
        localStorage.removeItem(standaloneKey)
    }

    @AfterTest
    fun tearDown() {
        localStorage.removeItem(standaloneKey)
    }

    /** The shipped fixture, re-stamped to the current save format and renamed so the test can tell
     *  WHICH source a restore actually came from. */
    private fun payloadNamed(name: String): String {
        val parsed = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        parsed.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        parsed.scenario.name = name
        return JSON.stringify(parsed)
    }

    private fun writeStandalone(
        name: String,
        savedAt: Double,
    ) {
        localStorage.setItem(
            standaloneKey,
            JSON.stringify(json(Pair("savedAt", savedAt), Pair("payload", payloadNamed(name)))),
        )
    }

    private fun snapshotFor(
        runId: String,
        payload: String,
    ) = SaveSnapshot(
        id = "gen-$runId",
        campaignRunId = runId,
        kind = "autosave",
        createdAt = 0.0,
        gameVersion = VERSION,
        saveFormat = GameStateSerializer.SAVE_FORMAT_VERSION,
        scenarioFile = "bizerte.xml",
        scenarioName = "from-campaign",
        turn = 1,
        maxTurns = 20,
        phase = SavePhaseValidation.PHASE_PLAYER_TURN,
        campaignFile = runId,
        campaignScenario = 0,
        payload = payload,
    )

    /** Only the four members [restore] touches are meaningful; the rest satisfy the interface. */
    private class FakeStore(
        private val metadata: CampaignRunMetadata?,
        private val current: SaveSnapshot?,
        private val recovery: SaveSnapshot? = null,
    ) : SaveSnapshotStore {
        override fun commitAutosave(
            campaignRunId: String,
            snapshot: SaveSnapshot,
        ) = SaveResult.success()

        override fun listCampaignRuns(): List<CampaignRunMetadata> = listOfNotNull(metadata)

        override fun readCurrent(campaignRunId: String): SaveSnapshot? = current

        override fun readRecovery(campaignRunId: String): SaveSnapshot? = recovery

        override fun replaceCampaignRun(bundle: CampaignRunBundle) = SaveResult.success()

        override fun readCampaignRunBundle(campaignRunId: String): CampaignRunBundle? = null

        override fun exportProfile() = ProfileBundle(emptyList(), 0.0, VERSION)

        override fun replaceProfile(bundle: ProfileBundle) = SaveResult.success()

        override fun deleteCampaignRun(campaignRunId: String) = SaveResult.success()

        override fun markCompleted(
            campaignRunId: String,
            outcome: String,
        ) = SaveResult.success()

        override fun pruneOrphans() = Unit
    }

    private fun runMetadata(lastPlayedAt: Double) =
        CampaignRunMetadata(
            campaignRunId = "camp.json",
            campaignFile = "camp.json",
            campaignName = "camp.json",
            scenarioName = "from-campaign",
            campaignScenario = 0,
            phase = SavePhaseValidation.PHASE_PLAYER_TURN,
            lastPlayedAt = lastPlayedAt,
            completed = false,
        )

    private fun restoreWith(store: SaveSnapshotStore): Promise<RestoreOutcome> {
        val game = Game()
        game.state = GameState(game)
        val persistence = GameStatePersistence(game, GameStateRestore(game), store)
        return Promise { resolve, _ ->
            var settled = false
            persistence.restore(
                onSuccess = {
                    if (!settled) {
                        settled = true
                        resolve(RestoreOutcome(succeeded = true, scenarioName = game.scenario?.name))
                    }
                },
                onFail = {
                    if (!settled) {
                        settled = true
                        resolve(RestoreOutcome(succeeded = false, scenarioName = null))
                    }
                },
            )
        }
    }

    private data class RestoreOutcome(
        val succeeded: Boolean,
        val scenarioName: String?,
    )

    @Test
    fun aMoreRecentStandaloneSessionWinsOverAnOlderCampaignRun(): Promise<Unit> {
        writeStandalone("from-standalone", savedAt = 2000.0)
        val store =
            FakeStore(
                metadata = runMetadata(lastPlayedAt = 1000.0),
                current = snapshotFor("camp.json", payloadNamed("from-campaign")),
            )
        return restoreWith(store).then {
            assertTrue(it.succeeded, "a restorable source exists, so restore must not fail")
            assertEquals(
                "from-standalone",
                it.scenarioName,
                "refreshing during a standalone scenario must come back to THAT scenario",
            )
        }
    }

    @Test
    fun aMoreRecentCampaignRunWinsOverAnOlderStandaloneSession(): Promise<Unit> {
        writeStandalone("from-standalone", savedAt = 1000.0)
        val store =
            FakeStore(
                metadata = runMetadata(lastPlayedAt = 2000.0),
                current = snapshotFor("camp.json", payloadNamed("from-campaign")),
            )
        return restoreWith(store).then {
            assertTrue(it.succeeded)
            assertEquals("from-campaign", it.scenarioName, "the campaign run was played more recently")
        }
    }

    /** The fall-through property: an unloadable campaign run must not consume the restore. */
    @Test
    fun anUnloadableCampaignRunFallsThroughToTheStandaloneSession(): Promise<Unit> {
        writeStandalone("from-standalone", savedAt = 1000.0)
        val store =
            FakeStore(
                metadata = runMetadata(lastPlayedAt = 9000.0), // newest, and tried first
                current = snapshotFor("camp.json", "{\"fmt\":99,\"not\":\"a save\"}"),
                recovery = null,
            )
        return restoreWith(store).then {
            assertTrue(it.succeeded, "the standalone snapshot was still good and must have been used")
            assertEquals("from-standalone", it.scenarioName)
        }
    }

    /** A generation that parses but describes no live game must be rejected on READ, not restored.
     *  Only the write path used to check this, so imported/migrated generations slipped through. */
    @Test
    fun aGenerationWithNoUnitsIsRejectedAndFallsThrough(): Promise<Unit> {
        writeStandalone("from-standalone", savedAt = 1000.0)
        val emptyPayload =
            JSON.stringify(
                json(
                    Pair("fmt", GameStateSerializer.SAVE_FORMAT_VERSION),
                    Pair(
                        "scenario",
                        json(
                            Pair("turn", 1),
                            Pair("maxTurns", 20),
                            Pair("map", json(Pair("hexes", arrayOf(arrayOf(json()))))),
                            Pair("reinforcements", json()),
                        ),
                    ),
                    Pair("players", arrayOf(json(Pair("coreUnits", arrayOf<dynamic>())))),
                ),
            )
        val store =
            FakeStore(
                metadata = runMetadata(lastPlayedAt = 9000.0),
                current = snapshotFor("camp.json", emptyPayload),
            )
        return restoreWith(store).then {
            assertTrue(it.succeeded)
            assertEquals("from-standalone", it.scenarioName, "an empty generation must not win over a real save")
        }
    }

    @Test
    fun aCorruptCampaignGenerationFallsBackToItsOwnRecoveryGeneration(): Promise<Unit> {
        val store =
            FakeStore(
                metadata = runMetadata(lastPlayedAt = 9000.0),
                current = snapshotFor("camp.json", "not json at all"),
                recovery = snapshotFor("camp.json", payloadNamed("from-recovery")),
            )
        return restoreWith(store).then {
            assertTrue(it.succeeded, "the recovery generation was good")
            assertEquals("from-recovery", it.scenarioName)
        }
    }

    @Test
    fun nothingRestorableAnywhereReportsFailure(): Promise<Unit> {
        val store = FakeStore(metadata = null, current = null)
        return restoreWith(store).then {
            assertTrue(it.succeeded.not(), "with no source at all restore must report failure")
        }
    }
}
