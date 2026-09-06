package org.osada

import org.osada.hero.EmergenceEvent
import org.osada.hero.FormationId
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroBiographyNarrator
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroPortraitArt
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.LEGENDS_AWAITING_PORTRAIT_ART
import org.osada.hero.LegendaryHeroPool
import org.osada.hero.PortraitComposerV2
import org.osada.hero.PortraitComposition
import org.osada.hero.ProceduralHeroGenerator
import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the painted (authored) portrait path added for `DEFERRED.md` §6.6 item 6a — the half of
 * `docs/design/hero-presentation.md` §3 that was blocked on art.
 *
 * The invariants that matter are not "the picture is pretty" but: every authored hero that requests
 * painted art names an existing, correctly dated asset; every shipped authored hero has one; an
 * unknown art id degrades to the procedural layer stack rather than to an empty frame (§3.4); and
 * prose gender follows the painting, not the seed roll (§4.11).
 */
class HeroPortraitArtTest {
    @Test
    fun everyPaintedAuthoredHeroNamesArtThatExistsInTheCatalogue() {
        LegendaryHeroPool.ALL.filter { it.portraitArtId != null }.forEach { hero ->
            val artId = assertNotNull(hero.portraitArtId)
            assertNotNull(
                HeroPortraitArt.byId(artId),
                "${hero.id} names unknown portrait art '$artId'",
            )
        }
    }

    @Test
    fun eachPortraitIsUsedByExactlyOneAuthoredHero() {
        // Two officers wearing the same face in one campaign reads as a bug, not as a pool.
        val used = LegendaryHeroPool.ALL.mapNotNull { it.portraitArtId }
        assertEquals(used.size, used.toSet().size, "a painted portrait is shared by two heroes: $used")
    }

    @Test
    fun theHeroesAwaitingPaintedArtAreExactlyTheOnesDeclaredAsAwaitingIt() {
        // Asserted in BOTH directions on purpose. "Has no painting yet" is a legitimate shipped
        // state since the 2026-09-04 expansion, but it has to be a DECLARED one: a new hero cannot
        // quietly join the art-less set, and a hero who gets a painting cannot be left behind in it.
        val missing =
            LegendaryHeroPool.ALL
                .filter { it.portraitArtId == null }
                .map { it.id }
                .toSet()
        assertEquals(
            LEGENDS_AWAITING_PORTRAIT_ART,
            missing,
            "LEGENDS_AWAITING_PORTRAIT_ART disagrees with the roster's art-less heroes",
        )
    }

    @Test
    fun anAuthoredWomanKeepsAFemaleProceduralFallbackBehindHerPainting() {
        // Every legendary now has a painting, but the stored layer stack is still the soft fallback
        // for a missing asset. It must agree with the authored gender instead of the seed's usual roll.
        val heroine = assertNotNull(LegendaryHeroPool.ALL.firstOrNull { it.female })
        val request =
            ProceduralHeroGenerator.Request(
                heroId = HeroId("H-GENDER"),
                seed = 1,
                country = 103,
                unitClass = UnitClass.INFANTRY.value,
                unitExperience = 120,
                event = EmergenceEvent.DISTINGUISHED_SERVICE,
                formationId = FormationId("F-GENDER"),
                serviceYear = heroine.yearRange.first,
            )
        val (definition, _) = LegendaryHeroPool.build(heroine, request)
        assertEquals(true, definition.portrait.female)
        assertEquals(
            "female",
            PortraitComposerV2.genderFor(definition.portrait.seed),
            "${heroine.id}'s procedural fallback must agree with her authored painting",
        )
    }

    @Test
    fun revolutionaryCampaignLegendsHaveTheirOwnPaintedPortraits() {
        val expected =
            mapOf(
                "red_german_council_guard" to "revolution_1918_otto_reimers",
                "red_hungarian_mobile" to "revolution_1919_laszlo_farkas",
            )
        expected.forEach { (heroId, artId) ->
            val hero = assertNotNull(LegendaryHeroPool.ALL.firstOrNull { it.id == heroId })
            assertEquals(artId, hero.portraitArtId)
            assertNotNull(HeroPortraitArt.byId(artId))
        }
    }

    @Test
    fun authoredGenderMatchesTheArtItPointsAt() {
        LegendaryHeroPool.ALL.filter { it.portraitArtId != null }.forEach { hero ->
            val art = assertNotNull(HeroPortraitArt.byId(assertNotNull(hero.portraitArtId)))
            assertEquals(art.female, hero.female, "${hero.id}'s stated gender disagrees with its portrait")
        }
    }

    @Test
    fun aHeroNeverReachesACampaignItsUniformCannotBeWornIn() {
        // A budenovka cannot appear in 1942 and pogony cannot appear in 1919, whatever both sides
        // call themselves. The era's window is the honest bound; the hero's own range must sit in it.
        LegendaryHeroPool.ALL.filter { it.portraitArtId != null }.forEach { hero ->
            val art = assertNotNull(HeroPortraitArt.byId(assertNotNull(hero.portraitArtId)))
            assertTrue(
                hero.yearRange.first >= art.era.years.first && hero.yearRange.last <= art.era.years.last,
                "${hero.id} spans ${hero.yearRange} but wears ${art.era} art (${art.era.years})",
            )
        }
    }

    @Test
    fun anUnknownArtIdFallsBackToTheProceduralFaceRatherThanAnEmptyFrame() {
        assertNull(HeroPortraitArt.pathFor("no_such_asset"))
        assertNull(HeroPortraitArt.pathFor(null))
        val known = assertNotNull(LegendaryHeroPool.ALL.firstNotNullOfOrNull { it.portraitArtId })
        assertEquals("resources/heroes/$known.png", HeroPortraitArt.pathFor(known))
    }

    @Test
    fun anAuthoredPortraitSurvivesASaveRoundTrip() {
        val hero = assertNotNull(LegendaryHeroPool.ALL.firstOrNull { it.female })
        val definition =
            HeroDefinition(
                id = HeroId("H-ART"),
                origin = HeroOrigin.AUTHORED_FICTIONAL,
                displayName = hero.name,
                backgroundId = hero.backgroundId,
                biographyFacts = HeroBiographyFacts(birthYear = 1899, emergenceEventId = "e"),
                portrait =
                    PortraitComposerV2
                        .composeFor(7, UnitClass.INFANTRY.value, hero.startingRankId, 1899, 1919)
                        .copy(artId = hero.portraitArtId, female = hero.female),
                signatureTraitId = null,
            )
        val roster = HeroRoster()
        roster.putHero(definition, HeroState(heroId = definition.id, rankId = hero.startingRankId))

        // Through a real JSON string, so a mangled or dropped key would show up.
        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))
        val reloaded = assertNotNull(restored.definition(definition.id))
        assertEquals(hero.portraitArtId, reloaded.portrait.artId)
        assertEquals(hero.female, reloaded.portrait.female)
        assertEquals(definition.portrait.layerIds, reloaded.portrait.layerIds)
    }

    @Test
    fun aProceduralPortraitStillRollsItsGenderFromTheSeedAfterReload() {
        // The override is opt-in: adding it must not freeze procedural heroes' gender into saves.
        val definition =
            HeroDefinition(
                id = HeroId("H-PROC"),
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Someone",
                backgroundId = "infantry_school_instructor",
                biographyFacts = HeroBiographyFacts(birthYear = 1911, emergenceEventId = "e"),
                portrait = PortraitComposition(seed = 42),
                signatureTraitId = null,
            )
        val roster = HeroRoster()
        roster.putHero(definition, HeroState(heroId = definition.id, rankId = "captain"))

        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))
        val reloaded = assertNotNull(restored.definition(definition.id))
        assertNull(reloaded.portrait.female)
        assertNull(reloaded.portrait.artId)
    }

    @Test
    fun theBiographyFollowsTheAuthoredGenderNotTheSeedRoll() {
        installEnglishUiBundleForTests()
        // Find a seed whose roll disagrees with a female authored hero, and prove the prose obeys
        // the painting -- the §4.11 defect, arriving by a different route.
        val maleSeed = (1..500).first { PortraitComposerV2.genderFor(it) == "male" }
        // Education + prior service, because those are the clauses English actually inflects --
        // the origin sentence reads identically for both genders and would prove nothing.
        val facts =
            HeroBiographyFacts(
                birthYear = 1899,
                militaryEducationId = "military_academy",
                priorServiceIds = listOf("border_skirmishes"),
                emergenceEventId = "destroyed_stronger_enemy",
            )

        val rolled = HeroBiographyNarrator.narrate(facts, "captain", maleSeed)
        val authored = HeroBiographyNarrator.narrate(facts, "captain", maleSeed, femaleOverride = true)

        assertTrue(rolled[1].contains(" he "), "the seed rolls male, so the unoverridden prose is male")
        assertTrue(authored[1].contains(" she "), "the painting is a woman, so the prose must be too")
    }
}
