package org.osada.ui

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.Hex
import org.osada.rules.HexGeometry
import org.osada.rules.PontoonBridges
import kotlin.math.atan2

/** Persistent damage over the painted map. Both demolition and barrage record [Hex.blownRoad];
 * repair and rebuilding clear it, including after a save round-trip. No crater rule is involved.
 * The sprite is 88x60 (2x of the draw box) and its water patch must cover the painted span. */
internal class DestroyedBridgeRenderer(
    private val rc: RenderContext,
) {
    fun draw(
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        val map = rc.map ?: return
        val img = rc.destroyedBridgeImage
        val ready = img != null && img.complete == true && (img.naturalWidth as? Int ?: 0) > 0
        if (!ready || !DestroyedBridgeAppearance.isVisible(hex)) return

        val pos = hex.getPos()
        val end = HexGeometry.getAdjacent(pos.row, pos.col)[DestroyedBridgeAppearance.axis(map, hex)]
        val origin = rc.cellToScreen(pos.row, pos.col, false)
        val target = rc.cellToScreen(end.row, end.col, false)
        val ctx = rc.hexesCtx
        ctx.save()
        ctx.translate(x + rc.hexTopWidth / 2.0, y + rc.v)
        ctx.rotate(atan2(target.y - origin.y, target.x - origin.x))
        ctx.drawImage(img, -SPRITE_WIDTH / 2.0, -SPRITE_HEIGHT / 2.0, SPRITE_WIDTH, SPRITE_HEIGHT)
        ctx.restore()
    }

    private companion object {
        const val SPRITE_WIDTH = 44.0
        const val SPRITE_HEIGHT = 30.0
    }
}

internal object DestroyedBridgeAppearance {
    // Same order as getAdjacent's first three neighbours: N, NW, SW (opposites are +3).
    private val roadEnds =
        listOf(
            listOf(RoadType.NORTH, RoadType.SOUTH),
            listOf(RoadType.NORTHWEST, RoadType.SOUTHEAST),
            listOf(RoadType.SOUTHWEST, RoadType.NORTHEAST),
        )
    private const val OPPOSITE = 3

    /** Terrain, like craters, so fog does not hide it: otherwise the player's own demolition would
     *  show the painted intact span again once the sappers moved off. A restored crossing never
     *  carries debris. */
    fun isVisible(hex: Hex): Boolean =
        hex.blownRoad != RoadType.NONE.value &&
            hex.road == RoadType.NONE.value &&
            PontoonBridges.isRiver(hex)

    /**
     * Use the recorded road directions first; on a junction or a sapper's all-directions mask,
     * prefer the axis joining dry banks. River connection masks exist in OG's binary but not in
     * Hex, so adjacent terrain is only a fallback, never a claim to match the painted river exactly.
     * A tie with no geographic evidence has a stable N-S fallback, including at map edges.
     */
    fun axis(
        map: GameMap,
        hex: Hex,
    ): Int {
        val pos = hex.getPos()
        val neighbours = HexGeometry.getAdjacent(pos.row, pos.col)
        val banks = neighbours.map { cell -> isBank(map.map?.getOrNull(cell.row)?.getOrNull(cell.col)) }
        return roadEnds.indices.maxWith(
            compareBy<Int> { axis -> roadEnds[axis].count { hex.blownRoad and it.value != 0 } }
                .thenBy { axis -> listOf(axis, axis + OPPOSITE).count { banks[it] } }
                .thenBy { axis -> -axis },
        )
    }

    private fun isBank(hex: Hex?): Boolean =
        hex != null && !PontoonBridges.isRiver(hex) && hex.terrain != TerrainType.OCEAN.value
}
