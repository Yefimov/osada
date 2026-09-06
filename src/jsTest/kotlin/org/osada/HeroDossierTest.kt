package org.osada

import org.osada.hero.CommandAttributes
import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroDisplay
import org.osada.hero.HeroDossierAssembler
import org.osada.hero.HeroId
import org.osada.hero.HeroMedal
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroPotential
import org.osada.hero.HeroRenown
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.LegacyTraitMapping
import org.osada.hero.PortraitComposition
import org.osada.i18n.installEnglishUiBundleForTests
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the Phase 4 read side (design brief §14.2–14.5, §26): the pure assembly of hero/formation
 * records into localization-ready view-models, with every trait carrying an effect and an
 * activation condition and no hidden bonuses.
 */
class HeroDossierTest {
    private val heroId = HeroId("H-1")
    private val formationId = FormationId("F-1")

    @BeforeTest
    fun setUpI18n() {
        installEnglishUiBundleForTests()
    }

    private fun definition() =
        HeroDefinition(
            id = heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = "Ivan Petrov",
            backgroundId = "armored_academy_graduate",
            biographyFacts = HeroBiographyFacts(birthYear = 1910, emergenceEventId = "destroyed_stronger"),
            portrait = PortraitComposition(seed = 1),
        )

    private fun state() =
        HeroState(
            heroId = heroId,
            rankId = "major",
            status = HeroStatus.ACTIVE,
            potential = HeroPotential.DISTINGUISHED,
            renown = HeroRenown.HERO,
            attributes = CommandAttributes(offense = 1, defense = 0, maneuver = 2, coordination = 0),
            experience = 120,
            assignedFormationId = formationId,
            learnedTraitIds = setOf(LegacyTraitMapping.toTraitId(LeaderType.FIRST_STRIKE)),
            specializationEvidence = mapOf("RIVER_OPERATIONS" to 40, "URBAN_COMBAT" to 10),
            medals = listOf(HeroMedal("valor_medal", "3")),
            promotionsAwarded = 1,
        )

    private fun formation() =
        CoreFormation(
            id = formationId,
            ownerId = 0,
            country = 1,
            displayName = "24th Tank Brigade",
            currentEquipmentId = 900,
            unitClass = UnitClass.TANK.value,
            assignedHeroId = heroId,
            recognition = 50,
            battleHonors = listOf("Smolensk"),
        )

    @Test
    fun dossierResolvesLocalizedLabels() {
        val view = HeroDossierAssembler.dossier(definition(), state(), formation(), unitExperience = 250)
        assertEquals("Ivan Petrov", view.name)
        assertEquals("Major", view.rank)
        assertEquals("Distinguished Hero", view.potential)
        assertEquals("Hero", view.renown)
        assertEquals("Active", view.status)
    }

    @Test
    fun traitsCoverBackgroundAndEarnedWithActivation() {
        val view = HeroDossierAssembler.dossier(definition(), state(), formation(), null)
        // Background grants Aggressive Tank Maneuver; the earned trait is First Strike.
        assertEquals(2, view.traits.size)
        assertEquals("Background", view.traits[0].source)
        assertTrue(view.traits.any { it.source == "Earned" })
        assertTrue(
            view.traits.all { it.title.isNotBlank() && it.activation.isNotBlank() },
            "every trait must state activation (§26)",
        )
    }

    @Test
    fun evidenceIsTitledAndSortedDescending() {
        val view = HeroDossierAssembler.dossier(definition(), state(), formation(), null)
        assertEquals(listOf("River Operations" to 40, "Urban Combat" to 10), view.evidence)
    }

    @Test
    fun medalsAndFormationSurface() {
        val view = HeroDossierAssembler.dossier(definition(), state(), formation(), unitExperience = 250)
        assertEquals(listOf("Medal of Valor" to "3"), view.medals)
        val formationView = assertNotNull(view.formation)
        assertEquals("24th Tank Brigade", formationView.name)
        assertEquals(250, formationView.unitExperience)
        assertEquals(listOf("Smolensk"), formationView.battleHonors)
        assertTrue(formationView.recognitionStatus.isNotBlank())
    }

    @Test
    fun commanderRowAndRosterTabsGroupByStatus() {
        val wounded = state().copy(status = HeroStatus.WOUNDED)
        val row = HeroDossierAssembler.commanderRow(definition(), wounded, "24th Tank Brigade")
        assertEquals("Ivan Petrov", row.name)
        assertEquals("Major", row.rank)
        assertEquals(HeroStatus.WOUNDED, row.status)
        // Ids, not labels. A label here would be a bug: the presenter groups rows by this value,
        // and the Russian roster printed `hero.roster.tab.в строю` while it returned translated text.
        assertEquals("wounded", HeroDisplay.rosterTab(HeroStatus.WOUNDED))
        assertEquals("fallen", HeroDisplay.rosterTab(HeroStatus.KILLED))
        assertEquals("reserve", HeroDisplay.rosterTab(HeroStatus.RETIRED))
        assertEquals("missing", HeroDisplay.rosterTab(HeroStatus.CAPTURED))
        assertEquals(HeroDisplay.ROSTER_TABS.toSet(), HeroStatus.entries.map { HeroDisplay.rosterTab(it) }.toSet())
        HeroDisplay.ROSTER_TABS.forEach { tab ->
            assertTrue(HeroDisplay.rosterTabLabel(tab).isNotEmpty(), tab)
            assertFalse(HeroDisplay.rosterTabLabel(tab).startsWith("hero.roster.tab."), tab)
        }
    }

    /** DEFERRED.md §6.6 item 6a / `docs/design/hero-presentation.md` §1: every renown tier maps to
     *  exactly one CSS class, UNKNOWN gets none, and no two tiers can ever collide. */
    @Test
    fun renownTierMapsToExactlyOneCssClass() {
        assertEquals("", HeroDisplay.renownClass(HeroRenown.UNKNOWN), "UNKNOWN must render no frame at all")
        val classes = HeroRenown.entries.map { HeroDisplay.renownClass(it) }
        assertEquals(classes.size, classes.toSet().size, "no two renown tiers may share a CSS class")
        HeroRenown.entries.filter { it != HeroRenown.UNKNOWN }.forEach { tier ->
            assertTrue(HeroDisplay.renownClass(tier).isNotBlank(), "$tier must have a non-empty frame class")
        }
    }

    @Test
    fun dossierAndRosterRowCarryTheMatchingRenownClass() {
        val distinguished = state().copy(renown = HeroRenown.DISTINGUISHED)
        val dossierView = HeroDossierAssembler.dossier(definition(), distinguished, formation(), null)
        val row = HeroDossierAssembler.commanderRow(definition(), distinguished, "24th Tank Brigade")

        assertEquals(HeroDisplay.renownClass(HeroRenown.DISTINGUISHED), dossierView.renownClass)
        assertEquals(dossierView.renownClass, row.renownClass, "the dossier and the roster row must agree")
    }
}
