package org.osada

import org.osada.hero.HeroBackgrounds
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroBiographyNarrator
import org.osada.hero.HeroChronology
import org.osada.hero.HeroLifePath
import org.osada.hero.PortraitComposerV2
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

    // Found by scan rather than hardcoded, so the tests stay correct if PortraitComposerV2's
    // gender roll ever changes shape (§4.11) -- what matters is "a seed that rolls male/female",
    // not any particular integer.
    private val maleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "male" }
    private val femaleSeed = (0..2000).first { PortraitComposerV2.genderFor(it) == "female" }

    @Test
    fun biographyOmitsMissingFactsRatherThanSayingUnknown() {
        // The migrated-hero case (LeaderMigration.kt): only emergenceEventId is ever set.
        val migrated = HeroBiographyFacts(emergenceEventId = "migrated_from_legacy_leader")

        val lines = HeroBiographyNarrator.narrate(migrated, "major", maleSeed)

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

        val lines = HeroBiographyNarrator.narrate(legendary, "colonel", maleSeed)

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
                priorServiceIds = listOf("border_skirmishes"),
                emergenceEventId = "destroyed_stronger_enemy",
            )

        val first = HeroBiographyNarrator.narrate(facts, "colonel", maleSeed)
        val second = HeroBiographyNarrator.narrate(facts, "colonel", maleSeed)

        assertEquals(first, second)
        // "to a worker's family", not "to a working-class family": the social-origin clauses were
        // rewritten to the Soviet encyclopedia's own formula -- SIE prints `в семье рабочего`,
        // `в семье дехканина`, `в семье мелкого торговца`. Unlike `hero.bio.education.*`, which is
        // worded to OPEN the legacy commission sentence and therefore could not be touched, this
        // clause sits in the same slot in both narration paths, so one wording serves both.
        assertEquals("Born 1911 near a provincial town, to a worker's family.", first[0])
        assertEquals(
            "A graduate of the military academy, he served in the border skirmishes before rising to Colonel.",
            first[1],
        )
    }

    @Test
    fun biographyAgreesWithThePortraitsRolledGender() {
        // §4.11: a hero the portrait draws as a woman must be narrated as one too -- same seed,
        // same gender, in both places.
        assertEquals("female", PortraitComposerV2.genderFor(femaleSeed))
        val facts =
            HeroBiographyFacts(
                birthYear = 1911,
                militaryEducationId = "military_academy",
                priorServiceIds = listOf("border_skirmishes"),
                emergenceEventId = "destroyed_stronger_enemy",
            )

        val lines = HeroBiographyNarrator.narrate(facts, "colonel", femaleSeed)

        assertEquals(
            "A graduate of the military academy, she served in the border skirmishes before rising to Colonel.",
            lines[1],
        )
        assertFalse(lines.any { it.contains(" he ", ignoreCase = false) })
    }

    @Test
    fun aLifePathHeroIsNarratedAsAPersonalRecordRatherThanTheLegacyTwoLines() {
        // The design's own worked example (13.1): origin, schooling, entry into service, and the
        // optional closing line. A hero with a pack id takes this path; one without keeps the two
        // sentences above, which is what the preceding tests are pinning.
        val facts =
            HeroLifePath.generate(
                HeroLifePath.Context(
                    seed = 4242,
                    country = SOVIET_COUNTRY,
                    serviceYear = 1942,
                    unitClass = UnitClass.INFANTRY.value,
                    rankId = "major",
                    emergenceEventId = "destroyed_stronger_enemy",
                ),
            )

        val lines = HeroBiographyNarrator.narrate(facts, "major", maleSeed)

        assertTrue(lines.size >= 3, "a life path renders more than the legacy two lines: $lines")
        assertTrue(lines.size <= 4, "13.3 caps the Overview at four sentences: $lines")
        assertTrue(lines[0].startsWith("Born "), lines[0])
        assertFalse(lines.any { it.contains("null", ignoreCase = true) }, lines.toString())
        assertFalse(lines.any { it.contains("unknown", ignoreCase = true) }, lines.toString())
        assertFalse(lines.any { it.contains("{") }, "every slot must be filled: $lines")
    }

    @Test
    fun theLifePathIsDeterministicAndItsFactsAreInternallyCompatible() {
        val context =
            HeroLifePath.Context(
                seed = 99,
                country = SOVIET_COUNTRY,
                serviceYear = 1943,
                unitClass = UnitClass.TANK.value,
                rankId = "captain",
                emergenceEventId = "x",
            )

        val first = HeroLifePath.generate(context)
        val second = HeroLifePath.generate(context)

        assertEquals(first, second, "the same seed and context must reproduce the same life path")
        assertEquals(emptyList(), HeroChronology.validate(first, 1943), "the path must pass its own checker")
        assertEquals(
            "soviet_1930_1945",
            first.biographyPackId,
            "a 1943 Soviet officer draws from the Soviet pack",
        )
    }

    @Test
    fun theCivilianOccupationIsNoLongerTheMilitaryBackground() {
        // 3.2's first listed gap: `prewarProfessionId` used to be `backgroundId` copied across, so
        // every officer's "pre-war occupation" was their officer training.
        val facts =
            HeroLifePath.generate(
                HeroLifePath.Context(
                    seed = 7,
                    country = SOVIET_COUNTRY,
                    serviceYear = 1942,
                    unitClass = UnitClass.TANK.value,
                    rankId = "captain",
                    emergenceEventId = "x",
                ),
            )

        assertTrue(
            HeroBackgrounds.byId(facts.prewarProfessionId.orEmpty()) == null,
            "the occupation is civilian, not a military background: ${facts.prewarProfessionId}",
        )
        assertTrue(facts.civilianEducationId != null, "and civilian schooling is its own fact")
    }

    private companion object {
        /** USSR (basekorp/adlerkorps) — the id the shipped Soviet campaigns author for player 0. */
        const val SOVIET_COUNTRY = 61
    }
}
