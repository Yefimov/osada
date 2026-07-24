package org.osada

import org.osada.hero.PortraitComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the layered-portrait composer (design brief §15). The load-bearing properties are the same
 * two the rest of the hero system rests on: determinism (§7.4 / §29.17 — a portrait must not reroll
 * on reload) and inspectability (every stored id must resolve to a real layer). Parity with the JS
 * core in `resources/portraits/portrait-core.mjs` is by construction — both use [org.osada.hero]'s
 * `SeededRandom` and key each category on the same `seedFrom(seed, category)` — and is additionally
 * checked end-to-end by `scripts/portraits/validate.mjs`.
 */
class PortraitCompositionTest {
    private val branches = listOf("infantry", "armor", "artillery", "aviation")
    private val genders = listOf("male", "female")
    private val ranks = listOf("lieutenant", "captain", "major", "colonel")
    private val ages = listOf("young", "middle", "old")

    // Prefix per category in [PortraitComposer.ORDER] order; index == stacking position.
    private val prefixOrder =
        listOf(
            "bg_",
            "uniform_",
            "head_",
            "eyes_",
            "nose_",
            "mouth_",
            "scar_",
            "facial_",
            "hair_",
            "headgear_",
            "rank_",
            "branch_",
            "age_",
            "wound_",
        )
    private val requiredPrefixes =
        listOf("bg_", "uniform_", "head_", "eyes_", "nose_", "mouth_", "hair_", "headgear_", "rank_", "branch_", "age_")

    private fun facts(
        branch: String = "infantry",
        gender: String = "male",
        rank: String = "captain",
        age: String = "middle",
        scar: Boolean = false,
        wound: String? = null,
    ) = PortraitComposer.Facts(branch, gender, rank, age, scar, wound)

    /** The full branch × gender × rank × age matrix, flattened so tests iterate a single loop. */
    private fun matrix(
        scar: Boolean = false,
        wound: String? = null,
    ): List<PortraitComposer.Facts> =
        branches.flatMap { b ->
            genders.flatMap { g -> ranks.flatMap { r -> ages.map { a -> facts(b, g, r, a, scar, wound) } } }
        }

    private fun orderIndexOf(id: String): Int = prefixOrder.indexOfFirst { id.startsWith(it) }

    @Test
    fun sameSeedAndFactsReproduceTheSamePortrait() {
        val f = facts(branch = "armor", gender = "male", rank = "major", age = "old")
        assertEquals(PortraitComposer.compose(f, 4242), PortraitComposer.compose(f, 4242))
    }

    @Test
    fun differentSeedsCanProduceDifferentPortraits() {
        val distinct = (1..40).map { PortraitComposer.compose(facts(), it * 101) }.toSet()
        assertTrue(distinct.size > 1, "seed had no effect on composition")
    }

    @Test
    fun everyComposedIdResolvesToALayerPath() {
        matrix(scar = true, wound = "head").forEach { f ->
            PortraitComposer.compose(f, 777).forEach { id ->
                assertNotNull(PortraitComposer.layerPath(id), "no path for composed id $id")
            }
        }
    }

    @Test
    fun requiredCategoriesArePresentInStackingOrder() {
        matrix().forEachIndexed { i, f ->
            val ids = PortraitComposer.compose(f, 1000 + i)
            requiredPrefixes.forEach { p -> assertTrue(ids.any { it.startsWith(p) }, "missing '$p' for $f") }
            val positions = ids.map(::orderIndexOf)
            assertTrue(positions.none { it < 0 }, "unknown category in $ids")
            assertEquals(positions.sorted(), positions, "layers not in stacking order")
            assertEquals(positions.toSet().size, positions.size, "two layers from one category")
        }
    }

    @Test
    fun factsDriveRankBranchAndAgeLayers() {
        val ids = PortraitComposer.compose(facts(branch = "artillery", rank = "colonel", age = "young"), 5)
        assertTrue("rank_colonel" in ids)
        assertTrue("branch_artillery" in ids)
        assertTrue("age_young" in ids)
        assertTrue("uniform_artillery_ussr_1942" in ids)
    }

    @Test
    fun femaleOfficersAreCleanShavenAndUseFemaleHeads() {
        (1..30).forEach { seed ->
            val ids = PortraitComposer.compose(facts(gender = "female"), seed)
            assertTrue(ids.any { it.startsWith("head_female_") }, "female head not selected at seed $seed")
            assertTrue("facial_clean" in ids, "female not clean-shaven at seed $seed")
        }
    }

    @Test
    fun scarAndWoundAreOptionalAndFactDriven() {
        val plain = PortraitComposer.compose(facts(), 9)
        assertTrue(plain.none { it.startsWith("scar_") }, "scar present without request")
        assertTrue(plain.none { it.startsWith("wound_") }, "wound present without request")

        val marked = PortraitComposer.compose(facts(scar = true, wound = "eye"), 9)
        assertTrue(marked.any { it.startsWith("scar_") }, "scar requested but absent")
        assertTrue("wound_eye_patch" in marked, "eye wound not mapped")
    }

    @Test
    fun aviationUsesTheFlightHelmet() {
        (1..20).forEach { seed ->
            assertTrue("headgear_flight_helmet" in PortraitComposer.compose(facts(branch = "aviation"), seed))
        }
    }

    @Test
    fun layerPathMapsPrefixesToDirectories() {
        assertEquals("portraits/layers/background/bg_field_gray.svg", PortraitComposer.layerPath("bg_field_gray"))
        assertEquals("portraits/layers/facial_hair/facial_beard.svg", PortraitComposer.layerPath("facial_beard"))
        assertEquals("portraits/layers/rank/rank_major.svg", PortraitComposer.layerPath("rank_major"))
        assertEquals("portraits/layers/wound/wound_arm_sling.svg", PortraitComposer.layerPath("wound_arm_sling"))
        assertNull(PortraitComposer.layerPath("totally_unknown_id"))
    }

    @Test
    fun composeForStoresSeedAndLayersAndReproduces() {
        val tank = org.osada.UnitClass.TANK.value
        val a = PortraitComposer.composeFor(31337, tank, "major", 1910, 1942)
        val b = PortraitComposer.composeFor(31337, tank, "major", 1910, 1942)
        assertEquals(31337, a.seed)
        assertTrue(a.layerIds.isNotEmpty())
        assertEquals(a.layerIds, b.layerIds, "composeFor must be deterministic")
        assertTrue("uniform_tank_ussr_1942" in a.layerIds)
        assertTrue("rank_major" in a.layerIds)
    }
}
