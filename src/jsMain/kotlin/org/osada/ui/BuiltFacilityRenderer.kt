package org.osada.ui

import org.osada.GameHolder
import org.osada.GroundCondition
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.Hex
import org.osada.model.SapperWorks
import org.osada.rules.Engineering
import org.osada.rules.EngineeringWork
import org.osada.rules.PontoonBridges

/**
 * What engineers built during the battle, drawn over the painted map.
 *
 * The map image is painted once by the scenario author, so a facility raised during play has no
 * picture of its own: without this only a flag -- or, for a bridge or a station, nothing at all --
 * would mark it. The sprites are Open General's own (`OPENGUI`, all three ground variants):
 *
 *  - **airfield**: OG's own airfield mark from `hex_marks.png` (its seventh row) -- the runway with
 *    its tower. The hangars are the Airbase unit's picture (`rules/Airbase`), so the field and the
 *    servicing unit never look alike;
 *  - **airfield under construction**: the bare runway piece from `hex_tiledecors*.png`, drawn on the
 *    job's last countdown turn ([BuiltFacilityRows.RUNWAY_WORKS]), for the side that sees the site at all
 *    ([Engineering.visibleSiteFor]). The hex is still its old terrain until the work completes, so
 *    nothing can base there yet; the strip only says the field is nearly ready;
 *  - **port, fortification, bridge, station**: OG's hex marks from `hex_marks.png` -- the crane and
 *    warehouse, the bunker in its earthworks, the truss bridge and the station building.
 *
 * Sheet layout: one 62x52 cell per row and per [GroundCondition] column, a hex box plus OG's
 * one-pixel margin. Each facility is drawn only while the hex still has it, so a blown bridge or a
 * razed fort shows its wreckage instead ([DestroyedBridgeRenderer], [RuinsRenderer]), and a
 * repaired one gets its picture back. Everything here
 * is derived from the hex's saved state (terrain, construction job and countdown, `sapperBuilt`,
 * `sapperWorks`), so a reload, a paused job or one abandoned half-way draws exactly what is there.
 * Terrain, so drawn under fog like craters and bridge debris.
 */
internal class BuiltFacilityRenderer(
    private val rc: RenderContext,
) {
    fun draw(
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        val img = rc.builtFacilitiesImage
        val ready = img != null && img.complete == true && (img.naturalWidth as? Int ?: 0) > 0
        if (!ready) return
        val ground = GameHolder.instance?.scenario?.ground ?: GroundCondition.DRY.value
        val column = ground.coerceIn(GroundCondition.DRY.value, GroundCondition.MUD.value)
        val side =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.currentPlayer
                ?.side
        BuiltFacilityRows.of(hex, side).forEach { row ->
            // `x` is the left end of the hex's top edge; the hex box starts one slant further left,
            // and the cell carries a one-pixel margin around it.
            rc.hexesCtx.drawImage(
                img,
                column * CELL_WIDTH,
                row * CELL_HEIGHT,
                CELL_WIDTH,
                CELL_HEIGHT,
                x - rc.hexSlantWidth - 1.0,
                y - 1.0,
                CELL_WIDTH,
                CELL_HEIGHT,
            )
        }
    }

    private companion object {
        const val CELL_WIDTH = 62.0
        const val CELL_HEIGHT = 52.0
    }
}

/** Which rows of the built-facilities sheet a hex shows -- pure, so the rule is testable without a canvas. */
internal object BuiltFacilityRows {
    const val RUNWAY_WORKS = 0
    const val PORT = 1
    const val FORTIFICATION = 2
    const val BRIDGE = 3
    const val STATION = 4
    const val AIRFIELD = 5

    /** The countdown value on the turn whose end completes the job (`Engineering.advanceTurn`). */
    private const val LAST_COUNTDOWN_TURN = 1

    /** The rows to draw for [hex] as seen by [viewerSide], bottom layer first. */
    fun of(
        hex: Hex,
        viewerSide: Int?,
    ): List<Int> =
        buildList {
            if (hex.sapperBuilt && hex.terrain == TerrainType.AIRFIELD.value) add(AIRFIELD)
            if (viewerSide != null && runwayNearlyReady(hex, viewerSide)) add(RUNWAY_WORKS)
            if (built(hex, SapperWorks.PORT) && hex.terrain == TerrainType.PORT.value) add(PORT)
            if (built(hex, SapperWorks.FORTIFICATION) && hex.terrain == TerrainType.FORTIFICATION.value) {
                add(FORTIFICATION)
            }
            val bridged = hex.road > RoadType.NONE.value && PontoonBridges.isRiver(hex)
            if (built(hex, SapperWorks.BRIDGE) && bridged) add(BRIDGE)
            if (built(hex, SapperWorks.STATION) && hex.station) add(STATION)
        }

    private fun built(
        hex: Hex,
        work: Int,
    ): Boolean = hex.sapperWorks and work != 0

    /** An airfield job on its last countdown turn, on a site [side] can see. */
    private fun runwayNearlyReady(
        hex: Hex,
        side: Int,
    ): Boolean =
        hex.construction == EngineeringWork.AIRFIELD.ordinal &&
            hex.constructionTurns == LAST_COUNTDOWN_TURN &&
            Engineering.visibleSiteFor(hex, side)
}
