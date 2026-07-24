package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroId
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroPotential
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.PortraitComposition
import org.osada.hero.SeededRandom
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Formation identity and hero-roster persistence.
 *
 * The formation id is the foundation the rest of the hero system hangs off: if it is not stable
 * across an equipment upgrade and a scenario transition, heroes detach from their units.
 */
class HeroPersistenceTest {
    private companion object {
        const val TANK_EQID = 900
        const val BETTER_TANK_EQID = 901
    }

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        listOf(TANK_EQID, BETTER_TANK_EQID).forEach { id ->
            Equipment.putEquipment(
                id,
                EquipmentData().apply {
                    uclass = UnitClass.TANK.value
                    name = "Tank $id"
                },
            )
        }
        HeroCampaign.reset()
    }

    @AfterTest
    fun tearDown() {
        HeroCampaign.reset()
    }

    private fun coreUnit(owner: Int = 0): GameUnit =
        GameUnit(TANK_EQID).apply {
            this.owner = owner
            player = Player().apply { id = owner }
        }

    // ------------------------------------------------------- formation identity

    @Test
    fun joiningTheCoreRosterMintsExactlyOneStableId() {
        val player = Player().apply { id = 0 }
        val first = coreUnit()
        val second = coreUnit()
        player.addCoreUnit(first)
        player.addCoreUnit(second)

        val firstId = assertNotNull(FormationIdentity.of(first))
        val secondId = assertNotNull(FormationIdentity.of(second))
        assertTrue(firstId != secondId, "two formations must not share an id")

        // Re-adding on a later scenario load must not re-mint.
        player.addCoreUnit(first)
        assertEquals(firstId, FormationIdentity.of(first))
    }

    @Test
    fun formationIdSurvivesAnEquipmentUpgrade() {
        val unit = coreUnit()
        Player().apply { id = 0 }.addCoreUnit(unit)
        val before = assertNotNull(FormationIdentity.of(unit))

        assertTrue(unit.upgrade(BETTER_TANK_EQID, 0), "same-class upgrade should succeed")

        assertEquals(BETTER_TANK_EQID, unit.eqid, "equipment did change")
        assertEquals(before, FormationIdentity.of(unit), "the formation is the same brigade after re-equipping")
    }

    @Test
    fun mintingSkipsIdsAlreadyRestoredFromASave() {
        // A restored roster holds F-0-1 and F-0-4; the next mint must not collide with either.
        val next = FormationIdentity.nextFor(owner = 0, existing = listOf("F-0-1", "F-0-4"))
        assertEquals(FormationId("F-0-5"), next)
    }

    @Test
    fun nonCoreUnitsHaveNoFormationId() {
        assertNull(FormationIdentity.of(coreUnit()), "a unit that never joined a core roster has no formation")
    }

    // ----------------------------------------------------------- roster save/load

    @Test
    fun rosterSurvivesASaveRoundTrip() {
        val formationId = FormationId("F-0-1")
        val heroId = HeroId("H-F-0-1")
        val roster =
            HeroRoster().apply {
                putFormation(
                    CoreFormation(
                        id = formationId,
                        ownerId = 0,
                        country = 3,
                        displayName = "24th Tank Brigade",
                        currentEquipmentId = TANK_EQID,
                        unitClass = UnitClass.TANK.value,
                        assignedHeroId = heroId,
                    ),
                )
                putHero(
                    HeroDefinition(
                        id = heroId,
                        origin = HeroOrigin.PROCEDURAL,
                        displayName = "K. Novak",
                        backgroundId = "armored_academy_graduate",
                        biographyFacts = org.osada.hero.HeroBiographyFacts(emergenceEventId = "test"),
                        portrait = PortraitComposition(seed = 12345),
                    ),
                    HeroState(
                        heroId = heroId,
                        rankId = "major",
                        potential = HeroPotential.PROMISING,
                        assignedFormationId = formationId,
                        learnedTraitIds = setOf("legacy.AGGRESSIVE_ATTACK"),
                    ),
                )
            }

        // Through a real JSON string, not just the dynamic object, so key mangling would show up.
        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))

        val formation = assertNotNull(restored.formation(formationId))
        assertEquals("24th Tank Brigade", formation.displayName)
        assertEquals(heroId, formation.assignedHeroId)

        val definition = assertNotNull(restored.definition(heroId))
        assertEquals("K. Novak", definition.displayName)
        assertEquals("armored_academy_graduate", definition.backgroundId)
        assertEquals(12345, definition.portrait.seed)

        val state = assertNotNull(restored.assignedHero(formationId))
        assertEquals("major", state.rankId)
        assertEquals(HeroPotential.PROMISING, state.potential)
        assertEquals(setOf("legacy.AGGRESSIVE_ATTACK"), state.learnedTraitIds)
    }

    @Test
    fun preHeroSavesRestoreToAnEmptyRoster() {
        // Backward compatibility is by absence: an old save simply has no `heroes` key.
        assertTrue(HeroSerializer.deserialize(null).isEmpty)
        assertTrue(HeroSerializer.deserialize(js("({})")).isEmpty)
    }

    @Test
    fun corruptRosterDataDegradesInsteadOfFailingTheLoad() {
        val garbage =
            JSON.parse<dynamic>("""{"formations":[{"owner":"nonsense"},{"id":"F-0-2"}],"heroes":"not-an-array"}""")
        val restored = HeroSerializer.deserialize(garbage)

        assertEquals(
            listOf(FormationId("F-0-2")),
            restored.allFormations().map { it.id },
            "the entry without an id is dropped; the usable one survives",
        )
    }

    @Test
    fun emptyRosterIsOmittedFromTheSave() {
        // snapshot() is `dynamic`, so compare explicitly rather than via assertNull's generic.
        assertTrue(HeroCampaign.snapshot() == null, "a run with no formations must not add a `heroes` key")
    }

    // ------------------------------------------------------------------- seeding

    @Test
    fun seededRandomIsReproducible() {
        val seed = SeededRandom.seedFrom("campaign.json", "F-0-1")
        val first = List(5) { SeededRandom(seed).nextInt(100) }
        val second = List(5) { SeededRandom(seed).nextInt(100) }
        assertEquals(first, second, "the same seed must always produce the same stream")

        assertTrue(
            SeededRandom.seedFrom("campaign.json", "F-0-1") != SeededRandom.seedFrom("campaign.json", "F-0-2"),
            "different formations must seed differently",
        )
    }

    @Test
    fun seededRandomStaysInRange() {
        val rng = SeededRandom(SeededRandom.seedFrom("range-check"))
        repeat(500) {
            val value = rng.nextInt(10)
            assertTrue(value in 0..9, "nextInt(10) produced $value")
        }
    }
}
