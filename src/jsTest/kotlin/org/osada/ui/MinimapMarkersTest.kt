package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The minimap half of the accessibility audit
 * (`docs/design/accessible-side-identification.md` §3, roadmap P2 item 2).
 *
 * The design asks whether the existing black enemy outline survives "every supported scale and
 * palette", and says Enhanced Side Markers should only touch the minimap if it does not. The two
 * rendered scales the layout can produce are:
 *
 * - desktop: `#osada-minimap { width: 240px; height: 160px }` against a 240x160 bitmap, so 1.0;
 * - phone drawer: `width: 100%`, so anything the drawer is wide divided by 240 -- below 1.0 on a
 *   narrow phone, which is the case a fixed 1-bitmap-pixel rim silently lost.
 *
 * These assertions are the audit's result, kept executable so a later layout change cannot quietly
 * fail it again.
 */
class MinimapMarkersTest {
    private val supportedScales = listOf(0.5, 0.6, 0.75, 0.9, 1.0, 1.25, 2.0)

    @Test
    fun enemyRimStaysAtLeastOneRenderedPixelAtEverySupportedScale() {
        for (scale in supportedScales) {
            val rendered = MinimapMarkers.renderedRimWidth(scale)
            assertTrue(
                rendered >= 1.0,
                "at render scale $scale the enemy rim is ${rendered}px on screen, i.e. antialiasing",
            )
        }
    }

    /** An unknown/unmeasurable box must not inflate the marker -- it falls back to the desktop rim. */
    @Test
    fun unknownScaleFallsBackToTheDesktopRim() {
        assertEquals(1.0, MinimapMarkers.enemyRimWidth(0.0))
        assertEquals(1.0, MinimapMarkers.enemyRimWidth(-1.0))
    }

    /** Past a point the ring stops reading as an outline and becomes a bigger black dot; the cap
     *  keeps the pale core the larger area so the marker keeps both halves of its redundancy. */
    @Test
    fun rimIsCappedSoItNeverBecomesTheMarker() {
        assertTrue(MinimapMarkers.enemyRimWidth(0.05) < MinimapMarkers.CORE_RADIUS)
    }

    /**
     * Shape redundancy, not colour: the enemy marker is strictly larger than the friendly one at
     * every scale, so the two are told apart in grayscale and under colour-vision simulation even
     * where the terrain underneath swallows one of the two fills.
     */
    @Test
    fun enemyMarkerIsAlwaysLargerThanTheFriendlyOne() {
        for (scale in supportedScales) {
            assertTrue(
                MinimapMarkers.enemyOuterRadius(scale) > MinimapMarkers.CORE_RADIUS,
                "enemy and friendly markers are the same size at scale $scale",
            )
        }
    }

    /**
     * Palette redundancy: the enemy marker pairs a near-white core with a near-black rim, so no
     * single terrain colour can hide both. This asserts the pairing itself rather than a rendered
     * screenshot, which is what makes it a regression gate.
     */
    @Test
    fun enemyFillAndRimSitAtOppositeEndsOfTheRange() {
        assertTrue(luminance(MinimapMarkers.ENEMY_FILL) > 0.8, "enemy core must be near-white")
        assertTrue(luminance(MinimapMarkers.ENEMY_RIM_FILL) < 0.2, "enemy rim must be near-black")
    }

    private fun luminance(hex: String): Double {
        val value = hex.removePrefix("#")
        val r = value.substring(0, 2).toInt(16) / 255.0
        val g = value.substring(2, 4).toInt(16) / 255.0
        val b = value.substring(4, 6).toInt(16) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
