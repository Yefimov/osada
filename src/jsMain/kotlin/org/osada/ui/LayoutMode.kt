package org.osada.ui

/**
 * Semantic layout modes (spec §9.2). Chosen from measured capabilities and viewport size, never
 * from the User-Agent string — a phone, a tablet, a touchscreen laptop and a desktop with a
 * touchscreen monitor are four different products and only measurement can tell them apart.
 */
internal enum class LayoutMode {
    DESKTOP,
    TABLET,
    PHONE_LANDSCAPE,
    PHONE_COMPACT,
    PHONE_PORTRAIT,
    ;

    /** Phone shell: drawer instead of sidebar, bottom action dock, CSS-owned map geometry. */
    val isPhone: Boolean get() = this == PHONE_LANDSCAPE || this == PHONE_COMPACT || this == PHONE_PORTRAIT

    /** Any mode where mobile CSS and touch-first geometry apply. */
    val isMobileShell: Boolean get() = this != DESKTOP
}

/** The user's explicit "Mobile interface" preference. */
internal object MobileUiOverride {
    const val AUTO = "auto"
    const val ON = "on"
    const val OFF = "off"
}

/**
 * Breakpoints. Implementation defaults tuned against the spec's required viewport matrix (§8.2),
 * not product constants — a phone in landscape is short rather than narrow, so height carries most
 * of the decision.
 */
private const val COMPACT_MAX_HEIGHT = 380.0
private const val COMPACT_MAX_WIDTH = 720.0
private const val PHONE_MAX_HEIGHT = 600.0
private const val PHONE_MAX_WIDTH = 1100.0
private const val TABLET_MIN_PORTRAIT_WIDTH = 600.0

/**
 * Pure layout-mode decision, extracted so every required viewport in the spec's matrix can be
 * asserted without a browser.
 *
 * [coarsePointer] is `matchMedia("(pointer: coarse)")` — the *primary* pointer. It distinguishes
 * touch-first tablets and large convertible displays, but an unmistakably phone-sized viewport is
 * enough on its own. Desktop device emulators commonly keep reporting a fine mouse pointer while
 * constraining the page to 393x852; treating that as desktop produces an unusable clipped shell.
 */
@Suppress("ReturnCount")
internal fun resolveLayoutMode(
    coarsePointer: Boolean,
    width: Double,
    height: Double,
    override: String,
): LayoutMode {
    if (override == MobileUiOverride.OFF) return LayoutMode.DESKTOP
    val portrait = height > width
    val phoneSizedViewport =
        if (portrait) {
            width < TABLET_MIN_PORTRAIT_WIDTH
        } else {
            height <= PHONE_MAX_HEIGHT && width <= PHONE_MAX_WIDTH
        }
    val mobileLayout = coarsePointer || override == MobileUiOverride.ON || phoneSizedViewport
    if (!mobileLayout) return LayoutMode.DESKTOP

    if (portrait) {
        // Portrait: a tablet has room to render the tablet layout upright; a phone does not and
        // gets the rotation gate instead.
        return if (width >= TABLET_MIN_PORTRAIT_WIDTH) LayoutMode.TABLET else LayoutMode.PHONE_PORTRAIT
    }
    if (height < COMPACT_MAX_HEIGHT || width < COMPACT_MAX_WIDTH) return LayoutMode.PHONE_COMPACT
    if (height <= PHONE_MAX_HEIGHT && width <= PHONE_MAX_WIDTH) return LayoutMode.PHONE_LANDSCAPE
    return LayoutMode.TABLET
}
