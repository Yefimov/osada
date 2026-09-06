package org.osada

import org.osada.hero.BiographyPacks
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroBiographyNarrator
import org.osada.hero.HeroChronology
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroLifePath
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.PortraitComposition
import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.2's property tests and §17.3's coverage gate for the biography life path.
 *
 * These are seeded SAMPLES, not examples: every finished campaign's player side is walked over
 * hundreds of seeds and every produced life path is asserted against [HeroChronology]. That is the
 * only way to catch the failure this system is actually prone to — not a wrong sentence, but a
 * combination of facts that is individually plausible and jointly impossible, which appears once
 * every few hundred draws and never in a hand-written example.
 */
class HeroBiographyLifePathTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    /**
     * Every visible finished campaign's human side, as `HeroLegendaryTest` records it: the
     * campaign file, the country its scenarios author for player 0, and its opening year.
     *
     * §9 forbids hard-coding this table as the ONLY coverage test, and it is not: the same list
     * drives `HeroLegendaryTest`'s legendary-candidate gate, and both fail the moment a campaign's
     * authored country and its content disagree.
     */
    private val campaigns =
        listOf(
            Triple("062d", 61, 1942),
            Triple("camp6", 61, 1942),
            Triple("rcampdfr", 61, 1941),
            Triple("camp6bn9", 19, 1936),
            Triple("camp6bn5", 19, 1941),
            Triple("reddestiny", 19, 1946),
            Triple("forward", 89, 1939),
            Triple("ga4", 89, 1939),
            Triple("ccampdfc", 103, 1918),
            Triple("volarm", 103, 1918),
            Triple("polsov", 103, 1919),
            Triple("simpob", 103, 1918),
            Triple("acampdf2", 103, 1917),
            Triple("camp6bn8", 43, 1941),
            Triple("ncampdfn", 25, 1950),
            Triple("aljf", 196, 1848),
            Triple("rhu", 187, 1919),
            Triple("novemberrevolution", 188, 1918),
            Triple("gce", 226, 1936),
            Triple("spa", 310, -72),
            Triple("nvc", 276, 1964),
            Triple("rsoc", 21, 1927),
            Triple("camp6bn4", 39, 1940),
        )

    private val ranks = listOf("lieutenant", "captain", "major", "colonel")

    private fun context(
        seed: Int,
        country: Int,
        year: Int,
        unitClass: Int = UnitClass.INFANTRY.value,
        rankId: String = "captain",
    ) = HeroLifePath.Context(
        seed = seed,
        country = country,
        serviceYear = year,
        unitClass = unitClass,
        rankId = rankId,
        emergenceEventId = "distinguished_service",
    )

    @Test
    fun everyFinishedCampaignResolvesToAnAuthoredBiographyPack() {
        // §9: "No finished campaign shown in Campaign Selection may silently fall back to the
        // seven generic birthplaces and four generic service histories."
        campaigns.forEach { (campaign, country, year) ->
            assertTrue(
                BiographyPacks.isAuthored(country, year),
                "$campaign (country $country, $year) falls back to the generic pack",
            )
        }
    }

    @Test
    fun noSampledLifePathBreaksItsOwnChronology() {
        campaigns.forEach { (campaign, country, year) ->
            ranks.forEach { rank ->
                (0 until SAMPLES).forEach { seed ->
                    val facts = HeroLifePath.generate(context(seed, country, year, rankId = rank))
                    val violations = HeroChronology.validate(facts, year)
                    assertTrue(
                        violations.isEmpty(),
                        "$campaign/$rank/seed $seed: ${violations.joinToString("; ")}",
                    )
                }
            }
        }
    }

    @Test
    fun everySampledLifePathNarratesWithoutAnEmptySlotOrAMissingKey() {
        campaigns.forEach { (campaign, country, year) ->
            (0 until NARRATION_SAMPLES).forEach { seed ->
                val facts = HeroLifePath.generate(context(seed, country, year))
                val lines = HeroBiographyNarrator.narrate(facts, "captain", seed)
                assertTrue(lines.isNotEmpty(), "$campaign/seed $seed narrated nothing")
                assertTrue(lines.size <= MAX_SENTENCES, "$campaign/seed $seed: ${lines.size} sentences")
                lines.forEach { line ->
                    // An unfilled slot survives as a literal `{name}`; a missing key survives as the
                    // key itself. Both are what a bundle gap actually looks like on screen.
                    assertTrue(!line.contains("{"), "$campaign/seed $seed unfilled slot: $line")
                    assertTrue(!line.contains("hero.bio."), "$campaign/seed $seed missing key: $line")
                    assertTrue(!line.contains("null"), "$campaign/seed $seed rendered null: $line")
                }
            }
        }
    }

    @Test
    fun theAncientPackNeverBorrowsTwentiethCenturyLanguage() {
        // §9.1: no schooling, no party membership, no dated enlistment, no conscription.
        (0 until SAMPLES).forEach { seed ->
            val facts = HeroLifePath.generate(context(seed, ANCIENT_COUNTRY, ANCIENT_YEAR))
            assertEquals("ancient_rebel", facts.biographyPackId, "seed $seed")
            assertEquals(null, facts.civilianEducationId, "seed $seed has modern schooling")
            assertEquals(null, facts.politicalStatusId, "seed $seed has a party status")
            assertEquals(null, facts.serviceStartYear, "seed $seed has an enlistment year")
        }
    }

    @Test
    fun aProfessionNeverAppearsWithoutTheSchoolingItRequires() {
        // The defect the tag system exists to prevent: a mine surveyor who never went to school.
        campaigns.forEach { (campaign, country, year) ->
            (0 until SAMPLES).forEach { seed ->
                val facts = HeroLifePath.generate(context(seed, country, year))
                val pack = BiographyPacks.forCountry(country, year)
                val profession = facts.prewarProfessionId ?: return@forEach
                val option = pack.professions.first { it.id == profession }
                if (option.requiresFacts.isEmpty()) return@forEach
                val education =
                    facts.civilianEducationId?.let { id -> pack.civilianEducation.first { it.id == id } }
                val social =
                    facts.socialBackgroundId?.let { id -> pack.socialBackgrounds.first { it.id == id } }
                val provided = education?.provides.orEmpty() + social?.provides.orEmpty()
                assertTrue(
                    provided.containsAll(option.requiresFacts),
                    "$campaign/seed $seed: '$profession' needs ${option.requiresFacts}, has $provided",
                )
            }
        }
    }

    @Test
    fun theSameSeedAndContextAlwaysProduceTheSamePath() {
        campaigns.forEach { (campaign, country, year) ->
            (0 until NARRATION_SAMPLES).forEach { seed ->
                val context = context(seed, country, year)
                assertEquals(
                    HeroLifePath.generate(context),
                    HeroLifePath.generate(context),
                    "$campaign/seed $seed is not deterministic",
                )
            }
        }
    }

    @Test
    fun sovietBiographiesSpreadAcrossTheirRegionsWithoutTouchingAnythingMechanical() {
        // §8.1: regional breadth is required, and it must be pure content. The second half is
        // structural -- no region id reaches any field but the birthplace -- so what is asserted
        // here is the first half plus the absence of a nationality-shaped field.
        val regions =
            (0 until SAMPLES)
                .map { HeroLifePath.generate(context(it, SOVIET_COUNTRY, 1942)).birthplaceId }
                .toSet()
        assertTrue(regions.size >= MIN_DISTINCT_REGIONS, "only ${regions.size} distinct birth regions")
    }

    @Test
    fun everyBiographyFactSurvivesASaveRoundTrip() {
        val facts = HeroLifePath.generate(context(11, SOVIET_COUNTRY, 1942, rankId = "major"))
        val roster = HeroRoster()
        val definition =
            HeroDefinition(
                id = HeroId("H-BIO"),
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Officer",
                backgroundId = "infantry_school_instructor",
                biographyFacts = facts,
                portrait = PortraitComposition(seed = 11),
            )
        roster.putHero(definition, HeroState(heroId = definition.id, rankId = "major"))

        val restored =
            HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))

        assertEquals(facts, restored.definition(definition.id)!!.biographyFacts)
    }

    @Test
    fun aHeroWrittenBeforeTheLifePathIsPreservedRatherThanEnriched() {
        // §15's chosen policy: "preserve ... Never reroll a character the player already knows."
        // A save from before this system has no pack id, and must come back with none -- which is
        // also what keeps [HeroBiographyNarrator] on the legacy two-sentence path for them.
        val legacy =
            HeroBiographyFacts(
                birthYear = 1911,
                birthplaceId = "provincial_town",
                socialBackgroundId = "worker",
                militaryEducationId = "military_academy",
                priorServiceIds = listOf("border_skirmishes"),
                emergenceEventId = "destroyed_stronger_enemy",
            )
        val roster = HeroRoster()
        val definition =
            HeroDefinition(
                id = HeroId("H-OLD"),
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Officer",
                backgroundId = "infantry_school_instructor",
                biographyFacts = legacy,
                portrait = PortraitComposition(seed = 3),
            )
        roster.putHero(definition, HeroState(heroId = definition.id, rankId = "colonel"))

        val restored =
            HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))
        val reloaded = restored.definition(definition.id)!!.biographyFacts

        assertEquals(legacy, reloaded)
        assertEquals(null, reloaded.biographyPackId, "an old hero must not acquire a content pack")
        assertEquals(
            2,
            HeroBiographyNarrator.narrate(reloaded, "colonel", 3).size,
            "and must keep rendering as the two sentences they always did",
        )
    }

    private companion object {
        const val SAMPLES = 200
        const val NARRATION_SAMPLES = 60
        const val MAX_SENTENCES = 4
        const val MIN_DISTINCT_REGIONS = 8
        const val SOVIET_COUNTRY = 61
        const val ANCIENT_COUNTRY = 310
        const val ANCIENT_YEAR = -72
    }
}
