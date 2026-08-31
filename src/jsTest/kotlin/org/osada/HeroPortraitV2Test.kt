package org.osada

import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroInjury
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.PortraitComposerV2
import org.osada.hero.PortraitComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun campaignCountriesResolveToTheirHistoricalPortraitPool() {
        val expected =
            mapOf(
                19 to PortraitComposerV2.Pool.USSR_1942,
                61 to PortraitComposerV2.Pool.USSR_1942,
                89 to PortraitComposerV2.Pool.USSR_1942,
                103 to PortraitComposerV2.Pool.REVOLUTION_1919,
                144 to PortraitComposerV2.Pool.REVOLUTION_1919,
                187 to PortraitComposerV2.Pool.REVOLUTION_1919,
                188 to PortraitComposerV2.Pool.REVOLUTION_1919,
                196 to PortraitComposerV2.Pool.REVOLUTION_1919,
                226 to PortraitComposerV2.Pool.SPANISH_REPUBLIC_1936,
                43 to PortraitComposerV2.Pool.YUGOSLAV_PARTISAN_1941,
                21 to PortraitComposerV2.Pool.EAST_ASIAN_REVOLUTIONARY,
                25 to PortraitComposerV2.Pool.EAST_ASIAN_REVOLUTIONARY,
                276 to PortraitComposerV2.Pool.EAST_ASIAN_REVOLUTIONARY,
                39 to PortraitComposerV2.Pool.GREEK_1940,
                100 to PortraitComposerV2.Pool.WHITE_ARMY_1919,
                310 to PortraitComposerV2.Pool.ANCIENT_REBEL,
            )

        expected.forEach { (country, pool) ->
            assertEquals(pool, PortraitComposerV2.poolFor(country), "country $country")
        }
    }

    @Test
    fun sovietInterwarCampaignDoesNotUseWartimeShoulderBoards() {
        val portrait =
            PortraitComposerV2.composeFor(
                seed = 17,
                unitClass = UnitClass.INFANTRY.value,
                rankId = "captain",
                birthYear = 1905,
                serviceYear = 1936,
                country = 19,
            )

        assertEquals(PortraitComposerV2.Pool.SOVIET_INTERWAR.id, portrait.poolId)
        assertTrue("rank_rev1919_captain" in portrait.layerIds)
        assertFalse("rank_captain" in portrait.layerIds)
    }

    @Test
    fun nonSovietPoolsUseTheirOwnCollarAndRankLayers() {
        val expected =
            mapOf(
                103 to listOf("collar_rev1919_field", "rank_rev1919_major"),
                226 to listOf("collar_spanish_republic", "rank_spanish_major"),
                43 to listOf("collar_yugoslav_partisan", "rank_yugoslav_major"),
                21 to listOf("collar_east_asian_field", "rank_east_asian_major"),
                39 to listOf("collar_greek_1940", "rank_greek_major"),
                100 to listOf("collar_white_army_1919", "rank_white_major"),
                310 to listOf("collar_ancient_rebel", "rank_ancient_major"),
            )

        expected.forEach { (country, nationalLayers) ->
            val serviceYear =
                when (country) {
                    310 -> -72
                    21 -> 1935
                    226 -> 1937
                    43 -> 1943
                    39 -> 1941
                    else -> 1919
                }
            val ids =
                PortraitComposerV2
                    .composeFor(77, UnitClass.TANK.value, "major", serviceYear - 35, serviceYear, country = country)
                    .layerIds
            nationalLayers.forEach { assertTrue(it in ids, "country $country should use $it") }
            assertFalse("rank_major" in ids, "country $country must not use Soviet rank plates")
            assertFalse(ids.any { it.startsWith("collar_armor_") }, "country $country must not use Soviet collar tabs")
        }
    }

    @Test
    fun unknownCountryUsesMonogramFallback() {
        val portrait =
            PortraitComposerV2.composeFor(
                seed = 73,
                unitClass = UnitClass.INFANTRY.value,
                rankId = "captain",
                birthYear = null,
                serviceYear = 1942,
                country = 999,
            )

        assertTrue(portrait.layerIds.isEmpty())
        assertEquals(PortraitComposerV2.Pool.NONE.id, portrait.poolId)
    }

    @Test
    fun savedNoPortraitVerdictSurvivesWithoutFormationCountry() {
        val heroId = HeroId("H-unknown")
        val definition =
            HeroDefinition(
                id = heroId,
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Unknown officer",
                backgroundId = "partisan_organizer",
                biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
                portrait =
                    PortraitComposerV2.composeFor(
                        seed = 73,
                        unitClass = UnitClass.INFANTRY.value,
                        rankId = "captain",
                        birthYear = null,
                        serviceYear = 1942,
                        country = 999,
                    ),
            )

        val roster = HeroRoster()
        roster.putHero(definition, HeroState(heroId = heroId, rankId = "captain"))
        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))
        val restoredDefinition = checkNotNull(restored.definition(heroId))
        assertEquals(PortraitComposerV2.Pool.NONE.id, restoredDefinition.portrait.poolId)

        val rendered =
            PortraitComposerV2.forHero(
                restoredDefinition,
                HeroState(heroId = heroId, rankId = "captain"),
                UnitClass.INFANTRY.value,
                country = null,
            )

        assertTrue(rendered.isEmpty(), "the detached hero must keep the saved monogram fallback")
    }

    @Test
    fun storedPortraitIdentityWinsAfterPoolsAreAdded() {
        val heroId = HeroId("H-existing")
        val stored = PortraitComposerV2.compose(facts(branch = "armor", rank = "major"), 11)
        val definition =
            HeroDefinition(
                id = heroId,
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Existing officer",
                backgroundId = "armored_academy_graduate",
                biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
                portrait = PortraitComposition(seed = 11, layerIds = stored),
            )

        val rendered =
            PortraitComposerV2.forHero(
                definition,
                HeroState(heroId = heroId, rankId = "major"),
                UnitClass.TANK.value,
                country = 226,
            )

        assertEquals(PortraitComposerV2.pathsFor(stored), rendered)
        assertTrue(rendered.any { it.endsWith("/rank/rank_major.svg") })
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
