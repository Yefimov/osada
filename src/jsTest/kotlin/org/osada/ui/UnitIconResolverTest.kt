package org.osada.ui

import kotlinx.browser.window
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals

class UnitIconResolverTest {
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
