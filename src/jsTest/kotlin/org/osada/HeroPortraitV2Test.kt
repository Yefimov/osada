package org.osada

import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroInjury
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.PortraitComposerV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the in-game v2 portrait composer (design brief §15): it produces ordered v2 layer ids,
 * is deterministic (§29.17), keeps female officers clean-shaven with female hair, and evolves a
 * hero's portrait with their wounds and scars (§11.1) while holding the base identity fixed.
 */
class HeroPortraitV2Test {
    private fun facts(
        branch: String = "infantry",
        gender: String = "male",
        rank: String = "captain",
        age: String = "middle",
        season: String = "winter",
        scar: Boolean = false,
        wound: String? = null,
    ) = PortraitComposerV2.Facts(branch, gender, rank, age, season, scar, wound)

    @Test
    fun composeProducesOrderedV2Layers() {
        val ids = PortraitComposerV2.compose(facts(branch = "infantry", rank = "captain", season = "winter"), 123)
        assertTrue(ids.first().startsWith("bg_"), "background stacks first")
        assertTrue(ids.any { it.startsWith("face_") }, "v2 uses a face archetype, not v1 head/eyes/nose")
        assertTrue("back_infantry" in ids)
        assertTrue("rank_captain" in ids)
        assertTrue("branch_infantry" in ids)
        assertTrue("collar_infantry_winter" in ids)
    }

    @Test
    fun compositionIsDeterministic() {
        val f = facts(branch = "armor", rank = "major", age = "old", season = "summer")
        assertEquals(PortraitComposerV2.compose(f, 77), PortraitComposerV2.compose(f, 77))
    }

    @Test
    fun femaleOfficersAreCleanShavenWithFemaleHair() {
        (1..20).forEach { seed ->
            val ids = PortraitComposerV2.compose(facts(gender = "female", rank = "lieutenant", age = "young"), seed)
            assertTrue("facial_clean" in ids, "female must be clean-shaven at seed $seed")
            assertTrue("hair_back_female" in ids, "female must keep hair at seed $seed")
        }
    }

    @Test
    fun aviationUsesFlightHelmetOrBareAndAviationCollar() {
        (1..20).forEach { seed ->
            val ids = PortraitComposerV2.compose(facts(branch = "aviation", rank = "captain"), seed)
            assertTrue("collar_aviation" in ids)
            val headgear = ids.firstOrNull { it.startsWith("headgear_") }
            assertTrue(headgear == null || headgear == "headgear_flight_helmet" || headgear == "headgear_officer_cap")
        }
    }

    @Test
    fun forHeroEvolvesWithWoundsAndScars() {
        val heroId = HeroId("H-p")
        val definition =
            HeroDefinition(
                id = heroId,
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Ivan",
                backgroundId = "armored_academy_graduate",
                biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
                portrait = PortraitComposerV2.composeFor(9, UnitClass.TANK.value, "major", null, null),
            )
        val healthy = HeroState(heroId = heroId, rankId = "major")
        val wounded =
            healthy.copy(
                status = HeroStatus.WOUNDED,
                injuries = listOf(HeroInjury("serious_wound", "1", permanent = true)),
            )

        val healthyPaths = PortraitComposerV2.forHero(definition, healthy, UnitClass.TANK.value)
        val woundedPaths = PortraitComposerV2.forHero(definition, wounded, UnitClass.TANK.value)

        assertTrue(healthyPaths.all { it.startsWith("portraits/v2/layers/") }, "renders the v2 assets")
        assertTrue(healthyPaths.none { it.contains("/wound/") }, "a healthy commander has no wound overlay")
        assertTrue(woundedPaths.any { it.contains("/wound/") }, "a wounded commander shows a wound (§11.1)")
        assertTrue(woundedPaths.any { it.contains("/scar/") }, "a permanent injury shows a scar (§11.1)")
    }
}
