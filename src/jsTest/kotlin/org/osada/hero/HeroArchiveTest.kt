package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.save.InMemoryHeroArchiveStore
import kotlin.js.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The archive's storage contract and the live/archived dossier parity §9 asks for
 * (`docs/design/hero-desk-and-profile-archive.md`).
 */
class HeroArchiveTest {
    private val heroId = HeroId("H-7")
    private val formationId = FormationId("F-0-3")

    @BeforeTest
    fun setUpI18n() {
        installEnglishUiBundleForTests()
    }

    private fun definition() =
        HeroDefinition(
            id = heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = "Anna Voroshina",
            backgroundId = "armored_academy_graduate",
            biographyFacts = HeroBiographyFacts(birthYear = 1912, emergenceEventId = "destroyed_stronger"),
            portrait = PortraitComposition(seed = 41, layerIds = listOf("portraits/a.svg"), female = true),
            signatureTraitId = LegacyTraitMapping.toTraitId(LeaderType.FIRST_STRIKE),
        )

    private fun state(
        status: HeroStatus = HeroStatus.ACTIVE,
        renown: HeroRenown = HeroRenown.HERO,
    ) = HeroState(
        heroId = heroId,
        rankId = "major",
        status = status,
        potential = HeroPotential.DISTINGUISHED,
        renown = renown,
        attributes = CommandAttributes(offense = 2, defense = 1, maneuver = 0, coordination = 1),
        experience = 310,
        assignedFormationId = formationId,
        learnedTraitIds = setOf(LegacyTraitMapping.toTraitId(LeaderType.FIRST_STRIKE)),
        specializationEvidence = mapOf("URBAN_COMBAT" to 30),
        medals = listOf(HeroMedal("valor_medal", "Kiel")),
        serviceEvents = listOf(HeroEvent("destroyed_stronger", "Kiel", 4)),
        promotionsAwarded = 2,
    )

    private fun formation() =
        CoreFormation(
            id = formationId,
            ownerId = 0,
            country = 1,
            displayName = "3rd Guards Brigade",
            currentEquipmentId = 900,
            unitClass = UnitClass.TANK.value,
            assignedHeroId = heroId,
            recognition = 70,
            battleHonors = listOf("Kiel"),
            history = listOf(FormationEvent("scenario_completed", "Kiel", 12)),
        )

    private fun roster() =
        HeroRoster().apply {
            putFormation(formation())
            putHero(definition(), state())
        }

    private fun archive(
        runStatus: ArchiveRunStatus = ArchiveRunStatus.IN_PROGRESS,
        heroState: HeroState = state(),
    ): CampaignHeroArchive {
        val roster =
            HeroRoster().apply {
                putFormation(formation())
                putHero(definition(), heroState)
            }
        return assertNotNull(
            HeroSnapshotProjector.projectRoster(
                roster = roster,
                formationExperience = mapOf(formationId.value to 240),
                campaignRunId = "camp6.json",
                runEpoch = "epoch-1",
                campaignFile = "camp6.json",
                campaignName = "Red Army Campaign",
                lastScenarioId = "Kiel",
                lastScenarioIndex = 3,
                updatedAt = Date().getTime(),
                runStatus = runStatus,
            ),
        )
    }

    // ------------------------------------------------------------------ codec

    @Test
    fun archiveRoundTripsThroughItsCodec() {
        val original = HeroArchive(campaigns = mapOf("camp6.json" to archive()))
        val restored = HeroArchiveCodec.parseString(HeroArchiveCodec.stringify(original))
        val entry = assertNotNull(restored.campaigns["camp6.json"])
        assertEquals("epoch-1", entry.runEpoch)
        assertEquals("Red Army Campaign", entry.campaignName)
        assertEquals(ArchiveRunStatus.IN_PROGRESS, entry.runStatus)
        assertEquals(mapOf(formationId.value to 240), entry.formationExperience)
    }

    /** Backward by absence, forward by tolerance: neither shape may throw. */
    @Test
    fun unreadableOrAbsentArchiveDegradesToEmpty() {
        assertEquals(HeroArchive.EMPTY, HeroArchiveCodec.parseString(null))
        assertEquals(HeroArchive.EMPTY, HeroArchiveCodec.parseString("not json at all"))
        assertTrue(HeroArchiveCodec.parseString("""{"campaigns":[{"runEpoch":"x"}]}""").campaigns.isEmpty())
    }

    @Test
    fun unknownRunStatusFallsBackInsteadOfThrowing() {
        val raw = """{"schemaVersion":1,"campaigns":[{"campaignRunId":"c","runStatus":"FUTURE_VALUE","roster":"{}"}]}"""
        assertEquals(ArchiveRunStatus.IN_PROGRESS, HeroArchiveCodec.parseString(raw).campaigns["c"]?.runStatus)
    }

    // ------------------------------------------------------------------ store

    @Test
    fun upsertIsACompleteReplacementAndThereforeIdempotent() {
        val store = InMemoryHeroArchiveStore()
        store.replaceCampaign(archive())
        store.replaceCampaign(archive())
        assertEquals(1, store.read().campaigns.size)
        val entry = assertNotNull(store.read().campaigns["camp6.json"])
        val records = HeroSnapshotProjector.records(entry, HeroRecordSource.ARCHIVE, resumableRun = false)
        assertEquals(1, records.size)
        assertEquals(
            1,
            records
                .single()
                .dossier
                ?.medals
                ?.size,
            "a retried transition must not duplicate medals",
        )
    }

    @Test
    fun deletingAnAbsentCampaignReportsNotFoundRatherThanSucceeding() {
        val store = InMemoryHeroArchiveStore()
        assertTrue(!store.deleteCampaign("nothing.json").isSuccess)
        store.replaceCampaign(archive())
        assertTrue(store.deleteCampaign("camp6.json").isSuccess)
        assertTrue(store.read().campaigns.isEmpty())
    }

    // ------------------------------------------------------ dossier parity (§9)

    @Test
    fun archivedRecordProducesTheSameDossierAsTheLiveOne() {
        val live = HeroDossierAssembler.dossier(definition(), state(), formation(), unitExperience = 240)
        val archived =
            HeroSnapshotProjector
                .records(archive(), HeroRecordSource.ARCHIVE, resumableRun = false)
                .single()
                .dossier
        assertEquals(live, assertNotNull(archived))
    }

    @Test
    fun projectionKeepsFallenHeroesAndFullFormationHistory() {
        val entry = archive(heroState = state(status = HeroStatus.KILLED))
        val record = HeroSnapshotProjector.records(entry, HeroRecordSource.ARCHIVE, false).single()
        assertEquals(HeroStatus.KILLED, record.status)
        assertTrue(record.inMemoriam)
        assertEquals(
            1,
            record.dossier
                ?.formation
                ?.history
                ?.size,
        )
    }

    /**
     * A survivor of a finished run presents as retired WITHOUT the stored status being rewritten
     * (§4) -- the record still says ACTIVE, and the dossier still says Active.
     */
    @Test
    fun completedRunPresentsSurvivorsAsRetiredWithoutMutatingTheirStatus() {
        val record =
            HeroSnapshotProjector
                .records(archive(runStatus = ArchiveRunStatus.COMPLETED), HeroRecordSource.ARCHIVE, false)
                .single()
        assertTrue(record.retiredFromRun)
        assertEquals(HeroStatus.ACTIVE, record.status)
        assertEquals("Active", record.dossier?.status)
    }

    @Test
    fun fallenOfficersAreNeverRelabelledAsRetired() {
        val entry = archive(runStatus = ArchiveRunStatus.COMPLETED, heroState = state(status = HeroStatus.KILLED))
        assertTrue(!HeroSnapshotProjector.records(entry, HeroRecordSource.ARCHIVE, false).single().retiredFromRun)
    }

    // ------------------------------------------------- projection from a save

    @Test
    fun projectingASavePayloadReadsTheHeroBlockAndCoreUnitExperience() {
        val payload =
            JSON.stringify(
                kotlin.js.json(
                    Pair(
                        "campaign",
                        kotlin.js.json(
                            Pair("heroes", HeroSerializer.serialize(roster())),
                            Pair(
                                "coreUnits",
                                arrayOf(
                                    kotlin.js.json(
                                        Pair("formationId", formationId.value),
                                        Pair("experience", 555),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val projected =
            assertNotNull(
                HeroSnapshotProjector.project(
                    payload = payload,
                    campaignRunId = "camp6.json",
                    runEpoch = "e",
                    campaignFile = "camp6.json",
                    campaignName = "Red Army Campaign",
                    lastScenarioId = "Kiel",
                    lastScenarioIndex = 3,
                    updatedAt = 1.0,
                    runStatus = ArchiveRunStatus.IN_PROGRESS,
                ),
            )
        val record = HeroSnapshotProjector.records(projected, HeroRecordSource.LIVE, resumableRun = true).single()
        assertEquals("Anna Voroshina", record.name)
        assertEquals(555, record.dossier?.formation?.unitExperience)
    }

    /** A campaign with no hero block contributes nothing rather than an empty card. */
    @Test
    fun payloadWithoutAHeroBlockProjectsNothing() {
        val campaign = kotlin.js.json(Pair("coreUnits", arrayOf<Int>()))
        val payload = JSON.stringify(kotlin.js.json(Pair("campaign", campaign)))
        assertNull(
            HeroSnapshotProjector.project(
                payload,
                "c",
                "e",
                "c",
                "C",
                "S",
                0,
                0.0,
                ArchiveRunStatus.IN_PROGRESS,
            ),
        )
        assertNull(
            HeroSnapshotProjector.project(
                "}} not json",
                "c",
                "e",
                "c",
                "C",
                "S",
                0,
                0.0,
                ArchiveRunStatus.IN_PROGRESS,
            ),
        )
    }
}
