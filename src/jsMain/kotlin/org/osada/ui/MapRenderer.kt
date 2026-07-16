package org.osada.ui

import org.osada.*
import org.osada.model.*
import org.osada.rules.GameRules

/**
 * Top-level map painter: clears and redraws hexes, grid, deploy/move/attack overlays,
 * flags and units for a region of the map. Extracted from the former `Render` god-class,
 * this is the coordinator of a single frame — it delegates unit sprites to [UnitRenderer],
 * flags/victory markers to [OverlayRenderer] and the touch attack preview to
 * [CursorRenderer], drawing everything through the shared [RenderContext].
 */
internal class MapRenderer(
    private val rc: RenderContext,
    private val unitRenderer: UnitRenderer,
    private val overlayRenderer: OverlayRenderer,
    private val cursorRenderer: CursorRenderer
) {

    /** Полный redraw всей карты (используется при старте, ресайзе и т.д.) */
    fun render() {
        rc.map ?: return
        // radius = -3 заставляет getBounds(radius+1/radius+2) вернуть всю карту
        render(0, 0, -3)
    }

    fun render(centerRow: Int, centerCol: Int, radius: Int) {
        val q = rc.map ?: return
        val rows = q.rows
        val cols = q.cols
        val gameMap = q.map ?: return

        val airMode = uiSettings.airMode as? Boolean ?: false
        val hexGrid = uiSettings.hexGrid as? Boolean ?: false
        val deployMode = uiSettings.deployMode as? Boolean ?: false
        val strategicZoom = uiSettings.strategicZoom as? Boolean ?: false
        val showHiddenVictoryHexes = uiSettings.showHiddenVictoryHexes as? Boolean ?: false
        val markOwnUnits = uiSettings.markOwnUnits as? Boolean ?: false
        val hasTouch = uiSettings.hasTouch as? Boolean ?: false

        val clearBounds = rc.getBounds(centerRow, centerCol, radius + 1, rows, cols)
        val drawBounds = rc.getBounds(centerRow, centerCol, radius + 2, rows, cols)

        if (hexGrid != rc.hexGridEnabled) {
            rc.hexGridEnabled = hexGrid
            rc.mapCtx.clearRect(0.0, 0.0, rc.mapWidth, rc.mapHeight + 65.0)
        }

        val currentPos = q.currentUnit?.getPos()

        // When deploying an AIRCRAFT, airfields are valid deploy targets even outside the deploy
        // zone (OG rule, already honoured on click in MapInputController). Highlight them too — but
        // only for aircraft, so ground units don't see airfields lit up. Mirrors selectedDeployUnitIsAir().
        val airDeploySelected = deployMode && run {
            val index = byId("eqUserSel")?.asDynamic()?.deployunit as? Int ?: -1
            val unit = if (index >= 0) q.currentPlayer?.getCoreUnitList()?.getOrNull(index) else null
            unit != null && GameRules.isAir(unit)
        }

        val c1 = rc.cellToScreen(clearBounds.srow, clearBounds.scol, false)
        val c2 = rc.cellToScreen(clearBounds.erow, clearBounds.ecol, false)
        rc.hexesCtx.clearRect(c1.x, c1.y, c2.x - c1.x, c2.y - c1.y)

        /* Fog of war: fill the (re)drawn area with a translucent veil, then ERASE the hexes the
           current side can see so the terrain shows through. Filling-then-erasing (instead of
           shading each unseen hex) means the veil never double-darkens where partial redraws
           overlap (no seam frame around a moved unit) and it also covers the map margins where a
           library map image overruns the logical grid by a partial row/column. On a full render
           (radius < 0) fog the whole canvas to catch those margins; on a partial render fog only
           the cleared box so the translucent layer can't stack outside it. isSpotted() already
           returns true when the noFOW setting is on, so this whole block produces nothing then. */
        run {
            val ctx = rc.hexesCtx
            val spotSide = GameHolder.instance?.spotSide ?: 0
            ctx.save()
            ctx.fillStyle = "rgba(20,24,28,0.38)"
            if (radius < 0) ctx.fillRect(0.0, 0.0, rc.mapWidth, rc.mapHeight + 65.0)
            else ctx.fillRect(c1.x, c1.y, c2.x - c1.x, c2.y - c1.y)
            // Erase the veil over SEEN hexes. The erase must be FULLY opaque: destination-out removes
            // dst alpha in proportion to src alpha, so a translucent fill here would only half-erase
            // and leave seen terrain darkened. Use solid black (only alpha matters for destination-out).
            ctx.globalCompositeOperation = "destination-out"
            ctx.fillStyle = "rgba(0,0,0,1)"
            // Also STROKE each erased hex (opaque, ~2px): adjacent hexes in offset columns share
            // slanted edges where fill()-only leaves a hairline veil seam (the thin grey line between
            // visible hexes). Stroking clears ~1px on each side of the edge so neighbours meet cleanly.
            ctx.strokeStyle = "rgba(0,0,0,1)"
            ctx.lineWidth = 2.0
            for (r in drawBounds.srow until drawBounds.erow) {
                for (c in drawBounds.scol until drawBounds.ecol) {
                    if (r < 0 || c < 0 || r >= rows || c >= cols) continue
                    if (!gameMap[r][c].isSpotted(spotSide)) continue
                    val fy = if (c % 2 == 1) 2.0 * r * rc.v + rc.v + rc.ca else 2.0 * r * rc.v + rc.ca
                    val fx = c * (rc.S + rc.Y) + rc.Y + rc.ba
                    ctx.beginPath()
                    ctx.moveTo(fx, fy)
                    ctx.lineTo(fx + rc.S, fy)
                    ctx.lineTo(fx + rc.S + rc.Y, fy + rc.v)
                    ctx.lineTo(fx + rc.S, fy + 2 * rc.v)
                    ctx.lineTo(fx, fy + 2 * rc.v)
                    ctx.lineTo(fx - rc.Y, fy + rc.v)
                    ctx.closePath()
                    ctx.fill()
                    ctx.stroke()
                }
            }
            ctx.restore()
        }

        for (r in drawBounds.srow until drawBounds.erow) {
            for (c in drawBounds.scol until drawBounds.ecol) {
                if (r < 0 || c < 0 || r >= rows || c >= cols) continue
                val hex = gameMap[r][c]
                val y = if (c % 2 == 1) 2.0 * r * rc.v + rc.v + rc.ca else 2.0 * r * rc.v + rc.ca
                val x = c * (rc.S + rc.Y) + rc.Y + rc.ba

                /* Hex grid outlines drawn on the map layer */
                if (hexGrid) {
                    rc.drawHex(rc.mapCtx, x, y, hexStyles["generic"], hex.terrain)
                }

                /* Current unit highlight */
                if (currentPos != null && r == currentPos.row && c == currentPos.col) {
                    rc.drawHex(rc.hexesCtx, x, y, hexStyles["currentstroke"])
                    rc.drawHex(rc.hexesCtx, x, y, hexStyles["current"])
                }

                /* Deploy hexes (own deploy zone, plus airfields when an aircraft is selected).
                   Occupancy gate matches UnitOperations.deployPlayerUnit's own rejection rule
                   (ground unit needs hex.unit==null, air unit needs hex.airunit==null): without
                   it, a hex stayed highlighted forever after another unit moved onto it (reserve
                   placed there, or an already-deployed unit relocating into the zone), even
                   though it's no longer a legal deploy target. */
                val deployOccupied = if (airDeploySelected) hex.airunit != null else hex.unit != null
                // Airfield clause mirrors MapInputController's click gate: only a FRIENDLY airfield
                // (hex.owner is a player id, -1 = unowned — getPlayer(-1) falls back to player 0, so
                // -1 must be excluded explicitly) is a legal out-of-zone deploy target; previously
                // this highlighted (and click-to-deploy accepted) any airfield on the map, enemy's
                // included.
                val friendlyAirfield = airDeploySelected && hex.terrain == TerrainType.AIRFIELD.value
                    && hex.owner != -1 && q.getPlayer(hex.owner).side == q.currentPlayer?.side
                if (deployMode && !deployOccupied && (
                        (hex.isDeployment != -1 && q.getPlayer(hex.isDeployment).side == q.currentPlayer?.side)
                        || friendlyAirfield
                    )
                ) {
                    rc.drawHex(rc.hexesCtx, x, y, hexStyles["deploy"])
                }

                if (strategicZoom) {
                    /* Strategic zoom – flags only */
                    val fx = x + (rc.S - rc.Y) / 2.0 - rc.A / 2.0
                    val fy = y + rc.v - rc.O - 2.0
                    var flag = -1
                    var scale = 0.0
                    var u: GameUnit? = null

                    if (hex.isSpotted(q.currentPlayer?.side ?: 0)) {
                        u = if (airMode) hex.airunit else hex.unit
                        if (u != null) {
                            flag = u.player?.country ?: -1
                            scale = 1.4
                            if (u.hasMoved) rc.hexesCtx.globalAlpha = 0.6
                        }
                    }
                    if (hex.flag != -1 && hex.victorySide != -1) {
                        flag = hex.flag
                        scale = 3.0
                    }
                    if (flag != -1) {
                        rc.hexesCtx.drawImage(
                            rc.flagImage,
                            rc.A * flag, 0.0, rc.A, rc.O,
                            fx, fy,
                            scale * rc.S,
                            scale * rc.S / (rc.A / rc.O)
                        )
                        rc.hexesCtx.globalAlpha = 1.0
                    }
                } else {
                    /* Normal zoom (fog veil is applied as a pre-pass before this loop) */
                    if (showHiddenVictoryHexes) {
                        overlayRenderer.drawVictoryHexes(rc.hexesCtx, hex, x, y, q)
                    } else {
                        overlayRenderer.drawFlags(rc.hexesCtx, hex, x, y, q)
                    }

                    /* Primary unit (!airMode) – sprite only */
                    val primary = hex.getUnit(!airMode)
                    if (primary != null && !primary.hasAnimation
                        && (hex.isSpotted(GameHolder.instance?.spotSide ?: 0)
                                || primary.tempSpotted
                                || primary.player?.side == GameHolder.instance?.spotSide)
                    ) {
                        if (markOwnUnits && primary.player?.id == q.currentPlayer?.id) {
                            rc.drawHex(rc.hexesCtx, x, y, hexStyles["ownunit"])
                        }
                        unitRenderer.drawUnit(rc.hexesCtx, x, y, primary, false)
                    }

                    /* Secondary unit (airMode) – sprite + stats */
                    val secondary = hex.getUnit(airMode)
                    if (secondary != null && !secondary.hasAnimation
                        && (hex.isSpotted(GameHolder.instance?.spotSide ?: 0)
                                || secondary.tempSpotted
                                || secondary.player?.side == GameHolder.instance?.spotSide)
                    ) {
                        if (airMode && hex.airunit != null) {
                            rc.drawHex(rc.hexesCtx, x, y, hexStyles["airunit"])
                        }
                        unitRenderer.drawUnit(rc.hexesCtx, x, y, secondary, true)
                    }

                    /* Move / attack overlays — для spotSide; видны и в deploy-режиме */
                    if (q.currentPlayer?.side == GameHolder.instance?.spotSide) {
                        if (hex.isMoveSel) {
                            if (hex.isSpotted(q.currentPlayer?.side ?: 0)) {
                                rc.drawHex(rc.hexesCtx, x, y, hexStyles["movespotted"])
                            } else {
                                rc.drawHex(rc.hexesCtx, x, y, hexStyles["move"])
                            }
                        }
                        if (hex.isAttackSel) {
                            rc.drawHex(rc.hexesCtx, x, y, hexStyles["attack"])
                        }
                    }

                    /* Touch attack preview */
                    if (hasTouch && hex.isAttackSel) {
                        val currentUnit = q.currentUnit
                        if (currentUnit != null) {
                            val target = hex.getAttackableUnit(currentUnit, airMode)
                            if (target != null) {
                                val cursor = cursorRenderer.generateAttackCursor(currentUnit, target)
                                rc.hexesCtx.drawImage(cursor, x - rc.S / 2.0, y)
                            }
                        }
                    }
                }
            }
        }
    }

    fun drawHexByCell(cell: Cell, style: dynamic) {
        val p = rc.cellToScreen(cell.row, cell.col, false)
        rc.drawHex(rc.hexesCtx, p.x, p.y, style)
    }
}
