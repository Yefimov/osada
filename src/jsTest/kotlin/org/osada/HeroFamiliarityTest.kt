package org.osada

import org.osada.hero.FormationId
import org.osada.hero.HeroBalance
import org.osada.hero.HeroEvent
import org.osada.hero.HeroFamiliarity
import org.osada.hero.HeroId
import org.osada.hero.HeroState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §5.1 and §17.4's familiarity rules: an unfamiliar formation still costs the full three turns, a
 * return costs one, and both verdicts are derived from appointment history rather than from a
 * stored affinity score that could drift.
 */
class HeroFamiliarityTest {
    private val first = FormationId("F-1")
    private val second = FormationId("F-2")

    private fun hero(
        assigned: FormationId?,
        vararg events: HeroEvent,
    ) = HeroState(
        heroId = HeroId("H-1"),
        rankId = "captain",
        assignedFormationId = assigned,
        serviceEvents = events.toList(),
    )

    private fun appointment(
        formation: FormationId,
        scenario: String,
        eventId: String = "emerged",
    ) = HeroEvent(eventId, scenario, turn = 1, formationId = formation)

    @Test
    fun anUnfamiliarFormationStillCostsTheFullSettlingPeriod() {
        // §18's acceptance criteria keep the three-turn penalty: it is the whole restraint on
        // shuffling one favourite officer into whichever brigade is about to fight.
        val commander = hero(first, appointment(first, "s1"))

        assertEquals(
            HeroBalance.DEFAULT.transferSettlingTurns,
            HeroFamiliarity.settlingTurnsFor(commander, second),
        )
        assertFalse(HeroFamiliarity.hasCommandedBefore(commander, second))
    }

    @Test
    fun returningToAPreviouslyCommandedFormationCostsOneTurn() {
        val commander =
            hero(
                second,
                appointment(first, "s1"),
                appointment(second, "s3", "transferred"),
            )

        assertTrue(HeroFamiliarity.hasCommandedBefore(commander, first))
        assertEquals(HeroBalance.DEFAULT.returnSettlingTurns, HeroFamiliarity.settlingTurnsFor(commander, first))
    }

    @Test
    fun postingAnOfficerToTheFormationTheyAlreadyHoldCostsNothing() {
        // Guards the shape of bug this code invites: a no-op post that suppresses a commander's own
        // traits for three turns.
        val commander = hero(first, appointment(first, "s1"))

        assertEquals(0, HeroFamiliarity.settlingTurnsFor(commander, first))
    }

    @Test
    fun familiarityIsDerivedFromEventsThatCarryAFormationId() {
        // §5.3: "Never reconstruct lineage from display names." An event with no formation id --
        // every event in a save written before this feature -- proves nothing about lineage.
        val legacy = hero(second, HeroEvent("transferred", "s1", turn = 1))

        assertFalse(HeroFamiliarity.hasCommandedBefore(legacy, first))
        assertEquals(HeroBalance.DEFAULT.transferSettlingTurns, HeroFamiliarity.settlingTurnsFor(legacy, first))
    }

    @Test
    fun tenureRisesWithCompletedScenariosAndFlagsAReturn() {
        assertEquals(
            HeroFamiliarity.Tenure.NEWLY_APPOINTED,
            HeroFamiliarity.tenureFor(hero(first, appointment(first, "s1"))),
        )
        assertEquals(
            HeroFamiliarity.Tenure.ESTABLISHED,
            HeroFamiliarity.tenureFor(
                hero(
                    first,
                    appointment(first, "s1"),
                    HeroEvent("destroyed_enemy", "s2", turn = 3, formationId = first),
                ),
            ),
        )
        assertEquals(
            HeroFamiliarity.Tenure.RETURNED,
            HeroFamiliarity.tenureFor(
                hero(
                    first,
                    appointment(first, "s1"),
                    appointment(first, "s4", "returned"),
                ),
            ),
        )
    }

    @Test
    fun previousFormationsExcludeTheOneCurrentlyHeld() {
        val commander =
            hero(
                second,
                appointment(first, "s1"),
                appointment(second, "s3", "transferred"),
            )

        assertEquals(listOf(first), HeroFamiliarity.previousFormations(commander))
    }
}
