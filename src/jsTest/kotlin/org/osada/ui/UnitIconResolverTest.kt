package org.osada.ui

import kotlinx.browser.window
import org.osada.scenario.Scenario
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitIconResolverTest {
    private fun scenarioWith(
        ground: Int,
        iconset: Int,
    ) = Scenario("test.xml").also {
        it.ground = ground
        it.iconset = iconset
    }

    /** Frozen ground with no authored iconset is drawn as snow — the Operation Uranus case
     *  (November 1942, frozen, snow map art, `iconset` never set by the OG author). */
    @Test
    fun frozenGroundWithoutAuthoredIconsetRendersAsSnow() {
        val scenario = scenarioWith(ground = 1, iconset = 0)
        assertEquals(1, scenario.effectiveIconset)
        assertEquals(0, scenario.iconset, "the raw imported value must stay untouched")
    }

    @Test
    fun leavesAuthoredAndNonFrozenIconsetsAlone() {
        val authoredJungleOnIce = scenarioWith(ground = 1, iconset = 3)
        assertEquals(3, authoredJungleOnIce.effectiveIconset, "an authored iconset always wins")
        assertEquals(0, scenarioWith(ground = 0, iconset = 0).effectiveIconset)
        assertEquals(0, scenarioWith(ground = 2, iconset = 0).effectiveIconset, "mud is not winter")
    }

    @Test
    fun usesGeneratedVariantWhenPresent() {
        assertEquals(
            "resources/units/images/seasonal/example-snow.png",
            chooseSeasonalUnitIcon(
                "resources/units/images/lxf/example.png",
                "resources/units/images/seasonal/example-snow.png",
            ),
        )
    }

    @Test
    fun preservesDefaultWhenVariantIsMissingOrBlank() {
        val base = "resources/units/images/lxf/example.png"
        assertEquals(base, chooseSeasonalUnitIcon(base, null))
        assertEquals(base, chooseSeasonalUnitIcon(base, ""))
    }

    @Test
    fun resolvesManifestByScenarioIconsetAndFallsBackForUnmappedClimate() {
        val base = "resources/units/images/lxf/example.png"
        val snow = "resources/units/images/seasonal/example-snow.png"
        val global = window.asDynamic()
        val previous: dynamic = global.seasonalUnitIcons
        global.seasonalUnitIcons = json(base to json("1" to snow))
        try {
            assertEquals(snow, UnitIconResolver.resolve(base, 1))
            assertEquals(base, UnitIconResolver.resolve(base, 2))
            assertEquals(base, UnitIconResolver.resolve(base, 0))
        } finally {
            global.seasonalUnitIcons = previous
        }
    }
}
