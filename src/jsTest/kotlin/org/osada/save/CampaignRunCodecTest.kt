package org.osada.save

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip and hostile-input coverage for the file format behind `Export campaign` /
 * `Import campaign` (`docs/design/save-recovery.md` §§2, 8).
 *
 * The codec is the compatibility contract between two files written months apart, so the tests
 * assert what survives, what is rejected, and what a missing field degrades to -- not just that a
 * happy path parses.
 */
class CampaignRunCodecTest {
    private fun snapshot(
        campaignRunId: String = "camp6.json",
        kind: String = "autosave",
        turn: Int = 7,
    ) = SaveSnapshot(
        id = "id-$kind",
        campaignRunId = campaignRunId,
        kind = kind,
        createdAt = 1_700_000_000_000.0,
        gameVersion = "3.3.0",
        saveFormat = 4,
        scenarioFile = "kiel.xml",
        scenarioName = "Kiel",
        turn = turn,
        maxTurns = 20,
        phase = "playerTurn",
        campaignFile = campaignRunId,
        campaignScenario = 3,
        payload = """{"fmt":4,"scenario":{"file":"kiel.xml","turn":$turn}}""",
    )

    private fun metadata(campaignRunId: String = "camp6.json") =
        CampaignRunMetadata(
            campaignRunId = campaignRunId,
            campaignFile = campaignRunId,
            campaignName = "Fall Weiss",
            scenarioName = "Kiel",
            campaignScenario = 3,
            phase = "playerTurn",
            lastPlayedAt = 1_700_000_000_000.0,
            completed = false,
            turn = 7,
            maxTurns = 20,
            outcome = "",
        )

    private fun reparse(bundle: CampaignRunBundle): CampaignRunBundle? =
        CampaignRunCodec.jsonToRun(JSON.parse(JSON.stringify(CampaignRunCodec.runToJson(bundle))))

    @Test
    fun runSurvivesRoundTripWithBothGenerations() {
        val original = CampaignRunBundle(metadata(), snapshot(), snapshot(kind = "recovery", turn = 6))
        val parsed = assertNotNull(reparse(original))
        assertEquals(original.metadata, parsed.metadata)
        assertEquals(original.current, parsed.current)
        assertEquals(original.recovery, parsed.recovery)
    }

    /** The previous-good generation is optional: a run exported before one existed must import as a
     *  run with no recovery copy, not as an unreadable file. */
    @Test
    fun runWithoutRecoverySurvivesRoundTrip() {
        val parsed = assertNotNull(reparse(CampaignRunBundle(metadata(), snapshot(), null)))
        assertNull(parsed.recovery)
        assertEquals(7, parsed.current.turn)
    }

    /** The payload is the whole point of the file, so it must come back byte-identical rather than
     *  re-encoded: it is handed straight to the existing game-state restore path. */
    @Test
    fun payloadIsNotRewrittenByTheRoundTrip() {
        val original = CampaignRunBundle(metadata(), snapshot(), null)
        assertEquals(original.current.payload, assertNotNull(reparse(original)).current.payload)
    }

    @Test
    fun runWithoutCurrentGenerationIsRejected() {
        val raw = JSON.parse<dynamic>("""{"metadata":{"campaignRunId":"camp6.json"}}""")
        assertNull(CampaignRunCodec.jsonToRun(raw))
    }

    /** A bare snapshot is not a run bundle: without metadata there is no campaign name, operation
     *  or timestamp to put in the confirmation the import is required to show first. */
    @Test
    fun runWithoutMetadataIsRejected() {
        val bareSnapshot = LocalStorageSaveSnapshotStore.snapshotToJson(snapshot())
        assertNull(CampaignRunCodec.jsonToRun(bareSnapshot))
        assertNull(CampaignRunCodec.jsonToRun(JSON.parse<dynamic>("{}")))
        assertNull(CampaignRunCodec.jsonToRun(null))
    }

    /** An unidentifiable run has nowhere to be filed, so it is rejected rather than imported under
     *  a guessed key that would overwrite an unrelated campaign. */
    @Test
    fun metadataWithoutRunIdIsRejected() {
        assertNull(CampaignRunCodec.jsonToMetadata(JSON.parse<dynamic>("""{"campaignName":"Fall Weiss"}""")))
    }

    /** Fields added to the index after a file was written must degrade to the store's own "unknown"
     *  defaults, not to plausible-looking numbers the importer invented. */
    @Test
    fun missingOptionalMetadataFieldsDegradeToUnknown() {
        val parsed =
            assertNotNull(
                CampaignRunCodec.jsonToMetadata(JSON.parse<dynamic>("""{"campaignRunId":"camp6.json"}""")),
            )
        assertEquals(0, parsed.turn)
        assertEquals(0, parsed.maxTurns)
        assertEquals(0.0, parsed.lastPlayedAt)
        assertEquals("", parsed.outcome)
        assertTrue(!parsed.completed)
    }

    /** A per-campaign export must not carry profile-level state: importing one campaign can never
     *  be a route to rewriting the ruleset library or another campaign's archived career. */
    @Test
    fun runFileCarriesNoProfileLevelState() {
        val serialized = JSON.stringify(CampaignRunCodec.runToJson(CampaignRunBundle(metadata(), snapshot(), null)))
        assertTrue("rulesetProfiles" !in serialized, serialized)
        assertTrue("heroArchive" !in serialized, serialized)
    }
}
