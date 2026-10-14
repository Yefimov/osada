package org.osada.ui

import org.osada.GameHolder
import org.osada.GroundCondition
import org.osada.model.Hex

/**
 * Wreckage over the painted map: what a barrage or a demolition charge destroyed.
 *
 * The map is a picture the scenario author painted once, so a city, airfield or port reduced to
 * rubble is still DRAWN there -- and until 2026-10-14 nothing else marked it. The hex had become
 * clear ground in the rules (no landing, no deployment, and a movement surcharge when a barrage did
 * it) while looking exactly like the working facility beside it.
 *
 * The sprite is **Open General's own rubble terrain**: slot 17 of SON's tileset, which SON's
 * `TerrainEx.txt` names `rubble` (`scripts/extract_og_rubble.py`). It covers the whole hex, which is
 * the point -- the painted facility under it must stop looking usable. One row per
 * [GroundCondition], as OG's tile bands and [BuiltFacilityRenderer] have it.
 *
 * Drawn where [Hex.razedTerrain] records a destroyed feature, or [Hex.rubble] / [Hex.blownRail]
 * record wreckage. A blown bridge with no other damage is left to [DestroyedBridgeRenderer]. Repair
 * clears the records and the sprite goes with them; everything is read from saved hex state, so a
 * reload draws exactly what is there. Terrain, so drawn under the units, in both zoom modes and on
 * every side's turn, and dimmed under fog the way craters are.
 */
internal class RuinsRenderer(
    private val rc: RenderContext,
) {
    fun draw(
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        if (!hasWreckage(hex)) return
        val img = rc.ruinsImage
        val ready = img != null && img != undefined && img.complete == true && (img.naturalWidth as? Int ?: 0) > 0
        if (!ready) return
        val ground = GameHolder.instance?.scenario?.ground ?: GroundCondition.DRY.value
        val row = ground.coerceIn(GroundCondition.DRY.value, GroundCondition.MUD.value)
        val ctx = rc.hexesCtx
        val fogged = !hex.isSpotted(GameHolder.instance?.spotSide ?: 0)
        if (fogged) ctx.globalAlpha = FOGGED_ALPHA
        // `x` is the left end of the hex's top edge; the hex box starts one slant further left.
        ctx.drawImage(
            img,
            0.0,
            row * HEX_HEIGHT,
            HEX_WIDTH,
            HEX_HEIGHT,
            x - rc.hexSlantWidth,
            y,
            HEX_WIDTH,
            HEX_HEIGHT,
        )
        if (fogged) ctx.globalAlpha = 1.0
    }

    internal companion object {
        /** 1 - the fog veil's 0.38, as for craters (`HexCellRenderer`). */
        private const val FOGGED_ALPHA = 0.62
        private const val HEX_WIDTH = 60.0
        private const val HEX_HEIGHT = 50.0

        /** Whether [hex] shows wreckage. Pure, so the choice is unit-tested. */
        fun hasWreckage(hex: Hex): Boolean =
            when {
                hex.razedTerrain >= 0 -> true
                // A blown bridge has its own sprite; a river hex has no ground to lie in ruins.
                DestroyedBridgeAppearance.isVisible(hex) -> false
                else -> hex.rubble || hex.blownRail != 0
            }
    }
}
