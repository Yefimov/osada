package org.osada.multiplayer.coop

import org.osada.hero.HeroPortraitArt
import org.osada.hero.PortraitComposerV2
import kotlin.js.JSON
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoopPortraitGenerationTest {
    @Test
    fun chosenGenderWorksForEveryEraAndSurvivesProfileSerialization() {
        HeroPortraitArt.Era.entries.forEach { era ->
            listOf(false, true).forEach { female ->
                val portrait = CoopPortraitGeneration.generate(Int.MIN_VALUE, era.name, female)
                assertTrue(portrait.isValid(), era.name)
                assertEquals(female, portrait.layerIds.any { it.startsWith("face_female_") }, era.name)
                assertEquals(portrait, CoopPortraitGeneration.generate(Int.MIN_VALUE, era.name, female))
                val profile = CoopCommanderProfile("Ada", portrait, emptyList())
                val wire = JSON.parse<dynamic>(JSON.stringify(CoopStateJson.encodeProfile(profile)))
                assertEquals(portrait, assertNotNull(CoopStateJson.decodeProfile(wire)).portrait)
            }
        }
    }

    @Test
    fun eraChangesTheClothingRatherThanJustThePaintedPortraitFilter() {
        val ancient = CoopPortraitGeneration.generate(19, "ANTIQUITY", true)
        assertTrue("collar_ancient_rebel" in ancient.layerIds)
        assertFalse(ancient.layerIds.any { it.startsWith("rank_pre1943_") })
        val spanish = CoopPortraitGeneration.generate(19, "SPANISH_CIVIL_WAR", true)
        assertTrue("collar_spanish_republic" in spanish.layerIds)
        assertFalse(spanish.layerIds.any { it.startsWith("rank_ancient_") })
        val early = CoopPortraitGeneration.generate(19, "FORTY_EIGHTER", true)
        assertTrue(early.layerIds.any { it.startsWith("back_") })
        assertTrue(early.layerIds.any { it.startsWith("hair_front_") })
        assertFalse(
            early.layerIds.any {
                it.startsWith("headgear_") ||
                    it.startsWith("rank_") ||
                    it.startsWith("collar_") ||
                    it.startsWith("branch_")
            },
        )
    }

    @Test
    fun allErasProducesDifferentPoolsAndDefaultHeroGenderRemainsUnchanged() {
        val pools = (0..30).map { CoopPortraitGeneration.generate(it, null, false).poolId }.toSet()
        assertTrue(pools.size > 1)
        (0..30).forEach { seed ->
            assertEquals(
                CoopPortrait.generated(seed),
                CoopPortrait.generated(seed, female = PortraitComposerV2.genderFor(seed) == "female"),
            )
        }
    }
}
