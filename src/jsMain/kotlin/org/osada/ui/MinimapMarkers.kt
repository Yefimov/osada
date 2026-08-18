package org.osada.ui

/**
 * Geometry and palette of the minimap's unit/objective dots, extracted from [MinimapBuilder] so the
 * accessibility audit in `docs/design/accessible-side-identification.md` §3 can be run as an
 * assertion instead of by eye.
 *
 * That audit asks three questions of the existing black enemy rim, and this is where each is
 * answered:
 *
 * 1. **Does it survive every supported scale?** The minimap bitmap is a fixed 240x160, but its
 *    rendered box is not: desktop CSS pins it 1:1, while the phone drawer sets `width: 100%` and
 *    lets it shrink. A rim fixed at 1 bitmap px therefore fell below one CSS pixel on a narrow
 *    drawer and was antialiased into the fill. [enemyRimWidth] scales it so the drawn ring is never
 *    thinner than one rendered pixel, whatever box the layout gives it.
 * 2. **Does it survive every palette?** The minimap composites real terrain artwork, so no single
 *    colour is safe: the white core disappears over snow and pale towns, the black rim over forest
 *    and deep water. Together they cannot both vanish -- one of the two always contrasts with
 *    whatever is underneath, which is the actual redundancy and why the rim must not be replaced by
 *    a palette-only treatment.
 * 3. **Is the silhouette distinguishable at the smallest size?** Enemy markers are drawn larger than
 *    friendly ones by exactly the rim, so the two differ in SIZE and in ring-versus-disc shape, not
 *    only in colour -- readable in grayscale and under colour-vision simulation.
 *
 * Because those three hold, Enhanced Side Markers deliberately does NOT add star/skull symbols to
 * the minimap (design §3: a skull at this size is visual noise).
 */
internal object MinimapMarkers {
    const val BITMAP_WIDTH = 240
    const val BITMAP_HEIGHT = 160

    const val OWN_FILL = "#c9463d"
    const val ENEMY_FILL = "#f2f0e8"
    const val ENEMY_RIM_FILL = "#0b0c0e"
    const val OBJECTIVE_FILL = "#d9b25a"

    /** Marker core radius, in bitmap pixels. Identical for both sides -- only the rim differs. */
    const val CORE_RADIUS = 2.2

    const val OBJECTIVE_RADIUS = 2.0

    private const val BASE_RIM = 1.0

    /** A ring thinner than one RENDERED pixel is antialiasing, not an outline. */
    private const val MIN_RIM_RENDERED_PX = 1.0

    /**
     * Past this the ring stops reading as an outline and starts reading as a bigger black dot.
     * Held below [CORE_RADIUS] on purpose: the white core must stay the larger of the two areas, or
     * the marker loses the pale half of its two-colour redundancy over dark terrain.
     *
     * The cap binds below a render scale of 0.5, i.e. a minimap box under 120 CSS px wide. No
     * supported layout produces one: the desktop stylesheet pins 240 px and the narrowest phone
     * drawer is far wider than that.
     */
    private const val MAX_RIM = 2.0

    /**
     * Enemy rim thickness in bitmap pixels for a canvas rendered at [renderScale]
     * (rendered CSS width / [BITMAP_WIDTH]). A non-positive or unknown scale falls back to the
     * 1:1 desktop value rather than inflating the marker.
     */
    fun enemyRimWidth(renderScale: Double): Double =
        if (renderScale <= 0.0) {
            BASE_RIM
        } else {
            (MIN_RIM_RENDERED_PX / renderScale).coerceIn(BASE_RIM, MAX_RIM)
        }

    /** Outer radius of the enemy marker, i.e. the black ring's edge. */
    fun enemyOuterRadius(renderScale: Double): Double = CORE_RADIUS + enemyRimWidth(renderScale)

    /** The rim thickness the player actually sees, in rendered CSS pixels. The audit assertion. */
    fun renderedRimWidth(renderScale: Double): Double =
        if (renderScale <= 0.0) BASE_RIM else enemyRimWidth(renderScale) * renderScale
}
