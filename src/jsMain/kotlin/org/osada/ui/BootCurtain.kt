package org.osada.ui

/**
 * The application's own loading screen, raised by `index.html` as static markup and taken down
 * here.
 *
 * It cannot be built the way [ScenarioLoadingCurtain] is. Everything Kotlin does starts on
 * `window.load`, which waits for every image, font and data script the page declares, and only
 * then come the i18n bundle, the equipment tables and the image cache — so a curtain the bundle
 * builds would first appear at the moment it is no longer needed. Until this existed the page
 * simply showed its raw chrome for the whole of that wait: an empty `#statusbar` carrying the
 * Turn Report button, with grey below it (reported 2026-09-07).
 *
 * The node is REMOVED rather than hidden. It is raised exactly once per page load, so leaving an
 * inert full-screen element over the game buys nothing and risks swallowing a tap.
 */
internal object BootCurtain {
    private const val ID = "osadaBootCurtain"

    /** Takes the curtain down. Safe to call more than once, and when it was never there. */
    fun hide() {
        delTag(byId(ID))
    }
}
