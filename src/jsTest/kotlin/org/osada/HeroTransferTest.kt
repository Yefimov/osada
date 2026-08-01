package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.PortraitComposition
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leader transfers between formations (DEFERRED.md §1.10): the "way back" for a commander
 * `HeroCampaign.applyCasualty` detaches on a light wound/evacuation, closing the state that used to
 * leave "Wounded" and "Reserve" terminal in practice.
 */
class HeroTransferTest {
    private val heroId = HeroId("H-T1")
    private val emptyFormationId = FormationId("F-T1")
    private val ledFormationId = FormationId("F-T2")
    private lateinit var map: GameMap

    @BeforeTest
    fun openInitialDeploymentWindow() {
        val game = Game()
        val scenario = Scenario(null)
        val player = Player().apply { id = 0 }
        map = scenario.map
        map.addPlayer(player)
        map.currentPlayer = player
        game.scenario = scenario
        game.campaignPlayer = player
    }

    private fun definition() =
        HeroDefinition(
            id = heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = "Ivan Orlov",
            backgroundId = "armored_academy_graduate",
            biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
            portrait = PortraitComposition(seed = 3),
        )

    private fun formation(
        id: FormationId,
        assignedHeroId: HeroId? = null,
    ) = CoreFormation(
        id = id,
        ownerId = 0,
        country = 19,
        displayName = "Test Formation ${id.value}",
        currentEquipmentId = 900,
        unitClass = UnitClass.TANK.value,
        assignedHeroId = assignedHeroId,
    )

    @AfterTest
    fun cleanup() {
        HeroCampaign.reset()
        GameHolder.instance = null
    }

    @Test
    fun aWoundedUnassignedCommanderCanTransferToAnUnledFormation() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.WOUNDED),
        )

        assertTrue(HeroCampaign.transferCommander(heroId, emptyFormationId))

        val hero = assertNotNull(HeroCampaign.roster().state(heroId))
        assertEquals(HeroStatus.ACTIVE, hero.status, "a transferred commander returns to active service")
        assertEquals(emptyFormationId, hero.assignedFormationId)
        val target = assertNotNull(HeroCampaign.roster().formation(emptyFormationId))
        assertEquals(heroId, target.assignedHeroId)
    }

    @Test
    fun anEvacuatedReserveCommanderIsAlsoEligible() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )

        assertEquals(listOf(emptyFormationId), HeroCampaign.transferableFormations(heroId).map { it.id })
    }

    @Test
    fun anActiveCommanderCannotBeTransferredOutFromUnderTheirFormation() {
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = heroId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(
                heroId = heroId,
                rankId = "major",
                status = HeroStatus.ACTIVE,
                assignedFormationId = ledFormationId,
            ),
        )
        HeroCampaign.roster().putFormation(formation(emptyFormationId))

        assertEquals(emptyList(), HeroCampaign.transferableFormations(heroId))
        assertFalse(HeroCampaign.transferCommander(heroId, emptyFormationId))
    }

    @Test
    fun aMissingOrCapturedCommanderCannotBeTransferred() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        listOf(HeroStatus.MISSING, HeroStatus.CAPTURED, HeroStatus.KILLED, HeroStatus.RETIRED).forEach { status ->
            val id = HeroId("H-${status.name}")
            HeroCampaign.roster().putHero(
                definition().copy(id = id),
                HeroState(heroId = id, rankId = "major", status = status),
            )
            assertEquals(emptyList(), HeroCampaign.transferableFormations(id), "$status must not be transfer-eligible")
            assertFalse(HeroCampaign.transferCommander(id, emptyFormationId), "$status must refuse the transfer")
        }
    }

    @Test
    fun cannotTransferOntoAFormationThatAlreadyHasACommander() {
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = HeroId("H-other")))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.WOUNDED),
        )

        assertFalse(HeroCampaign.transferCommander(heroId, ledFormationId))
        val hero = assertNotNull(HeroCampaign.roster().state(heroId))
        assertEquals(HeroStatus.WOUNDED, hero.status, "a refused transfer must not mutate the hero")
        assertNull(hero.assignedFormationId)
    }

    @Test
    fun transferRecordsAServiceEventAndAFormationEvent() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )

        HeroCampaign.transferCommander(heroId, emptyFormationId)

        val hero = assertNotNull(HeroCampaign.roster().state(heroId))
        assertTrue(hero.serviceEvents.any { it.eventId == "transferred" })
        val target = assertNotNull(HeroCampaign.roster().formation(emptyFormationId))
        assertTrue(target.history.any { it.eventId == "commander_transferred" })
    }

    @Test
    fun commanderTransferClosesAfterTheFirstUnitAction() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )
        map.units +=
            GameUnit(1).apply {
                owner = 0
                hasMoved = true
            }

        assertEquals(emptyList(), HeroCampaign.transferableFormations(heroId))
        assertFalse(HeroCampaign.transferCommander(heroId, emptyFormationId))
        assertEquals(HeroStatus.RESERVE, assertNotNull(HeroCampaign.roster().state(heroId)).status)
    }

    @Test
    fun commanderTransferDoesNotReopenOnALaterTurn() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )
        map.turn = 2

        assertFalse(HeroCampaign.transferCommander(heroId, emptyFormationId))
    }
}
