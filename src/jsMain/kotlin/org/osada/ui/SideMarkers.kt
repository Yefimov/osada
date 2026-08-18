package org.osada.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Optional shape-based allegiance channel for strategic-zoom unit flags
 * (`docs/design/accessible-side-identification.md`).
 *
 * Several scenarios put historically related red palettes on both sides -- Hungarian Revolution is
 * the reported case -- so the national flag cannot answer "whose unit is that?" on its own. This
 * adds a second, non-colour channel: a star on the player's side, a skull on the opposing one.
 *
 * Two hard rules from the design, both enforced here rather than at the call site:
 *
 * - allegiance comes from the unit owner's **side**, never from equipment country, the displayed
 *   national flag, palette or unit name. A support-country formation is part of the player's order
 *   of battle and stays friendly even though its flag is foreign (`DEFERRED.md` §§5.2, 5.6);
 * - the reference side is the SPOTTING side, not the current player's id, so an observer (and the
 *   player watching an AI turn) keeps reading the map from their own point of view.
 *
 * Shapes are canvas paths rather than font glyphs: a glyph's outline changes by platform and font
 * fallback, and this mark has to mean exactly one thing at 12 px.
 */
internal enum class SideMarker {
    FRIENDLY,
    ENEMY,
}

internal object SideMarkers {
    // Star: light fill, dark outline. Skull: dark fill, light outline. Inverting the two keeps both
    // legible over a pale flag (Poland, Japan) and a dark one (the black-heavy German/SS sheets).
    private const val STAR_FILL = "#f6f3e8"
    private const val STAR_STROKE = "#101208"
    private const val SKULL_FILL = "#101218"
    private const val SKULL_STROKE = "#f6f3e8"

    /** Badge edge as a fraction of the drawn flag's height -- scaled from the flag bounds, never a
     *  hard-coded screen size, so it tracks map zoom and retina backing scale (design §2). */
    private const val BADGE_SCALE = 0.62
    private const val MIN_BADGE_PX = 7.0
    private const val OUTLINE_RATIO = 0.12
    private const val MIN_OUTLINE_PX = 1.0

    private const val STAR_POINTS = 5
    private const val STAR_INNER_RATIO = 0.42

    // Skull proportions, all as fractions of the badge edge (or of the cranium, for the features
    // inside it), so the shape is defined once and scales with the flag like everything else here.
    private const val BADGE_INSET_RATIO = 0.42
    private const val CRANIUM_RADIUS_RATIO = 0.36
    private const val CRANIUM_RISE_RATIO = 0.08
    private const val JAW_WIDTH_RATIO = 0.34
    private const val JAW_HEIGHT_RATIO = 0.22
    private const val JAW_TOP_RATIO = 0.55
    private const val EYE_RADIUS_RATIO = 0.30
    private const val EYE_OFFSET_RATIO = 0.42
    private const val EYE_RISE_RATIO = 0.08

    /**
     * Allegiance of a unit owned by [unitSide] as seen from [spotSide], or null when the unit has
     * no owning side at all (a scenario-authored unit with no player, which gets no badge rather
     * than a guessed one).
     */
    fun classify(
        unitSide: Int?,
        spotSide: Int,
    ): SideMarker? =
        when (unitSide) {
            null -> null
            spotSide -> SideMarker.FRIENDLY
            else -> SideMarker.ENEMY
        }

    /** Badge edge length for a flag drawn [flagHeight] tall. */
    fun badgeSize(flagHeight: Double): Double = (flagHeight * BADGE_SCALE).coerceAtLeast(MIN_BADGE_PX)

    /**
     * Draws [marker] into the bottom-right corner of the flag rectangle
     * (`x`,`y`,`width`,`height`), overlapping its edge slightly so the badge reads as attached to
     * the flag rather than as a separate map symbol.
     */
    fun draw(
        ctx: dynamic,
        marker: SideMarker,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) {
        val size = badgeSize(height)
        val cx = x + width - size * BADGE_INSET_RATIO
        val cy = y + height - size * BADGE_INSET_RATIO
        ctx.save()
        ctx.lineJoin = "round"
        ctx.lineWidth = (size * OUTLINE_RATIO).coerceAtLeast(MIN_OUTLINE_PX)
        when (marker) {
            SideMarker.FRIENDLY -> drawStar(ctx, cx, cy, size / 2.0)
            SideMarker.ENEMY -> drawSkull(ctx, cx, cy, size)
        }
        ctx.restore()
    }

    /** A five-point star, first point straight up. */
    private fun drawStar(
        ctx: dynamic,
        cx: Double,
        cy: Double,
        radius: Double,
    ) {
        val inner = radius * STAR_INNER_RATIO
        ctx.beginPath()
        for (index in 0 until STAR_POINTS * 2) {
            val r = if (index % 2 == 0) radius else inner
            val angle = -PI / 2.0 + index * PI / STAR_POINTS
            val px = cx + r * cos(angle)
            val py = cy + r * sin(angle)
            if (index == 0) ctx.moveTo(px, py) else ctx.lineTo(px, py)
        }
        ctx.closePath()
        ctx.fillStyle = STAR_FILL
        ctx.fill()
        ctx.strokeStyle = STAR_STROKE
        ctx.stroke()
    }

    /**
     * A skull reduced to what survives at badge size: a domed cranium, a narrower jaw and two eye
     * sockets. The sockets are painted in the OUTLINE colour, not left transparent -- a hole would
     * show whatever flag colour happens to be under it, which is the one thing this mark exists to
     * stop depending on.
     */
    private fun drawSkull(
        ctx: dynamic,
        cx: Double,
        cy: Double,
        size: Double,
    ) {
        val craniumRadius = size * CRANIUM_RADIUS_RATIO
        val craniumCy = cy - size * CRANIUM_RISE_RATIO
        ctx.fillStyle = SKULL_FILL
        ctx.strokeStyle = SKULL_STROKE

        ctx.beginPath()
        ctx.arc(cx, craniumCy, craniumRadius, 0.0, 2.0 * PI)
        ctx.fill()
        ctx.stroke()

        val jawWidth = size * JAW_WIDTH_RATIO
        val jawHeight = size * JAW_HEIGHT_RATIO
        ctx.beginPath()
        ctx.rect(cx - jawWidth / 2.0, craniumCy + craniumRadius * JAW_TOP_RATIO, jawWidth, jawHeight)
        ctx.fill()
        ctx.stroke()

        val eyeRadius = craniumRadius * EYE_RADIUS_RATIO
        val eyeOffset = craniumRadius * EYE_OFFSET_RATIO
        val eyeCy = craniumCy - craniumRadius * EYE_RISE_RATIO
        ctx.fillStyle = SKULL_STROKE
        for (side in listOf(-1.0, 1.0)) {
            ctx.beginPath()
            ctx.arc(cx + side * eyeOffset, eyeCy, eyeRadius, 0.0, 2.0 * PI)
            ctx.fill()
        }
    }
}
