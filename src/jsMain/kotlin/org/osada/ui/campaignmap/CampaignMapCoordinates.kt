package org.osada.ui.campaignmap

/**
 * Lane positions within each rank. Each sweep moves every vertex toward the weighted mean of its
 * neighbours and then restores the minimum spacing inside the rank with the closest arrangement that
 * keeps the order (an isotonic fit), so lines straighten without operations ever overlapping.
 * Virtual-to-virtual steps weigh most: a long edge should run straight, not wander.
 */
internal object CampaignMapCoordinates {
    private const val SWEEPS = 24
    private const val GAP_REAL = 1.0
    private const val GAP_MIXED = 0.7
    private const val GAP_VIRTUAL = 0.42
    private const val WEIGHT_REAL = 1.0
    private const val WEIGHT_MIXED = 2.0
    private const val WEIGHT_VIRTUAL = 8.0

    fun assign(
        layered: LayeredGraph,
        layers: List<List<Int>>,
    ): Map<Int, Double> {
        val lane = mutableMapOf<Int, Double>()
        layers.forEach { layer -> place(layered, layer, layer.map { 0.0 }).forEach { (v, y) -> lane[v] = y } }
        repeat(SWEEPS) { sweep ->
            val downward = sweep % 2 == 0
            // One-sided: a vertex follows only the rank the sweep comes from, so a chain of single
            // links lines up exactly instead of settling into a shallow curve.
            val neighbours = neighbourWeights(layered, downward)
            val ranks = if (downward) layers.indices else layers.indices.reversed()
            for (rank in ranks) {
                val layer = layers[rank]
                val desired =
                    layer.map { vertex ->
                        val around = neighbours[vertex].orEmpty()
                        if (around.isEmpty()) {
                            lane.getValue(vertex)
                        } else {
                            around.sumOf { (other, weight) -> lane.getValue(other) * weight } /
                                around.sumOf { it.second }
                        }
                    }
                place(layered, layer, desired).forEach { (v, y) -> lane[v] = y }
            }
        }
        val top = lane.values.minOrNull() ?: 0.0
        return lane.mapValues { it.value - top }
    }

    /** Each vertex's neighbours in the previous rank ([fromAbove]) or the next one, with link weights. */
    private fun neighbourWeights(
        layered: LayeredGraph,
        fromAbove: Boolean,
    ): Map<Int, List<Pair<Int, Double>>> {
        val result = mutableMapOf<Int, MutableList<Pair<Int, Double>>>()
        layered.segments.forEach { segment ->
            val virtualEnds = listOf(segment.upper, segment.lower).count(layered::isVirtual)
            val weight =
                when (virtualEnds) {
                    0 -> WEIGHT_REAL
                    1 -> WEIGHT_MIXED
                    else -> WEIGHT_VIRTUAL
                }
            if (fromAbove) {
                result.getOrPut(segment.lower) { mutableListOf() } += segment.upper to weight
            } else {
                result.getOrPut(segment.upper) { mutableListOf() } += segment.lower to weight
            }
        }
        return result
    }

    private fun gap(
        layered: LayeredGraph,
        a: Int,
        b: Int,
    ): Double =
        when (listOf(a, b).count(layered::isVirtual)) {
            0 -> GAP_REAL
            1 -> GAP_MIXED
            else -> GAP_VIRTUAL
        }

    /**
     * The order-preserving positions closest (least squares) to [desired] with at least [gap]
     * between neighbours: subtract the cumulative gaps, fit a non-decreasing sequence by pooling
     * adjacent violators, add the gaps back.
     */
    fun place(
        layered: LayeredGraph,
        layer: List<Int>,
        desired: List<Double>,
    ): List<Pair<Int, Double>> {
        val offsets = DoubleArray(layer.size)
        for (i in 1 until layer.size) offsets[i] = offsets[i - 1] + gap(layered, layer[i - 1], layer[i])
        val fitted = poolAdjacentViolators(desired.mapIndexed { i, d -> d - offsets[i] })
        return layer.mapIndexed { i, vertex -> vertex to fitted[i] + offsets[i] }
    }

    fun poolAdjacentViolators(values: List<Double>): List<Double> {
        val means = mutableListOf<Double>()
        val sizes = mutableListOf<Int>()
        values.forEach { value ->
            means += value
            sizes += 1
            while (means.size > 1 && means[means.size - 2] > means.last()) {
                val size = sizes.removeLast()
                val mean = means.removeLast()
                val total = means.last() * sizes.last() + mean * size
                sizes[sizes.size - 1] += size
                means[means.size - 1] = total / sizes.last()
            }
        }
        return means.zip(sizes).flatMap { (mean, size) -> List(size) { mean } }
    }
}
