package org.osada.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Layout-mode decisions for every viewport in the spec's required matrix (§8.2-§8.4, §61.3).
 * These are the guard against the two classic misclassifications: a touchscreen laptop dropping
 * into a phone shell, and a phone being handed the desktop sidebar.
 */
class LayoutModeTest {
    private fun mode(
        width: Double,
        height: Double,
        coarse: Boolean = true,
        override: String = MobileUiOverride.AUTO,
    ) = resolveLayoutMode(coarse, width, height, override)

    @Test
    fun requiredPhoneLandscapeViewportsUsePhoneShell() {
        listOf(
            667.0 to 375.0,
            740.0 to 360.0,
            812.0 to 375.0,
            844.0 to 390.0,
            873.0 to 393.0,
            915.0 to 412.0,
            932.0 to 430.0,
        ).forEach { (w, h) ->
            val resolved = mode(w, h)
            assertEquals(true, resolved.isPhone, "${w.toInt()}x${h.toInt()} should be a phone layout, got $resolved")
        }
    }

    @Test
    fun shortPhoneLandscapeIsCompact() {
        assertEquals(LayoutMode.PHONE_COMPACT, mode(667.0, 375.0))
        assertEquals(LayoutMode.PHONE_LANDSCAPE, mode(844.0, 390.0))
    }

    @Test
    fun requiredTabletViewportsUseTabletShell() {
        assertEquals(LayoutMode.TABLET, mode(1024.0, 768.0))
        assertEquals(LayoutMode.TABLET, mode(1180.0, 820.0))
        assertEquals(LayoutMode.TABLET, mode(1280.0, 800.0))
    }

    @Test
    fun desktopViewportsWithFinePointerStayDesktop() {
        listOf(1366.0 to 768.0, 1536.0 to 864.0, 1920.0 to 1080.0).forEach { (w, h) ->
            assertEquals(LayoutMode.DESKTOP, mode(w, h, coarse = false))
        }
    }

    @Test
    fun phoneSizedDeviceEmulationUsesPhoneShellWithFinePointer() {
        // Codex/Chromium device panels constrain the viewport but may retain the host mouse as the
        // primary pointer. Layout follows the available canvas; input modality remains independent.
        assertEquals(LayoutMode.PHONE_PORTRAIT, mode(393.0, 852.0, coarse = false))
        assertEquals(LayoutMode.PHONE_LANDSCAPE, mode(852.0, 393.0, coarse = false))
    }

    @Test
    fun touchscreenLaptopIsNotAutomaticallyAPhone() {
        // Coarse primary pointer reported on a large landscape viewport: a convertible/kiosk, not
        // a phone. It gets touch-sized targets through the tablet shell, never the phone drawer.
        assertEquals(LayoutMode.TABLET, mode(1920.0, 1080.0, coarse = true))
    }

    @Test
    fun manualOverrideWinsInBothDirections() {
        assertEquals(LayoutMode.DESKTOP, mode(667.0, 375.0, override = MobileUiOverride.OFF))
        assertEquals(LayoutMode.PHONE_COMPACT, mode(667.0, 375.0, coarse = false, override = MobileUiOverride.ON))
    }

    @Test
    fun phonePortraitTriggersTheGateButTabletPortraitDoesNot() {
        assertEquals(LayoutMode.PHONE_PORTRAIT, mode(390.0, 844.0))
        assertEquals(LayoutMode.TABLET, mode(768.0, 1024.0))
    }

    @Test
    fun minimapCoordinatesUseTheRenderedCanvasSize() {
        assertEquals(0.5, minimapFraction(280.0, renderedStart = 100.0, renderedSize = 360.0))
        assertEquals(0.0, minimapFraction(80.0, renderedStart = 100.0, renderedSize = 360.0))
        assertEquals(1.0, minimapFraction(500.0, renderedStart = 100.0, renderedSize = 360.0))
        assertEquals(0.0, minimapFraction(100.0, renderedStart = 100.0, renderedSize = 0.0))
    }

    @Test
    fun measuredHudHeightDoesNotCountSafeAreaTwice() {
        assertEquals(48.0, heightExcludingSafeArea(renderedHeight = 72.0, safeInset = 24.0))
        assertEquals(0.0, heightExcludingSafeArea(renderedHeight = 12.0, safeInset = 24.0))
    }
}
