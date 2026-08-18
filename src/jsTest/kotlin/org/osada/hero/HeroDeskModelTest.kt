package org.osada.hero

import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every Hero Desk filter predicate and the stable sort, which §9 of
 * `docs/design/hero-desk-and-profile-archive.md` requires to be pure and unit-tested.
 */
class HeroDeskModelTest {
    @BeforeTest
    fun setUpI18n() {
        installEnglishUiBundleForTests()
    }

    @Suppress("LongParameterList")
    private fun record(
        heroId: String,
        name: String = heroId,
        campaignRunId: String = "camp6.json",
        campaignName: String = "Red Army Campaign",
        source: HeroRecordSource = HeroRecordSource.LIVE,
        status: HeroStatus? = HeroStatus.ACTIVE,
        renown: HeroRenown? = HeroRenown.UNKNOWN,
        potential: HeroPotential? = HeroPotential.LINE_OFFICER,
        resumableRun: Boolean = true,
        retiredFromRun: Boolean = false,
        formationName: String? = "3rd Guards Brigade",
        updatedAt: Double = 0.0,
    ) = HeroDeskRecord(
        heroId = heroId,
        campaignRunId = campaignRunId,
        runEpoch = "epoch",
        campaignName = campaignName,
        source = source,
        name = name,
        rank = "Major",
        formationName = formationName,
        status = status,
        statusLabel = status?.name.orEmpty(),
        renown = renown,
        renownLabel = renown?.name.orEmpty(),
        renownClass = "",
        potential = potential,
        potentialLabel = potential?.name.orEmpty(),
        notable =
            renown == HeroRenown.HERO ||
                renown == HeroRenown.LEGEND ||
                potential == HeroPotential.AUTHORED_LEGENDARY ||
                status == HeroStatus.KILLED,
        resumableRun = resumableRun,
        retiredFromRun = retiredFromRun,
        inMemoriam = status == HeroStatus.KILLED,
        updatedAt = updatedAt,
        dossier = null,
    )

    // ------------------------------------------------------------------ merge

    @Test
    fun liveRecordSuppressesTheArchivedCopyOfTheSameOfficer() {
        val live = record("H-1")
        val archived = record("H-1", source = HeroRecordSource.ARCHIVE, resumableRun = false)
        val merged = HeroDeskModel.merge(listOf(live), listOf(archived), emptyList())
        assertEquals(listOf(HeroRecordSource.LIVE), merged.map { it.source })
    }

    @Test
    fun archivedCareerOfAnotherRunSurvivesTheMerge() {
        val live = record("H-1")
        val archived = record("H-9", campaignRunId = "other.json", source = HeroRecordSource.ARCHIVE)
        assertEquals(2, HeroDeskModel.merge(listOf(live), listOf(archived), emptyList()).size)
    }

    /** A legacy summary is replaced once a complete record for the same officer/campaign exists. */
    @Test
    fun legacySummaryIsReplacedByAMatchingCompleteRecord() {
        val complete = record("H-1", name = "Anna Voroshina")
        val legacy =
            HeroDeskModel.legacyRecord(
                LegacyHeroRecord("  anna voroshina ", "Major", "Hero", "Distinguished", "KIA", "Red Army Campaign"),
            )
        assertEquals(1, HeroDeskModel.merge(listOf(complete), emptyList(), listOf(legacy)).size)
    }

    @Test
    fun legacySummaryOfAnUnknownOfficerIsKept() {
        val legacy =
            HeroDeskModel.legacyRecord(
                LegacyHeroRecord("Pavel Belov", "Colonel", "Legend", "Legendary", "KIA", "Bagration"),
            )
        val merged = HeroDeskModel.merge(listOf(record("H-1")), emptyList(), listOf(legacy))
        assertEquals(1, merged.count { it.source == HeroRecordSource.LEGACY })
    }

    // ----------------------------------------------------------------- filters

    @Test
    fun activeMeansANonTerminalOfficerOfAResumableRun() {
        assertTrue(HeroDeskModel.matches(record("a"), HeroDeskFilter.ACTIVE))
        assertFalse(
            HeroDeskModel.matches(record("b", resumableRun = false), HeroDeskFilter.ACTIVE),
            "a completed or cleared run has no active officers",
        )
        assertFalse(HeroDeskModel.matches(record("c", status = HeroStatus.KILLED), HeroDeskFilter.ACTIVE))
        assertFalse(HeroDeskModel.matches(record("d", status = HeroStatus.RETIRED), HeroDeskFilter.ACTIVE))
        assertFalse(HeroDeskModel.matches(record("e", status = HeroStatus.CAPTURED), HeroDeskFilter.ACTIVE))
        assertTrue(
            HeroDeskModel.matches(record("f", status = HeroStatus.WOUNDED), HeroDeskFilter.ACTIVE),
            "a wounded officer can still be posted, so the run has not finished with them",
        )
        assertFalse(
            HeroDeskModel.matches(record("g", retiredFromRun = true), HeroDeskFilter.ACTIVE),
            "a survivor presented as retired from a finished run is not active",
        )
    }

    @Test
    fun legendaryCoversAuthoredPotentialAndEarnedRenown() {
        assertTrue(
            HeroDeskModel.matches(record("a", potential = HeroPotential.AUTHORED_LEGENDARY), HeroDeskFilter.LEGENDARY),
        )
        assertTrue(HeroDeskModel.matches(record("b", renown = HeroRenown.LEGEND), HeroDeskFilter.LEGENDARY))
        assertFalse(HeroDeskModel.matches(record("c", renown = HeroRenown.HERO), HeroDeskFilter.LEGENDARY))
    }

    @Test
    fun fallenIsKilledOnly() {
        assertTrue(HeroDeskModel.matches(record("a", status = HeroStatus.KILLED), HeroDeskFilter.FALLEN))
        assertFalse(HeroDeskModel.matches(record("b", status = HeroStatus.MISSING), HeroDeskFilter.FALLEN))
    }

    /** Hall of Fame is a FILTER over the existing notable predicate, not a lifetime cap: a line
     *  officer enters it through earned renown. */
    @Test
    fun hallOfFameUsesTheNotablePredicateAndAdmitsEarnedRenown() {
        val lineOfficer = record("a", renown = HeroRenown.HERO, potential = HeroPotential.LINE_OFFICER)
        assertTrue(HeroDeskModel.matches(lineOfficer, HeroDeskFilter.HALL_OF_FAME))
        assertFalse(HeroDeskModel.matches(record("b"), HeroDeskFilter.HALL_OF_FAME))
    }

    @Test
    fun everyRecordAnswersAll() {
        assertTrue(HeroDeskModel.matches(record("a", status = null, renown = null), HeroDeskFilter.ALL))
    }

    // ------------------------------------------------------------------ search

    @Test
    fun searchMatchesHeroCampaignAndFormationNames() {
        val subject = record("H-1", name = "Anna Voroshina")
        assertTrue(HeroDeskModel.matchesSearch(subject, "voroshina"))
        assertTrue(HeroDeskModel.matchesSearch(subject, " RED ARMY "))
        assertTrue(HeroDeskModel.matchesSearch(subject, "guards"))
        assertTrue(HeroDeskModel.matchesSearch(subject, ""))
        assertFalse(HeroDeskModel.matchesSearch(subject, "wehrmacht"))
    }

    // -------------------------------------------------------------------- sort

    @Test
    fun sortIsRenownDescendingThenLastServiceThenName() {
        val legend = record("a", name = "Zhukov", renown = HeroRenown.LEGEND)
        val recentHero = record("b", name = "Belov", renown = HeroRenown.HERO, updatedAt = 200.0)
        val olderHero = record("c", name = "Antonov", renown = HeroRenown.HERO, updatedAt = 100.0)
        val unknown = record("d", name = "Ivanov", renown = HeroRenown.UNKNOWN)
        val sorted = HeroDeskModel.sorted(listOf(unknown, olderHero, legend, recentHero))
        assertEquals(listOf("Zhukov", "Belov", "Antonov", "Ivanov"), sorted.map { it.name })
    }

    @Test
    fun sortIsTotalSoTheListNeverReshuffles() {
        val first = record("A-1", name = "Same Name")
        val second = record("A-2", name = "Same Name")
        assertEquals(
            HeroDeskModel.sorted(listOf(first, second)).map { it.heroId },
            HeroDeskModel.sorted(listOf(second, first)).map { it.heroId },
        )
    }

    /** Filtering is a view, never a write: the input list is untouched. */
    @Test
    fun viewFiltersAndSortsWithoutMutatingTheInput() {
        val input = listOf(record("a"), record("b", status = HeroStatus.KILLED))
        val copy = input.toList()
        assertEquals(1, HeroDeskModel.view(input, HeroDeskFilter.FALLEN, "").size)
        assertEquals(copy, input)
    }

    @Test
    fun legacyRecordAnswersOnlyAllAndHallOfFame() {
        val legacy =
            HeroDeskModel.legacyRecord(
                LegacyHeroRecord("Pavel Belov", "Colonel", "Legend", "Legendary", "KIA", "Bagration"),
            )
        assertTrue(HeroDeskModel.matches(legacy, HeroDeskFilter.ALL))
        assertTrue(HeroDeskModel.matches(legacy, HeroDeskFilter.HALL_OF_FAME))
        assertFalse(HeroDeskModel.matches(legacy, HeroDeskFilter.ACTIVE))
        assertFalse(HeroDeskModel.matches(legacy, HeroDeskFilter.FALLEN))
        assertFalse(HeroDeskModel.matches(legacy, HeroDeskFilter.LEGENDARY))
    }
}
