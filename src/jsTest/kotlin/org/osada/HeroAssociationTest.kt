package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroAssociation
import org.osada.hero.HeroAssociations
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroEvent
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroRoster
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.PortraitComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §6 and §17.4: associations are sparse, reciprocal, capped at two, and never invented. The death
 * callback is memory only — it must not touch a single number on a surviving officer.
 */
class HeroAssociationTest {
    private fun roster(vararg heroes: String): HeroRoster {
        val roster = HeroRoster()
        heroes.forEach { id ->
            roster.putHero(
                HeroDefinition(
                    id = HeroId(id),
                    origin = HeroOrigin.PROCEDURAL,
                    displayName = "Officer $id",
                    backgroundId = "infantry_school_instructor",
                    biographyFacts = HeroBiographyFacts(emergenceEventId = "e"),
                    portrait = PortraitComposition(seed = 1),
                ),
                HeroState(heroId = HeroId(id), rankId = "captain"),
            )
        }
        return roster
    }

    private fun link(
        roster: HeroRoster,
        a: String,
        b: String,
        type: HeroAssociation.Type = HeroAssociation.Type.ENDORSED_BY,
    ) = HeroAssociations.link(roster, HeroId(a), HeroId(b), type, "distinguished_service", "s1")

    @Test
    fun anAssociationIsWrittenOnBothOfficersWithReciprocalDirections() {
        val roster = roster("A", "B")

        assertTrue(link(roster, "A", "B"))

        val a = roster.state(HeroId("A"))!!.associations.single()
        val b = roster.state(HeroId("B"))!!.associations.single()
        assertEquals(HeroAssociation.Type.ENDORSED_BY, a.type)
        assertEquals(HeroId("B"), a.otherHeroId)
        assertEquals(HeroAssociation.Type.ENDORSED, b.type)
        assertEquals(HeroId("A"), b.otherHeroId)
    }

    @Test
    fun aPairIsNeverLinkedTwiceAndNobodyExceedsTwoAssociations() {
        val roster = roster("A", "B", "C", "D")

        assertTrue(link(roster, "A", "B"))
        assertFalse(link(roster, "A", "B"), "the same pair must not be linked again")
        assertTrue(link(roster, "A", "C", HeroAssociation.Type.PREDECESSOR))
        assertFalse(link(roster, "A", "D"), "§6 caps a hero at ${HeroAssociations.MAX_ASSOCIATIONS}")

        assertEquals(HeroAssociations.MAX_ASSOCIATIONS, roster.state(HeroId("A"))!!.associations.size)
        // The refusal is atomic: D must not have been given a one-sided record.
        assertEquals(0, roster.state(HeroId("D"))!!.associations.size)
    }

    @Test
    fun anOfficerIsNeverLinkedToThemselves() {
        val roster = roster("A")

        assertFalse(link(roster, "A", "A"))
        assertEquals(0, roster.state(HeroId("A"))!!.associations.size)
    }

    @Test
    fun aDeathAddsMemoryToSurvivorsAndChangesNothingElse() {
        // §6.5: "This is narrative memory only. Do not reduce attributes, suppress traits, or apply
        // a morale penalty."
        val roster = roster("A", "B")
        link(roster, "A", "B")
        val before = roster.state(HeroId("B"))!!

        HeroAssociations.recordDeath(roster, HeroId("A"), "s4", turn = 6, date = "1943-08-24", location = "Kharkov")

        val after = roster.state(HeroId("B"))!!
        val added = after.serviceEvents.single()
        assertEquals("associate_protege_killed", added.eventId)
        assertEquals(HeroId("A"), added.relatedHeroId)
        assertEquals(before.copy(serviceEvents = after.serviceEvents), after, "only the record changed")
    }

    @Test
    fun aDeadOfficerReceivesNoMemoryOfAnotherDeath() {
        val roster = roster("A", "B")
        link(roster, "A", "B")
        roster.updateState(roster.state(HeroId("B"))!!.copy(status = HeroStatus.KILLED))

        HeroAssociations.recordDeath(roster, HeroId("A"), "s4", turn = 6, date = null, location = null)

        assertEquals(0, roster.state(HeroId("B"))!!.serviceEvents.size)
    }

    @Test
    fun sharedOperationsAreComputedFromScenarioRecordsRatherThanStored() {
        // §6.2: a computed dossier line "cannot become inconsistent". Neither officer holds any
        // record OF the other -- the overlap is derived from where each of them was.
        val first =
            HeroState(
                heroId = HeroId("A"),
                rankId = "captain",
                serviceEvents =
                    listOf(HeroEvent("e", "s1", 1), HeroEvent("e", "s2", 1), HeroEvent("e", "s3", 1)),
            )
        val second =
            HeroState(
                heroId = HeroId("B"),
                rankId = "captain",
                serviceEvents = listOf(HeroEvent("e", "s2", 1), HeroEvent("e", "s3", 1), HeroEvent("e", "s9", 1)),
            )

        assertEquals(2, HeroAssociations.sharedOperations(first, second))
        assertEquals(0, HeroAssociations.sharedOperations(first, first.copy(serviceEvents = emptyList())))
    }

    @Test
    fun anAssociationSurvivesASaveRoundTrip() {
        val roster = roster("A", "B")
        link(roster, "A", "B", HeroAssociation.Type.PREDECESSOR)
        roster.putFormation(
            CoreFormation(
                id = FormationId("F-1"),
                ownerId = 0,
                country = 61,
                displayName = "24th Tank Brigade",
                currentEquipmentId = 1,
                unitClass = 2,
            ),
        )

        val restored =
            org.osada.hero.HeroSerializer.deserialize(
                JSON.parse(
                    JSON.stringify(
                        org.osada.hero.HeroSerializer
                            .serialize(roster),
                    ),
                ),
            )

        assertEquals(
            roster.state(HeroId("A"))!!.associations,
            restored.state(HeroId("A"))!!.associations,
        )
    }
}
