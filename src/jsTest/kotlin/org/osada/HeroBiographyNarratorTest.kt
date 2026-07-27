package org.osada

import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroBiographyNarrator
import org.osada.hero.HeroBiographyPools
import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The biography narrator (DEFERRED.md §6.6 item 6a, `docs/design/hero-presentation.md` §2): a null
 * fact renders as an omitted clause, never as "unknown"; rendering is deterministic; and an
 * authored (legendary) biography's sparse facts are rendered honestly, never invented around.
 */
class HeroBiographyNarratorTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
    }

    @Test
    fun biographyOmitsMissingFactsRatherThanSayingUnknown() {
        // The migrated-hero case (LeaderMigration.kt): only emergenceEventId is ever set.
        val migrated = HeroBiographyFacts(emergenceEventId = "migrated_from_legacy_leader")

        val lines = HeroBiographyNarrator.narrate(migrated, "major")

        assertEquals(listOf("Commissioned as Major."), lines, "no birth year means no origin sentence at all")
        assertFalse(lines.any { it.contains("null", ignoreCase = true) })
        assertFalse(lines.any { it.contains("unknown", ignoreCase = true) })
    }

    @Test
    fun authoredLegendaryBiographyIsNotOverwritten() {
        // LegendaryHeroPool.kt sets only birthYear + prewarProfessionId -- the other three fields
        // stay null by construction, exactly like any other HeroBiographyFacts default.
        val legendary =
            HeroBiographyFacts(
                birthYear = 1905,
                prewarProfessionId = "armored_academy_graduate",
                emergenceEventId = "distinguished_service",
            )

        val lines = HeroBiographyNarrator.narrate(legendary, "colonel")

        assertEquals("Born 1905.", lines[0], "no birthplace/social fact means the plain year-only sentence")
        assertEquals("Commissioned as Colonel.", lines[1])
    }

    @Test
    fun biographyIsDeterministicAcrossRenders() {
        val facts =
            HeroBiographyFacts(
                birthYear = 1911,
                birthplaceId = "provincial_town",
                socialBackgroundId = "worker",
                militaryEducationId = "military_academy",
                priorServiceId = "border_skirmishes",
                emergenceEventId = "destroyed_stronger_enemy",
            )

        val first = HeroBiographyNarrator.narrate(facts, "colonel")
        val second = HeroBiographyNarrator.narrate(facts, "colonel")

        assertEquals(first, second)
        assertEquals("Born 1911 near a provincial town, to a working-class family.", first[0])
        assertEquals(
            "A graduate of the military academy, he served in the border skirmishes before rising to Colonel.",
            first[1],
        )
    }

    @Test
    fun biographyPoolsAreSeededDeterministically() {
        val seed = 4242
        val first =
            HeroBiographyFacts(
                birthplaceId = HeroBiographyPools.birthplaceId(seed),
                socialBackgroundId = HeroBiographyPools.socialBackgroundId(seed),
                militaryEducationId = HeroBiographyPools.militaryEducationId(seed, "major"),
                priorServiceId = HeroBiographyPools.priorServiceId(seed, "major"),
                emergenceEventId = "x",
            )
        val second =
            HeroBiographyFacts(
                birthplaceId = HeroBiographyPools.birthplaceId(seed),
                socialBackgroundId = HeroBiographyPools.socialBackgroundId(seed),
                militaryEducationId = HeroBiographyPools.militaryEducationId(seed, "major"),
                priorServiceId = HeroBiographyPools.priorServiceId(seed, "major"),
                emergenceEventId = "x",
            )

        assertEquals(first, second, "the same seed and rank must reproduce the same facts across reloads")
    }

    @Test
    fun militaryEducationIsWeightedByRank() {
        val seniorPool = (0 until 50).map { HeroBiographyPools.militaryEducationId(it, "colonel") }.toSet()
        val juniorPool = (0 until 50).map { HeroBiographyPools.militaryEducationId(it, "lieutenant") }.toSet()

        assertTrue(seniorPool.all { it == "military_academy" || it == "staff_college" })
        assertTrue(juniorPool.all { it == "commissioned_from_the_ranks" || it == "reserve_officer_course" })
    }
}
