package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationEvent
import org.osada.hero.FormationId
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroEvent
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroPotential
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.LeaderAcquisitionService
import org.osada.hero.LegendaryHeroPool
import org.osada.hero.PortraitComposition
import org.osada.hero.RecognitionService
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import org.osada.model.ensureFormationIds
import org.osada.model.recordsInCampaignDossier
import org.osada.scenario.Scenario
import org.osada.ui.attackPreviewAllowsCell
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeroSystemFixesTest {
    @AfterTest
    fun resetHeroes() = HeroCampaign.reset()

    @Test
    fun everyControlledUnitPathIsEnrolledExceptExplicitBorrowedUnits() {
        val player = Player().apply { id = 0 }
        val map = GameMap()
        val prePlaced = controlledUnit(player)
        val tray = controlledUnit(player).also(player::addCoreUnit)
        val scriptedReinforcement = controlledUnit(player)
        val restored = controlledUnit(player).apply { formationId = "F-0-8" }
        val borrowed = controlledUnit(player).apply { isTemporaryBorrowed = true }
        val enemy = GameUnit(1).apply { owner = 1 }
        map.units += listOf(prePlaced, restored, borrowed, enemy)

        map.ensureFormationIds(player, listOf(scriptedReinforcement))

        listOf(prePlaced, tray, scriptedReinforcement, restored).forEach {
            assertNotNull(FormationIdentity.of(it), "controlled campaign unit was not enrolled")
        }
        assertEquals("F-0-8", restored.formationId, "restored identity must not be reminted")
        assertNull(FormationIdentity.of(borrowed), "only an explicitly temporary borrowed unit opts out")
        assertNull(FormationIdentity.of(enemy), "another player's unit is not directly controlled")
    }

    @Test
    fun temporaryBorrowedMarkerRoundTripsWithoutChangingOrdinarySaveShape() {
        val borrowed = GameUnit(1).apply { isTemporaryBorrowed = true }
        val borrowedJson = JSON.stringify(GameStateSerializer.serializeUnit(borrowed))
        assertTrue(borrowedJson.contains("temporaryBorrowed"))
        assertTrue(GameStateDeserializer.deserializeUnit(JSON.parse(borrowedJson)).isTemporaryBorrowed)

        val ordinaryJson = JSON.stringify(GameStateSerializer.serializeUnit(GameUnit(1)))
        assertFalse(ordinaryJson.contains("temporaryBorrowed"), "false stays absent for save compatibility")
    }

    @Test
    fun specializationEvidenceSurvivesJsonSaveRoundTrip() {
        val heroId = HeroId("H-EVIDENCE")
        val roster =
            HeroRoster().apply {
                putHero(
                    HeroDefinition(
                        id = heroId,
                        origin = HeroOrigin.PROCEDURAL,
                        displayName = "Test Officer",
                        backgroundId = "infantry_school_instructor",
                        biographyFacts = HeroBiographyFacts(emergenceEventId = "test"),
                        portrait = PortraitComposition(seed = 1),
                    ),
                    HeroState(
                        heroId = heroId,
                        rankId = "captain",
                        specializationEvidence = mapOf("URBAN_COMBAT" to 31, "RIVER_OPERATIONS" to 12),
                        serviceEvents =
                            listOf(HeroEvent("held_under_attack", "Battle of Sesena", 9, "1936-11-14", "Sesena")),
                    ),
                )
                putFormation(
                    CoreFormation(
                        id = FormationId("F-HISTORY"),
                        ownerId = 0,
                        country = 19,
                        displayName = "Infantry",
                        currentEquipmentId = 1,
                        unitClass = UnitClass.INFANTRY.value,
                        history =
                            listOf(FormationEvent("held_under_attack", "Battle of Sesena", 9, "1936-11-14", "Sesena")),
                    ),
                )
            }

        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))

        assertEquals(
            mapOf("URBAN_COMBAT" to 31, "RIVER_OPERATIONS" to 12),
            assertNotNull(restored.state(heroId)).specializationEvidence,
        )
        val event = assertNotNull(restored.state(heroId)).serviceEvents.single()
        assertEquals("1936-11-14", event.date)
        assertEquals("Sesena", event.location)
        assertEquals(
            "1936-11-14",
            restored
                .formation(FormationId("F-HISTORY"))
                ?.history
                ?.single()
                ?.date,
        )
    }

    @Test
    fun reservationFiltersCampaignNationDateAndAvailableClasses() {
        val authored = LegendaryHeroPool.reserve("camp6.json", 61, 1942, setOf(UnitClass.TANK.value))
        assertNotNull(LegendaryHeroPool.byId(authored))
        assertTrue(
            LegendaryHeroPool.reservationCompatible(
                authored,
                "camp6.json",
                61,
                1942,
                setOf(UnitClass.TANK.value),
            ),
        )

        assertEquals(
            LegendaryHeroPool.PROCEDURAL_FALLBACK_ID,
            LegendaryHeroPool.reserve("unrelated.json", 7, 1936, setOf(UnitClass.SUBMARINE.value)),
        )
    }

    @Test
    fun proceduralReservationFallbackIsForcedAndCompatibleWithItsFormation() {
        val formation =
            CoreFormation(
                id = FormationId("F-FALLBACK"),
                ownerId = 0,
                country = 7,
                displayName = "Naval Detachment",
                currentEquipmentId = 1,
                unitClass = UnitClass.SUBMARINE.value,
                recognition = 100,
                emergenceChecks = 1,
            )
        val result =
            LeaderAcquisitionService.tryGenerate(
                LeaderAcquisitionService.EmergenceContext(
                    campaignId = "unrelated.json",
                    scenarioIndex = org.osada.hero.HeroBalance.DEFAULT.legendaryGuaranteedByScenarioIndex,
                    formation = formation,
                    event = org.osada.hero.EmergenceEvent.DISTINGUISHED_SERVICE,
                    campaignDrought = 0,
                    country = 7,
                    unitExperience = 100,
                    serviceYear = 1936,
                    proceduralLegendaryFallback = true,
                    // resolveEarlyLegendary bails out before checking proceduralLegendaryFallback
                    // when this is <= 0 (LeaderAcquisitionService.kt's reservedPending guard).
                    earlyLegendaryQualifyingCombats = 1,
                ),
            ) as LeaderAcquisitionService.EmergenceResult.Emerged

        assertTrue(result.consumedReservation)
        assertFalse(result.legendary)
        assertEquals(HeroOrigin.PROCEDURAL, result.definition.origin)
        assertEquals(HeroPotential.DISTINGUISHED, result.state.potential)
        assertEquals(formation.id, result.state.assignedFormationId)
    }

    @Test
    fun surrenderCasualtyIsProcessedAfterCombat() {
        val formationId = FormationId("F-SURRENDER")
        val heroId = HeroId("H-SURRENDER")
        val roster =
            HeroRoster().apply {
                putFormation(
                    CoreFormation(
                        id = formationId,
                        ownerId = 0,
                        country = 61,
                        displayName = "1st Brigade",
                        currentEquipmentId = 1,
                        unitClass = UnitClass.INFANTRY.value,
                        assignedHeroId = heroId,
                    ),
                )
                putHero(
                    HeroDefinition(
                        id = heroId,
                        origin = HeroOrigin.PROCEDURAL,
                        displayName = "Commander",
                        backgroundId = "infantry_school_instructor",
                        biographyFacts = HeroBiographyFacts(emergenceEventId = "test"),
                        portrait = PortraitComposition(seed = 2),
                    ),
                    HeroState(heroId = heroId, rankId = "captain", assignedFormationId = formationId),
                )
            }
        HeroCampaign.restore(HeroSerializer.serialize(roster))
        val surrendered =
            GameUnit(1).apply {
                owner = 0
                this.formationId = formationId.value
                destroyed = true
                this.surrendered = true
            }

        assertTrue(HeroCampaign.recordCasualty(surrendered, turn = 3))

        assertEquals(1, HeroCampaign.drainCasualties().size)
        assertTrue(assertNotNull(HeroCampaign.roster().state(heroId)).serviceEvents.isNotEmpty())
    }

    @Test
    fun recognitionExposesThreeStagesAndExactProgress() {
        val target = org.osada.hero.HeroBalance.DEFAULT.recognitionEmergenceFloor
        assertEquals(0, RecognitionService.progress(0).filledStages)
        assertEquals(1, RecognitionService.progress(1).filledStages)
        assertEquals(2, RecognitionService.progress(target / 2).filledStages)
        val ready = RecognitionService.progress(target + 7)
        assertEquals(3, ready.filledStages)
        assertEquals(target + 7, ready.recognition)
        assertEquals(target, ready.target)
    }

    @Test
    fun shortRangeAircraftPreviewSeparatesGroundAndAirTargets() {
        val adjacent = org.osada.model.Cell(4, 5)
        assertFalse(attackPreviewAllowsCell(true, 1, false, 4, 4, adjacent))
        assertTrue(attackPreviewAllowsCell(true, 1, true, 4, 4, adjacent))
        assertTrue(attackPreviewAllowsCell(true, 1, false, 4, 4, org.osada.model.Cell(4, 4)))
    }

    @Test
    fun timedObjectiveThresholdsProduceOgOutcome() {
        val scenario = Scenario(null)
        val player =
            Player().apply {
                id = 0
                side = 0
                playedTurn = 8
            }
        val enemy =
            Player().apply {
                id = 1
                side = 1
                playedTurn = 8
            }
        scenario.map.apply {
            rows = 1
            cols = 4
            turn = 8
            maxTurns = 8
            addPlayer(player)
            addPlayer(enemy)
            allocMap()
            map!![0].forEachIndexed { index, hex ->
                hex.victorySide = 0
                hex.owner = if (index < 2) player.id else enemy.id
            }
        }
        scenario.victoryHoldCounts = listOf(3, 2, 1)

        assertEquals("victory", scenario.checkTimedOutcome(0, 0))
        scenario.map.map!![0][1].owner = enemy.id
        assertEquals("tactical", scenario.checkTimedOutcome(0, 0))
    }

    @Test
    fun normalCampaignLossesAreRecordedButExplicitNoDossierUnitsAreNot() {
        assertTrue(recordsInCampaignDossier(hasCampaign = true, noDossier = false))
        assertFalse(recordsInCampaignDossier(hasCampaign = true, noDossier = true))
        assertFalse(recordsInCampaignDossier(hasCampaign = false, noDossier = false))
    }

    private fun controlledUnit(player: Player) =
        GameUnit(1).apply {
            owner = player.id
            this.player = player
        }
}
