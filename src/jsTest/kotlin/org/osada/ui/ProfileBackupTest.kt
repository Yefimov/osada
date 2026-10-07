package org.osada.ui

import kotlinx.browser.localStorage
import org.osada.BIZERTE_SAVE_JSON
import org.osada.Game
import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.LeaderType
import org.osada.OPERATION_URANUS_SAVE_JSON
import org.osada.SavePhaseValidation
import org.osada.UnitClass
import org.osada.hero.ArchiveRunStatus
import org.osada.hero.CommandAttributes
import org.osada.hero.CoreFormation
import org.osada.hero.FormationEvent
import org.osada.hero.FormationId
import org.osada.hero.HeroArchive
import org.osada.hero.HeroArchiveCodec
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroEvent
import org.osada.hero.HeroId
import org.osada.hero.HeroMedal
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroPotential
import org.osada.hero.HeroRenown
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSnapshotProjector
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.LegacyTraitMapping
import org.osada.hero.PortraitComposition
import org.osada.i18n.I18n
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetProfile
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.save.CampaignRunBundle
import org.osada.save.CampaignRunCodec
import org.osada.save.LocalStorageSaveSnapshotStore
import org.osada.save.ProfileBundle
import org.osada.save.ResumeTarget
import org.osada.save.SaveResult
import org.osada.save.SaveResultKind
import kotlin.js.json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileBackupTest {
    private var previousGame: Game? = null
    private var previousStorage = emptyMap<String, String>()
    private val store = LocalStorageSaveSnapshotStore()

    @BeforeTest
    fun setup() {
        previousGame = GameHolder.instance
        Game()
        previousStorage =
            (0 until localStorage.length)
                .mapNotNull { localStorage.key(it) }
                .associateWith { localStorage.getItem(it)!! }
        localStorage.clear()
    }

    @AfterTest
    fun cleanup() {
        localStorage.clear()
        previousStorage.forEach { (key, value) -> localStorage.setItem(key, value) }
        GameHolder.instance = previousGame
    }

    private fun run(
        file: String,
        fixture: String = BIZERTE_SAVE_JSON,
    ): CampaignRunBundle {
        val raw = JSON.parse<dynamic>(fixture)
        raw.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        raw.campaign = json("file" to file, "scenario" to 0)
        return ProfileBackupCodec.runFromSave(JSON.stringify(raw), 123456.0)
    }

    private fun profile(vararg runs: CampaignRunBundle): dynamic =
        json(
            "runs" to runs.map(CampaignRunCodec::runToJson).toTypedArray(),
            "exportedAt" to 123456.0,
        )

    @Test
    fun fullBackupRoundTripsBothRealFixturesAndRestartCheckpoints() {
        val first = run("test-a.json")
        val second = run("test-b.json", OPERATION_URANUS_SAVE_JSON)
        assertTrue(store.replaceProfile(ProfileBundle(listOf(first, second), 0.0, "test")).isSuccess)
        localStorage.setItem("${ProfileBackupCodec.checkpointPrefix}:test-a.json", first.current.payload)
        localStorage.setItem("${ProfileBackupCodec.checkpointPrefix}:test-b.json", second.current.payload)
        val exported = ProfileBackup.prepareExport()
        val plan = ProfileBackupCodec.decode(JSON.parse<dynamic>(JSON.stringify(exported)))
        localStorage.clear()
        assertTrue(ProfileBackup.apply(plan).isSuccess)
        assertEquals(first.current.payload, store.readCurrent("test-a.json")?.payload)
        assertEquals(second.current.payload, store.readCurrent("test-b.json")?.payload)
        assertEquals(first.current.payload, localStorage.getItem("${ProfileBackupCodec.checkpointPrefix}:test-a.json"))
        assertEquals(second.current.payload, localStorage.getItem("${ProfileBackupCodec.checkpointPrefix}:test-b.json"))
    }

    @Test
    fun malformedSiblingRejectsWholeFileWithoutWritingAnything() {
        store.replaceCampaignRun(run("existing.json"))
        val raw = profile(run("incoming.json"))
        raw.runs.push(json("metadata" to json("campaignRunId" to "broken")))
        assertFails { ProfileBackupCodec.decode(raw) }
        assertNotNull(store.readCurrent("existing.json"))
        assertNull(store.readCurrent("incoming.json"))
    }

    @Test
    fun duplicateAndMismatchedIdentitiesAreRejected() {
        val valid = run("test-a.json")
        assertFails { ProfileBackupCodec.decode(profile(valid, valid)) }
        val raw = profile(valid)
        raw.runs[0].metadata.campaignRunId = "another.json"
        assertFails { ProfileBackupCodec.decode(raw) }
    }

    @Test
    fun malformedRecoveryAndTooOldFormatsAreRejected() {
        val raw = profile(run("test-a.json"))
        raw.runs[0].recovery = json("payload" to "truncated")
        assertFails { ProfileBackupCodec.decode(raw) }
        val old = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        old.fmt = 0
        assertFails { ProfileBackupCodec.decode(old) }
    }

    @Test
    fun emptyProfileWithArchiveAndRulesIsImportable() {
        val raw = profile()
        raw.heroArchive = HeroArchiveCodec.serialize(HeroArchive.EMPTY)
        raw.rulesetProfiles = js("[]")
        val plan = ProfileBackupCodec.decode(raw)
        assertTrue(plan.bundle.runs.isEmpty())
        assertTrue(ProfileBackup.apply(plan).isSuccess)
        assertNotNull(localStorage.getItem(ProfileBackupCodec.archiveKey))
        assertEquals("[]", localStorage.getItem(ProfileBackupCodec.RULESETS_KEY))
    }

    @Test
    fun legacySingleCampaignKeepsOtherCampaignsAndLibraries() {
        store.replaceCampaignRun(run("keep.json"))
        localStorage.setItem(ProfileBackupCodec.RULESETS_KEY, "[]")
        val imported = run("new.json")
        val raw =
            json(
                "kind" to CampaignRunCodec.FILE_KIND,
                "run" to CampaignRunCodec.runToJson(imported),
                "missionStart" to imported.current.payload,
            )
        val plan = ProfileBackupCodec.decode(raw)
        assertTrue(ProfileBackup.apply(plan).isSuccess)
        assertNotNull(store.readCurrent("keep.json"))
        assertEquals(imported.current.payload, store.readCurrent("new.json")?.payload)
        assertEquals("[]", localStorage.getItem(ProfileBackupCodec.RULESETS_KEY))
    }

    @Test
    fun standaloneLegacyFileUsesSameImporterWithoutCreatingCampaign() {
        val raw = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        raw.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        raw.campaign = null
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(raw)).isSuccess)
        assertTrue(store.listCampaignRuns().isEmpty())
        val exported = ProfileBackup.prepareExport()
        assertNotNull(exported.standalone)
        ProfileBackupCodec.decode(exported)
    }

    @Test
    fun legacyProfileAbsentLibrariesPreservesLocalLibraries() {
        localStorage.setItem(ProfileBackupCodec.RULESETS_KEY, "[]")
        localStorage.setItem(ProfileBackupCodec.archiveKey, HeroArchiveCodec.stringify(HeroArchive.EMPTY))
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(profile(run("test-a.json")))).isSuccess)
        assertEquals("[]", localStorage.getItem(ProfileBackupCodec.RULESETS_KEY))
        assertNotNull(localStorage.getItem(ProfileBackupCodec.archiveKey))
    }

    @Test
    fun wrongMissionCheckpointIsRejected() {
        val raw = profile(run("test-a.json"))
        raw.missionStarts = json("test-a.json" to run("different.json").current.payload)
        assertFails { ProfileBackupCodec.decode(raw) }
    }

    @Test
    fun unreadableStoredRunCannotBeSilentlyOmittedFromExport() {
        store.replaceCampaignRun(run("test-a.json"))
        val key =
            (0 until localStorage.length)
                .mapNotNull { localStorage.key(it) }
                .single { it.endsWith("test-a.json-current") }
        localStorage.setItem(key, "broken")
        assertFails { ProfileBackup.prepareExport() }
    }

    /** [run] with its payload edited, keeping the snapshot's identity fields consistent. */
    private fun editedRun(
        file: String,
        phase: String = SavePhaseValidation.PHASE_PLAYER_TURN,
        edit: (dynamic) -> Unit,
    ): CampaignRunBundle {
        val base = run(file)
        val raw = JSON.parse<dynamic>(base.current.payload)
        edit(raw)
        return base.copy(current = base.current.copy(payload = JSON.stringify(raw), phase = phase))
    }

    private fun forEachUnitHex(
        raw: dynamic,
        action: (dynamic) -> Unit,
    ) {
        val hexes = raw.scenario.map.hexes ?: raw.scenario.map.map
        (hexes as Array<dynamic>).forEach { row -> (row as Array<dynamic>).forEach { if (it != null) action(it) } }
    }

    @Test
    fun defeatedFinalTurnRunWithOnlyEnemyUnitsLeftIsImportable() {
        val defeated =
            editedRun("lost.json") { raw ->
                raw.scenario.map.turn = raw.scenario.map.maxTurns
                val human = raw.players[0].id
                forEachUnitHex(raw) { hex ->
                    if (hex.unit?.owner == human) hex.unit = null
                    if (hex.airunit?.owner == human) hex.airunit = null
                }
            }
        val finished = defeated.copy(metadata = defeated.metadata.copy(completed = true, outcome = "lose"))
        val plan = ProfileBackupCodec.decode(JSON.parse<dynamic>(JSON.stringify(profile(finished))))
        assertTrue(ProfileBackup.apply(plan).isSuccess)
        val stored = store.listCampaignRuns().single()
        assertTrue(stored.completed, "completion must survive the transfer")
        assertEquals("lose", stored.outcome, "a defeat must not come back as an unfinished or won run")
        assertEquals(defeated.current.payload, store.readCurrent("lost.json")?.payload)
    }

    @Test
    fun reserveOnlyDeploymentRunIsValidatedAsDeploymentAndImportable() {
        val reserveOnly =
            editedRun("deploy.json", SavePhaseValidation.PHASE_DEPLOYMENT) { raw ->
                var sample: dynamic = null
                forEachUnitHex(raw) { hex ->
                    if (sample == null) sample = hex.unit ?: hex.airunit
                    hex.unit = null
                    hex.airunit = null
                }
                raw.scenario.reinforcements = json()
                raw.players[0].coreUnits = arrayOf(sample)
            }
        val plan = ProfileBackupCodec.decode(JSON.parse<dynamic>(JSON.stringify(profile(reserveOnly))))
        assertTrue(ProfileBackup.apply(plan).isSuccess)
        assertEquals(SavePhaseValidation.PHASE_DEPLOYMENT, store.readCurrent("deploy.json")?.phase)
    }

    @Test
    fun aSaveWithNoUnitsAnywhereIsStillRejected() {
        val empty =
            editedRun("empty.json") { raw ->
                forEachUnitHex(raw) { hex ->
                    hex.unit = null
                    hex.airunit = null
                }
                raw.scenario.reinforcements = json()
            }
        assertFails { ProfileBackupCodec.decode(JSON.parse<dynamic>(JSON.stringify(profile(empty)))) }
    }

    @Test
    fun legacyCampaignFileTargetsTheImportedRunForTheNextBoot() {
        val imported = run("new.json")
        val raw = json("kind" to CampaignRunCodec.FILE_KIND, "run" to CampaignRunCodec.runToJson(imported))
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(raw)).isSuccess)
        assertEquals(ResumeTarget.Campaign("new.json"), ResumeTarget.read())
    }

    @Test
    fun rawStandaloneFileTargetsTheStandaloneSlot() {
        store.replaceCampaignRun(run("newer-local.json"))
        val raw = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        raw.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        raw.campaign = null
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(raw)).isSuccess)
        assertEquals(ResumeTarget.Standalone, ResumeTarget.read())
        assertNotNull(store.readCurrent("newer-local.json"), "a standalone import leaves campaign progress alone")
    }

    @Test
    fun fullProfileImportClearsAnOlderTarget() {
        localStorage.setItem(ResumeTarget.key, ResumeTarget.encode(ResumeTarget.Standalone))
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(profile(run("test-a.json")))).isSuccess)
        assertNull(ResumeTarget.read(), "a full replacement keeps ordinary recency")
    }

    @Test
    fun aFailedImportRollsTheTargetBackWithTheData() {
        localStorage.setItem(ResumeTarget.key, ResumeTarget.encode(ResumeTarget.Campaign("kept.json")))
        val legacy = json("kind" to CampaignRunCodec.FILE_KIND, "run" to CampaignRunCodec.runToJson(run("new.json")))
        val plan = ProfileBackupCodec.decode(legacy)
        // A pending journal makes the batch refuse before its first write.
        localStorage.setItem(org.osada.save.LocalStorageBatch.JOURNAL_KEY, "[]")
        assertTrue(!ProfileBackup.apply(plan).isSuccess)
        assertEquals(ResumeTarget.Campaign("kept.json"), ResumeTarget.read())
        assertNull(store.readCurrent("new.json"))
    }

    /** A real one-hero career (same shape as `HeroArchiveTest`), so the transfer is not vacuous. */
    private val heroId = HeroId("H-7")
    private val formationId = FormationId("F-0-3")

    private fun heroRoster(): HeroRoster =
        HeroRoster().apply {
            putFormation(
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
                ),
            )
            putHero(
                HeroDefinition(
                    id = heroId,
                    origin = HeroOrigin.PROCEDURAL,
                    displayName = "Anna Voroshina",
                    backgroundId = "armored_academy_graduate",
                    biographyFacts = HeroBiographyFacts(birthYear = 1912, emergenceEventId = "destroyed_stronger"),
                    portrait = PortraitComposition(seed = 41, layerIds = listOf("portraits/a.svg"), female = true),
                    signatureTraitId = LegacyTraitMapping.toTraitId(LeaderType.FIRST_STRIKE),
                ),
                HeroState(
                    heroId = heroId,
                    rankId = "major",
                    status = HeroStatus.ACTIVE,
                    potential = HeroPotential.DISTINGUISHED,
                    renown = HeroRenown.HERO,
                    attributes = CommandAttributes(offense = 2, defense = 1, maneuver = 0, coordination = 1),
                    experience = 310,
                    assignedFormationId = formationId,
                    learnedTraitIds = setOf(LegacyTraitMapping.toTraitId(LeaderType.FIRST_STRIKE)),
                    specializationEvidence = mapOf("URBAN_COMBAT" to 30),
                    medals = listOf(HeroMedal("valor_medal", "Kiel")),
                    serviceEvents = listOf(HeroEvent("destroyed_stronger", "Kiel", 4)),
                    promotionsAwarded = 2,
                ),
            )
        }

    private fun heroArchive(): HeroArchive {
        val roster = heroRoster()
        val career =
            assertNotNull(
                HeroSnapshotProjector.projectRoster(
                    roster = roster,
                    formationExperience = mapOf(formationId.value to 240),
                    campaignRunId = "test-a.json",
                    runEpoch = "epoch-1",
                    campaignFile = "test-a.json",
                    campaignName = "Red Army Campaign",
                    lastScenarioId = "Kiel",
                    lastScenarioIndex = 3,
                    updatedAt = 1000.0,
                    runStatus = ArchiveRunStatus.IN_PROGRESS,
                ),
            )
        return HeroArchive(campaigns = mapOf("test-a.json" to career))
    }

    @Test
    fun nonEmptyHeroCareersCustomRulesAndRecoveryGenerationsSurviveTransfer() {
        val first = run("test-a.json")
        val previous =
            run("test-a.json", OPERATION_URANUS_SAVE_JSON).current.copy(id = "previous-good", kind = "recovery")
        assertTrue(store.replaceCampaignRun(first.copy(recovery = previous)).isSuccess)
        localStorage.setItem(ProfileBackupCodec.archiveKey, HeroArchiveCodec.stringify(heroArchive()))
        RulesetProfileStore.save(RulesetProfile("custom-1", "Long flak", overrides = mapOf(RuleKey.FLAK_RANGE to 3)))
        val rulesBefore = RulesetProfileStore.custom()
        val file = JSON.stringify(ProfileBackup.prepareExport())

        localStorage.clear()
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(JSON.parse<dynamic>(file))).isSuccess)

        assertEquals(previous.payload, store.readRecovery("test-a.json")?.payload, "previous-good generation")
        val careers = HeroArchiveCodec.parseString(localStorage.getItem(ProfileBackupCodec.archiveKey)!!)
        assertEquals("epoch-1", careers.campaigns["test-a.json"]?.runEpoch, "hero careers travel")
        assertEquals(rulesBefore, RulesetProfileStore.custom(), "named custom rulesets travel")
        assertTrue(rulesBefore.isNotEmpty())
    }

    @Test
    fun corruptLibraryRecordsRejectTheWholeFile() {
        val raw = profile(run("test-a.json"))
        raw.rulesetProfiles = JSON.parse<dynamic>("""[{"id":"custom-1","name":"ok","overrides":{}},{"broken":true}]""")
        assertFails { ProfileBackupCodec.decode(raw) }
        val archive = profile(run("test-a.json"))
        archive.heroArchive =
            JSON.parse<dynamic>("""{"schemaVersion":${HeroArchive.SCHEMA_VERSION},"campaigns":[{"x":1}],"legacy":[]}""")
        assertFails { ProfileBackupCodec.decode(archive) }
    }

    @Test
    fun anOldFileWithoutAnOpeningClearsTheReplacedRunsLocalOpening() {
        val key = "${ProfileBackupCodec.checkpointPrefix}:test-a.json"
        val indexKey = "${ProfileBackupCodec.checkpointPrefix}:index"
        store.replaceCampaignRun(run("test-a.json"))
        // This device's opening belongs to another playthrough of the same campaign file.
        localStorage.setItem(key, run("test-a.json", OPERATION_URANUS_SAVE_JSON).current.payload)
        localStorage.setItem(indexKey, JSON.stringify(arrayOf(key)))
        val legacy =
            json("kind" to CampaignRunCodec.FILE_KIND, "run" to CampaignRunCodec.runToJson(run("test-a.json")))
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(legacy)).isSuccess)
        assertNull(localStorage.getItem(key), "another playthrough's opening must not be adopted")
        val index = localStorage.getItem(indexKey)?.let { JSON.parse<Array<String>>(it).toList() }.orEmpty()
        assertTrue(key !in index, "the index must not point at the removed opening")
    }

    @Test
    fun aFullReplacementRemovesOpeningsOfCampaignsNotInTheFile() {
        store.replaceCampaignRun(run("gone.json"))
        val goneKey = "${ProfileBackupCodec.checkpointPrefix}:gone.json"
        localStorage.setItem(goneKey, run("gone.json").current.payload)
        assertTrue(ProfileBackup.apply(ProfileBackupCodec.decode(profile(run("test-a.json")))).isSuccess)
        assertNull(store.readCurrent("gone.json"))
        assertNull(localStorage.getItem(goneKey), "a replaced profile must not keep an orphan opening")
    }

    @Test
    fun emptyAndStandaloneOnlyPreviewsSayWhatChanges() {
        installEnglishUiBundleForTests()
        val empty = ProfileBackupPreview.preview(ProfileBackupCodec.decode(profile()), emptyList())
        assertTrue(I18n.t("save_load.profile_import.confirm.incoming_none") in empty, empty)
        assertTrue(I18n.t("save_load.profile_import.confirm.replaces_nothing") in empty, empty)
        assertTrue("0 run" !in empty, "no empty count/list pair: $empty")

        val raw = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        raw.fmt = GameStateSerializer.SAVE_FORMAT_VERSION
        raw.campaign = null
        raw.scenario.name = "Lone battle"
        val standalone = ProfileBackupPreview.preview(ProfileBackupCodec.decode(raw), emptyList())
        assertTrue("Lone battle" in standalone, "a standalone-only file must name its battle: $standalone")
        assertTrue(I18n.t("save_load.profile_import.turn", mapOf("turn" to 1)) in standalone, standalone)
    }

    @Test
    fun importFailuresNameTheirRemedy() {
        assertEquals(
            "save_load.backup.import_quota",
            ProfileBackup.importFailureKey(SaveResult(SaveResultKind.QUOTA_EXCEEDED)),
        )
        assertEquals(
            "save_load.backup.import_unchanged",
            ProfileBackup.importFailureKey(SaveResult(SaveResultKind.STORAGE_UNAVAILABLE)),
        )
        localStorage.setItem(org.osada.save.LocalStorageBatch.JOURNAL_KEY, "[]")
        assertEquals(
            "save_load.backup.import_failed",
            ProfileBackup.importFailureKey(SaveResult(SaveResultKind.QUOTA_EXCEEDED)),
            "only a pending journal asks the player to reload",
        )
    }
}
