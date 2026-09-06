package org.osada

import org.osada.hero.AchievementType
import org.osada.hero.HeroDistinction
import org.osada.hero.HeroDistinctions
import org.osada.hero.HeroId
import org.osada.hero.HeroState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §12 and §17.5: the title is date-correct, side-correct, needs an exceptional recorded deed,
 * cannot be duplicated by replaying an event, and carries no combat effect.
 */
class HeroDistinctionTest {
    private fun hero(vararg distinctions: HeroDistinction) =
        HeroState(heroId = HeroId("H-1"), rankId = "captain", distinctions = distinctions.toList())

    private fun context(
        country: Int? = SOVIET,
        year: Int = 1943,
        date: String? = "1943-08-24",
        scenario: String = "s4",
        turn: Int = 6,
        state: HeroState = hero(),
        achievements: List<AchievementType> = listOf(AchievementType.DESTROYED_STRONGER_ENEMY),
    ) = HeroDistinctions.Context(
        hero = state,
        country = country,
        serviceYear = year,
        date = date,
        scenarioId = scenario,
        turn = turn,
        achievements = achievements,
    )

    @Test
    fun theTitleIsNeverConferredBeforeItExisted() {
        // §12.1: established 16 April 1934. The day matters, not just the year.
        assertNull(HeroDistinctions.evaluate(context(year = 1934, date = "1934-04-15")))
        assertNotNull(HeroDistinctions.evaluate(context(year = 1934, date = "1934-04-16")))
        assertNull(HeroDistinctions.evaluate(context(year = 1919, date = "1919-06-01")))
    }

    @Test
    fun anUndatedScenarioInTheEstablishmentYearIsRefusedRatherThanGuessed() {
        // Without a day, 1934 might be March. §12.5's discipline about never asserting more than
        // the record supports applies to the gate too.
        assertNull(HeroDistinctions.evaluate(context(year = 1934, date = null)))
        assertNotNull(HeroDistinctions.evaluate(context(year = 1935, date = null)))
    }

    @Test
    fun onlyASovietPlayerSideCanReceiveIt() {
        assertNull(HeroDistinctions.evaluate(context(country = 226)), "Spanish Republic")
        assertNull(HeroDistinctions.evaluate(context(country = 103, year = 1919, date = "1919-06-01")), "Red Russia")
        assertNull(HeroDistinctions.evaluate(context(country = null)))
        listOf(19, 61, 89).forEach { assertNotNull(HeroDistinctions.evaluate(context(country = it)), "country $it") }
    }

    @Test
    fun anExceptionalDeedIsRequiredAndAnOrdinaryOneIsNotEnough() {
        // §12.3: "at least one exceptional recorded deed, not only an accumulated XP threshold".
        assertNull(HeroDistinctions.evaluate(context(achievements = emptyList())))
        assertNull(HeroDistinctions.evaluate(context(achievements = listOf(AchievementType.DESTROYED_ENEMY))))
        assertNotNull(
            HeroDistinctions.evaluate(context(achievements = listOf(AchievementType.SURVIVED_CRITICAL_DAMAGE))),
        )
    }

    @Test
    fun replayingTheSameEventCannotConferTheTitleTwice() {
        // §17.5: "replaying or reloading the same event cannot duplicate the award".
        val first = assertNotNull(HeroDistinctions.evaluate(context()))
        val decorated = hero(first)

        assertNull(
            HeroDistinctions.evaluate(context(state = decorated)),
            "the same deed in the same scenario and turn is already cited",
        )
    }

    @Test
    fun aLaterIndependentDeedConfersASecondTitle() {
        val first = assertNotNull(HeroDistinctions.evaluate(context()))
        val decorated = hero(first)

        val second =
            assertNotNull(
                HeroDistinctions.evaluate(context(state = decorated, scenario = "s7", turn = 3, date = "1944-02-11")),
            )
        assertEquals(2, second.sequence)
        assertTrue(second.deedEventIds.none { it in first.deedEventIds })
    }

    @Test
    fun anAwardBeforeNineteenThirtyNineDoesNotClaimAGoldStar() {
        // §12.4: the Gold Star medal was established in 1939, so an earlier award must not show one.
        val early = assertNotNull(HeroDistinctions.evaluate(context(year = 1938, date = "1938-07-25")))
        val later = assertNotNull(HeroDistinctions.evaluate(context(year = 1943, date = "1943-08-24")))

        assertFalse(HeroDistinctions.includesGoldStar(early))
        assertTrue(HeroDistinctions.includesGoldStar(later))
    }

    @Test
    fun theConferralRecordsOnlyWhatTheTriggeringEventKnew() {
        // §12.5: the citation may name nothing the engine did not observe, so the record it is
        // built from carries the scenario, the turn and the date and nothing invented.
        val conferral = assertNotNull(HeroDistinctions.evaluate(context()))

        assertEquals(HeroDistinctions.HERO_OF_THE_SOVIET_UNION, conferral.distinctionId)
        assertEquals("s4", conferral.scenarioId)
        assertEquals(6, conferral.turn)
        assertEquals("1943-08-24", conferral.date)
        assertEquals(1, conferral.sequence)
        assertFalse(conferral.posthumous)
    }

    private companion object {
        const val SOVIET = 61
    }
}
