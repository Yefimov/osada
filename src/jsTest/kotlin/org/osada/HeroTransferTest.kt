package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroBalance
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.HeroTransferService
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
 * Leader transfers between formations (`docs/heroes/README.md` §2): the "way back" for a commander
 * `HeroCampaign.applyCasualty` detaches on a light wound/evacuation, closing the state that used to
 * leave "Wounded" and "Reserve" terminal in practice.
 */
class HeroTransferTest {
    private val heroId = HeroId("H-T1")
    private val emptyFormationId = FormationId("F-T1")
    private val ledFormationId = FormationId("F-T2")
    private lateinit var map: GameMap
    private lateinit var player: Player

    @BeforeTest
    fun openInitialDeploymentWindow() {
        val game = Game()
        val scenario = Scenario(null)
        player = Player().apply { id = 0 }
        map = scenario.map
        map.addPlayer(player)
        map.currentPlayer = player
        game.scenario = scenario
        game.campaignPlayer = player
        enterCampaignForTests(player)
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

    private fun activeState(
        formationId: FormationId,
        id: HeroId = heroId,
    ) = HeroState(
        heroId = id,
        rankId = "major",
        status = HeroStatus.ACTIVE,
        assignedFormationId = formationId,
    )

    /**
     * A roster record for [id] AND, unless [inArmy] is false, the campaign player's reserve unit that
     * carries it: a transfer target must be a formation actually in the army (§3.3 of the
     * combat/heroes audit), and a reserve unit that is not on the map counts.
     */
    private fun formation(
        id: FormationId,
        assignedHeroId: HeroId? = null,
        inArmy: Boolean = true,
        ownerId: Int = 0,
    ): CoreFormation {
        if (inArmy) {
            val reserveUnit =
                GameUnit(900).apply {
                    owner = ownerId
                    formationId = id.value
                }
            player.setCoreUnitList(player.getCoreUnitList() + reserveUnit)
        }
        return CoreFormation(
            id = id,
            ownerId = ownerId,
            country = 19,
            displayName = "Test Formation ${id.value}",
            currentEquipmentId = 900,
            unitClass = UnitClass.TANK.value,
            assignedHeroId = assignedHeroId,
        )
    }

    @AfterTest
    fun cleanup() {
        HeroCampaign.reset()
        leaveCampaignForTests()
        GameHolder.instance = null
    }

    @Test
    fun aWoundedUnassignedCommanderCanTransferToAnUnledFormation() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.WOUNDED),
        )

        assertTrue(HeroTransferService.transferCommander(heroId, emptyFormationId))

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

        assertEquals(listOf(emptyFormationId), HeroTransferService.transferableFormations(heroId).map { it.id })
    }

    @Test
    fun anActiveCommanderCanBePostedToAnUnledFormation() {
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = heroId))
        HeroCampaign.roster().putHero(definition(), activeState(ledFormationId))
        HeroCampaign.roster().putFormation(formation(emptyFormationId))

        assertEquals(listOf(emptyFormationId), HeroTransferService.transferableFormations(heroId).map { it.id })
        assertTrue(HeroTransferService.transferCommander(heroId, emptyFormationId))

        val hero = assertNotNull(HeroCampaign.roster().state(heroId))
        assertEquals(emptyFormationId, hero.assignedFormationId)
        assertNull(
            assertNotNull(HeroCampaign.roster().formation(ledFormationId)).assignedHeroId,
            "the formation the commander left must be unled, not still pointing at them",
        )
    }

    @Test
    fun aCommandersOwnFormationIsNeverOfferedAsATransferTarget() {
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = heroId))
        HeroCampaign.roster().putHero(definition(), activeState(ledFormationId))

        assertEquals(emptyList(), HeroTransferService.transferableFormations(heroId))
        assertFalse(HeroTransferService.transferCommander(heroId, ledFormationId))
    }

    @Test
    fun twoActiveCommandersExchangeFormations() {
        val otherHeroId = HeroId("H-T2")
        HeroCampaign.roster().putFormation(formation(emptyFormationId, assignedHeroId = heroId))
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = otherHeroId))
        HeroCampaign.roster().putHero(definition(), activeState(emptyFormationId))
        HeroCampaign.roster().putHero(definition().copy(id = otherHeroId), activeState(ledFormationId, otherHeroId))

        assertTrue(HeroTransferService.transferCommander(heroId, ledFormationId))

        assertEquals(ledFormationId, assertNotNull(HeroCampaign.roster().state(heroId)).assignedFormationId)
        assertEquals(
            emptyFormationId,
            assertNotNull(HeroCampaign.roster().state(otherHeroId)).assignedFormationId,
            "the incumbent takes the other officer's formation rather than being left with none",
        )
        assertEquals(heroId, assertNotNull(HeroCampaign.roster().formation(ledFormationId)).assignedHeroId)
        assertEquals(otherHeroId, assertNotNull(HeroCampaign.roster().formation(emptyFormationId)).assignedHeroId)
    }

    @Test
    fun bothSidesOfAnExchangeAreSettlingInAndGrantNoTraits() {
        val otherHeroId = HeroId("H-T2")
        HeroCampaign.roster().putFormation(formation(emptyFormationId, assignedHeroId = heroId))
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = otherHeroId))
        HeroCampaign.roster().putHero(definition(), activeState(emptyFormationId))
        HeroCampaign.roster().putHero(definition().copy(id = otherHeroId), activeState(ledFormationId, otherHeroId))

        HeroTransferService.transferCommander(heroId, ledFormationId)

        listOf(heroId, otherHeroId).forEach { id ->
            val hero = assertNotNull(HeroCampaign.roster().state(id))
            assertTrue(HeroTransferService.isSettlingIn(hero), "$id must be settling into its new formation")
            assertEquals(HeroBalance.DEFAULT.transferSettlingTurns, HeroTransferService.settlingTurnsLeft(hero))
        }
    }

    @Test
    fun settlingInEndsOnceTheFormationHasBeenCommandedLongEnough() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )
        HeroTransferService.transferCommander(heroId, emptyFormationId)

        val hero = assertNotNull(HeroCampaign.roster().state(heroId))
        map.turn = map.turn + HeroBalance.DEFAULT.transferSettlingTurns
        assertFalse(HeroTransferService.isSettlingIn(hero), "the settling period is over once its last turn passes")
        assertEquals(0, HeroTransferService.settlingTurnsLeft(hero))
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
            assertEquals(
                emptyList(),
                HeroTransferService.transferableFormations(id),
                "$status must not be transfer-eligible",
            )
            assertFalse(HeroTransferService.transferCommander(id, emptyFormationId), "$status must refuse the transfer")
        }
    }

    /** An exchange needs two seats. An UNASSIGNED officer has none to offer, so posting them onto a
     *  led formation would displace its commander to nowhere — refused, not silently resolved. */
    @Test
    fun cannotTransferOntoAFormationThatAlreadyHasACommander() {
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = HeroId("H-other")))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.WOUNDED),
        )

        assertFalse(HeroTransferService.transferCommander(heroId, ledFormationId))
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

        HeroTransferService.transferCommander(heroId, emptyFormationId)

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

        assertEquals(emptyList(), HeroTransferService.transferableFormations(heroId))
        assertFalse(HeroTransferService.transferCommander(heroId, emptyFormationId))
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

        assertFalse(HeroTransferService.transferCommander(heroId, emptyFormationId))
    }

    /** A formation destroyed in an earlier battle keeps its record, but nobody can be posted to it. */
    @Test
    fun aDestroyedFormationIsNotATransferTarget() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId, inArmy = false))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )

        assertEquals(emptyList(), HeroTransferService.transferableFormations(heroId))
        assertFalse(HeroTransferService.transferCommander(heroId, emptyFormationId))
        assertNotNull(HeroCampaign.roster().formation(emptyFormationId), "the record itself is kept")
    }

    /** An enemy formation an AI purchase once enrolled is never a target, even if its unit is present. */
    @Test
    fun anEnemyFormationIsNotATransferTarget() {
        HeroCampaign.roster().putFormation(formation(emptyFormationId, ownerId = 1))
        HeroCampaign.roster().putHero(
            definition(),
            HeroState(heroId = heroId, rankId = "major", status = HeroStatus.RESERVE),
        )

        assertEquals(emptyList(), HeroTransferService.transferableFormations(heroId))
        assertFalse(HeroTransferService.transferCommander(heroId, emptyFormationId))
    }

    /** An officer whose own formation is gone has no seat to hand an incumbent, so an exchange is
     *  refused rather than posting the incumbent into a formation that no longer exists. */
    @Test
    fun anExchangeNeedsTheOfferedSeatToStillExist() {
        val otherHeroId = HeroId("H-T2")
        HeroCampaign.roster().putFormation(formation(emptyFormationId, assignedHeroId = heroId, inArmy = false))
        HeroCampaign.roster().putFormation(formation(ledFormationId, assignedHeroId = otherHeroId))
        HeroCampaign.roster().putHero(definition(), activeState(emptyFormationId))
        HeroCampaign.roster().putHero(definition().copy(id = otherHeroId), activeState(ledFormationId, otherHeroId))

        assertFalse(HeroTransferService.transferCommander(heroId, ledFormationId))
        assertEquals(ledFormationId, assertNotNull(HeroCampaign.roster().state(otherHeroId)).assignedFormationId)
    }
}
