package org.osada.ui.campaignmap

/** Time runs left to right on a wide screen and top to bottom on a phone held upright. */
internal enum class MapOrientation {
    HORIZONTAL,
    VERTICAL,
}

/** A screen point in surface pixels. */
internal data class MapPoint(
    val x: Double,
    val y: Double,
)

/** A screen rectangle in surface pixels. */
internal data class MapBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Layout space to surface pixels. Everything is computed along the time axis ("along") and across
 * it, then mapped to x/y once, so the horizontal and the vertical map are one piece of arithmetic.
 */
internal class CampaignMapGeometry(
    private val layout: CampaignMapLayout,
    val orientation: MapOrientation,
) {
    private val horizontal = orientation == MapOrientation.HORIZONTAL
    private val nodeAlong = if (horizontal) 176.0 else 64.0
    private val nodeAcross = if (horizontal) 62.0 else 170.0
    private val finaleAlong = if (horizontal) 104.0 else 60.0
    private val rankStep = if (horizontal) 252.0 else 118.0
    private val laneStep = if (horizontal) 88.0 else 190.0

    val width: Double get() = if (horizontal) alongSize else acrossSize
    val height: Double get() = if (horizontal) acrossSize else alongSize

    private val alongSize: Double get() = PAD * 2 + (layout.rankCount - 1).coerceAtLeast(0) * rankStep + nodeAlong
    private val acrossSize: Double get() = PAD * 2 + layout.laneSpan * laneStep + nodeAcross

    fun box(
        point: LayoutPoint,
        finale: Boolean,
    ): MapBox {
        val along = alongStart(point.rank)
        val across = acrossCenter(point.lane) - nodeAcross / 2
        val alongLength = if (finale) finaleAlong else nodeAlong
        return if (horizontal) {
            MapBox(along, across, alongLength, nodeAcross)
        } else {
            MapBox(across, along, nodeAcross, alongLength)
        }
    }

    /**
     * The polyline an edge follows: out of the source's trailing side, straight through every
     * rank it merely crosses, into the target's leading side.
     */
    fun routePoints(
        route: List<LayoutPoint>,
        sourceFinale: Boolean,
    ): List<MapPoint> {
        val first = route.first()
        val last = route.last()
        val sourceLength = if (sourceFinale) finaleAlong else nodeAlong
        val points = mutableListOf(point(alongStart(first.rank) + sourceLength, acrossCenter(first.lane)))
        route.drop(1).dropLast(1).forEach { virtual ->
            points += point(alongStart(virtual.rank), acrossCenter(virtual.lane))
            points += point(alongStart(virtual.rank) + nodeAlong, acrossCenter(virtual.lane))
        }
        points += point(alongStart(last.rank), acrossCenter(last.lane))
        return points
    }

    /** SVG path data: straight runs through crossed ranks, S-curves with along-axis tangents in the gaps. */
    fun path(points: List<MapPoint>): String {
        val builder = StringBuilder("M ${fmt(points.first().x)} ${fmt(points.first().y)}")
        points.zipWithNext().forEach { (a, b) ->
            val straight = if (horizontal) a.y == b.y else a.x == b.x
            if (straight) {
                builder.append(" L ${fmt(b.x)} ${fmt(b.y)}")
            } else if (horizontal) {
                val mid = (a.x + b.x) / 2
                builder.append(" C ${fmt(mid)} ${fmt(a.y)} ${fmt(mid)} ${fmt(b.y)} ${fmt(b.x)} ${fmt(b.y)}")
            } else {
                val mid = (a.y + b.y) / 2
                builder.append(" C ${fmt(a.x)} ${fmt(mid)} ${fmt(b.x)} ${fmt(mid)} ${fmt(b.x)} ${fmt(b.y)}")
            }
        }
        return builder.toString()
    }

    /**
     * Where an edge's chip sits. Across the screen: the middle of the gap it leaves its source
     * through, the exact midpoint of that S-curve. Down a phone: just above where it enters its
     * target, because sibling curves leaving one card stay too close together to label in the
     * middle, while their targets sit a whole lane apart.
     */
    fun labelPoint(points: List<MapPoint>): MapPoint {
        if (!horizontal) return points.last().let { MapPoint(it.x, it.y - TAG_GAP - TAG_HEIGHT) }
        val a = points[0]
        val b = points[1]
        return MapPoint((a.x + b.x) / 2, (a.y + b.y) / 2)
    }

    /** The top-left corner of a tag hung on [box]: under the card when time runs across, above it
     *  when it runs down (below would sit on the outgoing line). */
    fun tagPoint(box: MapBox): MapPoint =
        if (horizontal) MapPoint(box.x, box.y + box.height + TAG_GAP) else MapPoint(box.x, box.y - TAG_GAP - TAG_HEIGHT)

    private fun alongStart(rank: Int): Double = PAD + rank * rankStep

    private fun acrossCenter(lane: Double): Double = PAD + nodeAcross / 2 + lane * laneStep

    private fun point(
        along: Double,
        across: Double,
    ): MapPoint = if (horizontal) MapPoint(along, across) else MapPoint(across, along)

    private fun fmt(value: Double): String = (kotlin.math.round(value * DECIMALS) / DECIMALS).toString()

    companion object {
        const val PAD = 44.0
        private const val TAG_GAP = 4.0
        private const val TAG_HEIGHT = 18.0

        /** Path coordinates keep one decimal: sub-pixel enough, and the `d` strings stay short. */
        private const val DECIMALS = 10.0
    }
}
