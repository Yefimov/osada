package org.osada.ui

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.canDeployOnTerrain
import org.osada.model.getActiveLayerTarget
import org.osada.model.getPlayer
import org.osada.model.isInDeployZone

/**
 * Per-cell drawing for [MapRenderer]: grid outline, current-unit highlight, deploy highlight,
 * strategic-zoom flags, and (in normal zoom) victory/flag overlays, unit sprites, move/attack
 * overlays and the touch attack preview. Split out purely to keep [MapRenderer] within the
 * project's function-count/complexity limits.
 */
internal class HexCellRenderer(
    private val rc: RenderContext,
    private val unitRenderer: UnitRenderer,
    private val overlayRenderer: OverlayRenderer,
    private val cursorRenderer: CursorRenderer,
) {
    companion object {
        private const val STRATEGIC_ZOOM_UNIT_FLAG_SCALE = 1.4
        private const val MOVED_UNIT_FLAG_ALPHA = 0.6
        private const val STRATEGIC_ZOOM_VICTORY_FLAG_SCALE = 3.0
    }

    fun drawCell(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
        isCurrentHex: Boolean,
    ) {
        // Hex grid outlines drawn on the map layer
        if (frame.hexGrid) {
            rc.drawHex(rc.mapCtx, x, y, hexStyles["generic"], hex.terrain)
        }
        if (isCurrentHex) {
            rc.drawHex(rc.hexesCtx, x, y, hexStyles["currentstroke"])
            rc.drawHex(rc.hexesCtx, x, y, hexStyles["current"])
        }
        drawDeployHighlight(frame, hex, x, y)
        overlayRenderer.drawTerrainFacility(rc.hexesCtx, hex, x, y)
        if (frame.strategicZoom) {
            drawStrategicZoomFlags(frame, hex, x, y)
        } else {
            // Normal zoom (fog veil is applied as a pre-pass before the cell loop)
            drawNormalZoomCell(frame, hex, x, y)
        }
    }

    /**
     * Deploy hexes (own deploy zone, plus airfields when an aircraft is selected).
     * Occupancy gate matches UnitDeployOperations.deployPlayerUnit's own rejection rule
     * (ground unit needs hex.unit==null, air unit needs hex.airunit==null): without
     * it, a hex stayed highlighted forever after another unit moved onto it (reserve
     * placed there, or an already-deployed unit relocating into the zone), even
     * though it's no longer a legal deploy target.
     *
     * The terrain gate ([canDeployOnTerrain], the same call `deployPlayerUnit` makes) is applied
     * only once a reserve unit is actually picked: with nothing selected the whole zone is drawn,
     * because some unit in the reserve can go there. In `Falciu 1` the author marks 3 town, 1
     * mountain, 1 clear and 2 river deploy hexes; with the Shtorm TB picked, OG lights only the
     * two river hexes (24,17)/(24,18), while OSADA lit all seven and then refused five of them
     * silently on click.
     */
    private fun drawDeployHighlight(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        val deployOccupied = if (frame.airDeploySelected) hex.airunit != null else hex.unit != null
        // Airfield clause mirrors MapInputController's click gate: only a FRIENDLY airfield
        // (hex.owner is a player id, -1 = unowned — getPlayer(-1) falls back to player 0, so
        // -1 must be excluded explicitly) is a legal out-of-zone deploy target; previously
        // this highlighted (and click-to-deploy accepted) any airfield on the map, enemy's
        // included.
        val friendlyAirfield =
            frame.airDeploySelected &&
                hex.terrain == TerrainType.AIRFIELD.value &&
                hex.owner != -1 &&
                frame.q.getPlayer(hex.owner).side == frame.q.currentPlayer?.side
        val ownSide = frame.q.currentPlayer?.side
        val pos = hex.getPos()
        val isOwnDeployZone = ownSide != null && frame.q.isInDeployZone(ownSide, pos.row, pos.col)
        val terrainAllowsUnit =
            frame.deployUnit?.let { canDeployOnTerrain(it, hex, frame.airDeploySelected) } ?: true
        val showDeployHighlight =
            frame.deployMode && !deployOccupied && terrainAllowsUnit && (isOwnDeployZone || friendlyAirfield)
        if (showDeployHighlight) {
            rc.drawHex(rc.hexesCtx, x, y, hexStyles["deploy"])
        }
    }

    private fun drawStrategicZoomFlags(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        val fx = x + (rc.hexTopWidth - rc.hexSlantWidth) / 2.0 - rc.flagIconWidth / 2.0
        val fy = y + rc.v - rc.flagIconHeight - 2.0
        var flag = -1
        var scale = 0.0

        if (hex.isSpotted(frame.q.currentPlayer?.side ?: 0)) {
            val u = if (frame.airMode) hex.airunit else hex.unit
            if (u != null) {
                flag = strategicUnitFlag(u)
                scale = STRATEGIC_ZOOM_UNIT_FLAG_SCALE
                if (u.hasMoved) rc.hexesCtx.globalAlpha = MOVED_UNIT_FLAG_ALPHA
            }
        }
        if (hex.flag != -1 && hex.victorySide != -1) {
            flag = hex.flag
            scale = STRATEGIC_ZOOM_VICTORY_FLAG_SCALE
        }
        if (flag != -1) {
            rc.hexesCtx.drawImage(
                rc.flagImage,
                rc.flagIconWidth * flag,
                0.0,
                rc.flagIconWidth,
                rc.flagIconHeight,
                fx,
                fy,
                scale * rc.hexTopWidth,
                scale * rc.hexTopWidth / (rc.flagIconWidth / rc.flagIconHeight),
            )
            rc.hexesCtx.globalAlpha = 1.0
        }
    }

    private fun drawNormalZoomCell(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        if (frame.showHiddenVictoryHexes) {
            overlayRenderer.drawVictoryHexes(rc.hexesCtx, hex, x, y, frame.q)
        } else {
            overlayRenderer.drawFlags(rc.hexesCtx, hex, x, y)
        }
        drawCellUnits(frame, hex, x, y)
        drawMoveAttackOverlays(frame, hex, x, y)
        drawTouchAttackPreview(frame, hex, x, y)
    }

    private fun isUnitVisible(
        hex: Hex,
        unit: GameUnit,
    ): Boolean =
        !unit.hasAnimation &&
            (
                hex.isSpotted(GameHolder.instance?.spotSide ?: 0) ||
                    unit.tempSpotted ||
                    unit.player?.side == GameHolder.instance?.spotSide
            )

    private fun drawCellUnits(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        // Primary unit (!airMode) – sprite only
        val primary = hex.getUnit(!frame.airMode)
        if (primary != null && isUnitVisible(hex, primary)) {
            if (frame.markOwnUnits && primary.player?.id == frame.q.currentPlayer?.id) {
                rc.drawHex(rc.hexesCtx, x, y, hexStyles["ownunit"])
            }
            unitRenderer.drawUnit(rc.hexesCtx, x, y, primary, false)
        }

        // Secondary unit (airMode) – sprite + stats
        val secondary = hex.getUnit(frame.airMode)
        if (secondary != null && isUnitVisible(hex, secondary)) {
            if (frame.airMode && hex.airunit != null) {
                rc.drawHex(rc.hexesCtx, x, y, hexStyles["airunit"])
            }
            unitRenderer.drawUnit(rc.hexesCtx, x, y, secondary, true)
        }
    }

    // Move / attack overlays — для spotSide; видны и в deploy-режиме
    private fun drawMoveAttackOverlays(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        if (frame.q.currentPlayer?.side != GameHolder.instance?.spotSide) return
        if (hex.isMoveSel) {
            if (hex.isSpotted(frame.q.currentPlayer?.side ?: 0)) {
                rc.drawHex(rc.hexesCtx, x, y, hexStyles["movespotted"])
            } else {
                rc.drawHex(rc.hexesCtx, x, y, hexStyles["move"])
            }
        }
        if (hex.isAttackSel) {
            rc.drawHex(rc.hexesCtx, x, y, hexStyles["attack"])
        }
        // Outline-only so it composites over the move fill just drawn -- covers only spotted AA,
        // by construction of AAInterception.visibleThreatHexes (DEFERRED.md §1.1).
        if (hex.isAaThreat) {
            rc.drawHex(rc.hexesCtx, x, y, hexStyles["aathreat"])
        }
    }

    private fun drawTouchAttackPreview(
        frame: RenderFrame,
        hex: Hex,
        x: Double,
        y: Double,
    ) {
        val currentUnit = frame.q.currentUnit
        if (!frame.hasTouch || !hex.isAttackSel || currentUnit == null) return
        val target = hex.getActiveLayerTarget(currentUnit, frame.airMode) ?: return
        val cursor = cursorRenderer.generateAttackCursor(currentUnit, target)
        rc.hexesCtx.drawImage(cursor, x - rc.hexTopWidth / 2.0, y)
    }
}

/**
 * The `flags_med.png` COLUMN for [unit]'s flag, for the strategic-zoom overlay.
 *
 * Scenario-authored units may deliberately fly a flag other than their owner's (partisans, foreign
 * volunteers, captured equipment), so the unit's own stable `flag` is the source — never
 * `Player.country` re-derived from the owner.
 *
 * `GameUnit.flag` is ONE-BASED, though: the scenario XML writes `country + 1` (Spartacus' Roman
 * player is country 307 and its units carry `flag="308"`), which is also why the big-flag asset is
 * `flag_big_${unit.flag}.png` and why `CursorRenderer`/`EquipmentWindowBuilder` both subtract one
 * before indexing the sheet. `Hex.flag`, by contrast, is the zero-based country id — the same
 * numbering `Player.country` uses, which is what lets `OverlayRenderer.drawVictoryHexes` fall back
 * to `getPlayer(hex.owner).country` for a hidden objective. Returning `unit.flag` raw mixed the two
 * conventions in one function and drew every strategic-zoom unit flag one country to the right.
 */
internal fun strategicUnitFlag(unit: GameUnit): Int = (unit.flag - 1).coerceAtLeast(0)
