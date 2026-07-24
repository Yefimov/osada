package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.FormationId
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroCasualtyService
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroDossierAssembler
import org.osada.hero.HeroId
import org.osada.hero.HeroInjury
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.HeroStatus
import org.osada.hero.PortraitComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards Phase 5 wounds/death/memorials (design brief §11, §29.13, §29.14): the seeded casualty
 * outcome (deterministic, with meaningful survival odds and encirclement making it grimmer),
 * injury persistence through the save, and the fallen commander surfacing as In Memoriam.
 */
class HeroCasualtyTest {
    private val heroId = HeroId("H-9")
    private val formationId = FormationId("F-9")

    private fun definition() =
        HeroDefinition(
            id = heroId,
            origin = HeroOrigin.PROCEDURAL,
            displayName = "Sergei Vorontsov",
            backgroundId = "armored_academy_graduate",
            biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
            portrait = PortraitComposition(seed = 7),
        )

    private fun killedState() =
        HeroState(
            heroId = heroId,
            rankId = "major",
            status = HeroStatus.KILLED,
            injuries = listOf(HeroInjury("serious_wound", "3", permanent = true)),
        )

    private fun context(
        surrendered: Boolean,
        safeSupply: Boolean,
        seed: Int,
    ) = HeroCasualtyService.Context(surrendered, safeSupply, seed)

    @Test
    fun casualtyIsDeterministic() {
        val ctx = context(surrendered = true, safeSupply = false, seed = 42)
        assertEquals(HeroCasualtyService.resolve(ctx, "1"), HeroCasualtyService.resolve(ctx, "1"))
    }

    @Test
    fun encirclementMakesTheOutcomeGrimmer() {
        val open = sample(surrendered = false, safeSupply = true)
        val encircled = sample(surrendered = true, safeSupply = false)
        assertTrue(grim(encircled) > grim(open), "surrender/encirclement must raise captured+missing+killed")
        assertTrue(survived(open) > survived(encircled), "an intact supply route must raise survival")
    }

    @Test
    fun injuryAndDetachmentFollowTheDisposition() {
        (0 until 400).forEach { seed ->
            val outcome = HeroCasualtyService.resolve(context(seed % 2 == 0, seed % 3 == 0, seed), "2")
            when (outcome.disposition) {
                HeroCasualtyService.Disposition.LIGHTLY_WOUNDED -> {
                    assertEquals("light_wound", outcome.injury?.injuryId)
                    assertEquals(false, outcome.injury?.permanent)
                    assertTrue(!outcome.detach, "a lightly wounded commander stays with the formation")
                }
                HeroCasualtyService.Disposition.SERIOUSLY_WOUNDED -> {
                    assertEquals("serious_wound", outcome.injury?.injuryId)
                    assertEquals(true, outcome.injury?.permanent)
                    assertTrue(outcome.detach)
                }
                else -> {
                    assertNull(outcome.injury)
                    assertTrue(outcome.detach)
                }
            }
        }
    }

    @Test
    fun statusAndInjuriesSurviveTheSave() {
        val roster = HeroRoster().apply { putHero(definition(), killedState()) }
        val restored = HeroSerializer.deserialize(HeroSerializer.serialize(roster))
        val state = assertNotNull(restored.state(heroId))
        assertEquals(HeroStatus.KILLED, state.status)
        assertEquals(1, state.injuries.size)
        assertEquals("serious_wound", state.injuries[0].injuryId)
        assertTrue(state.injuries[0].permanent)
    }

    @Test
    fun fallenCommanderIsInMemoriam() {
        val formation =
            CoreFormation(
                id = formationId,
                ownerId = 0,
                country = 1,
                displayName = "24th Tank Brigade",
                currentEquipmentId = 900,
                unitClass = UnitClass.TANK.value,
            )
        val view = HeroDossierAssembler.dossier(definition(), killedState(), formation, null)
        assertTrue(view.inMemoriam)
        assertTrue(view.injuries.any { it.contains("Serious wound") })
    }

    private fun sample(
        surrendered: Boolean,
        safeSupply: Boolean,
    ): Map<HeroCasualtyService.Disposition, Int> {
        val counts = mutableMapOf<HeroCasualtyService.Disposition, Int>()
        (0 until 600).forEach { seed ->
            val d = HeroCasualtyService.resolve(context(surrendered, safeSupply, seed), "1").disposition
            counts[d] = (counts[d] ?: 0) + 1
        }
        return counts
    }

    private fun grim(counts: Map<HeroCasualtyService.Disposition, Int>): Int =
        (counts[HeroCasualtyService.Disposition.CAPTURED] ?: 0) +
            (counts[HeroCasualtyService.Disposition.MISSING] ?: 0) +
            (counts[HeroCasualtyService.Disposition.KILLED] ?: 0)

    private fun survived(counts: Map<HeroCasualtyService.Disposition, Int>): Int =
        (counts[HeroCasualtyService.Disposition.EVACUATED] ?: 0) +
            (counts[HeroCasualtyService.Disposition.LIGHTLY_WOUNDED] ?: 0)
}
