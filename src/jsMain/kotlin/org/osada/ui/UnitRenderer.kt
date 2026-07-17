package org.osada.ui

import org.osada.GameHolder
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.uiSettings

/**
 * Draws unit sprites and their stat overlays (strength box, ammo/fire indicator,
 * leader badge) onto a canvas context. Extracted from the former `Render` god-class;
 * reads its geometry, cached images and current map through the shared [RenderContext].
 */
internal class UnitRenderer(private val rc: RenderContext) {

    fun drawUnit(ctx: dynamic, x: Double, y: Double, unit: GameUnit, showStats: Boolean) {
        if (!drawUnitSprite(ctx, x, y, unit)) return
        if (!showStats || unit.strength < 1) return

        val side = unit.player?.side ?: 0
        val isCore = unit.isCore
        val isCurrentPlayer = unit.player?.id == rc.map?.currentPlayer?.id
        val hasMoved = unit.hasMoved || rc.map?.let { TurnSleep.isAsleep(it, unit) } == true

        val fontSize = rc.R.toInt()
        ctx.font = "${fontSize}px ${if (isCore) "coreUnitFont" else "unitInfo"}, sans-serif"

        val boxX = x + rc.Y / 2.0
        val boxY = y + 2.0 * rc.v - (rc.R + 4.0)
        val boxW = if (unit.strength < 10) 12.0 else 19.0
        val boxH = rc.R + 4.0
        val textOff = if (unit.strength < 10) 2.0 else 1.0

        ctx.fillStyle =
            if (side ==
                1
            ) {
                unitStyles["alliedBox"] as? String ?: "#808000"
            } else {
                unitStyles["axisBox"] as? String ?: "#383838"
            }
        if (uiSettings.markEnemyUnits == true && side != GameHolder.instance?.spotSide) {
            ctx.fillStyle = unitStyles["enemyBoxMarked"] as? String ?: "#FF0000"
        }

        if (isCore) {
            ctx.strokeStyle =
                if (side ==
                    1
                ) {
                    unitStyles["alliedBorder"] as? String ?: "rgba(127,255,0,1)"
                } else {
                    unitStyles["axisBorder"] as? String
                        ?: "rgba(211,211,211,1)"
                }
            ctx.lineWidth = 2.0
            ctx.strokeRect(boxX, boxY - 1.0, boxW, boxH)
        }
        ctx.fillRect(boxX, boxY - 1.0, boxW, boxH)

        ctx.fillStyle =
            if (unit.player?.id != rc.map?.currentPlayer?.id && unit.player?.side == rc.map?.currentPlayer?.side) {
                unitStyles["alliedPlayerText"] as? String ?: "#696969"
            } else {
                unitStyles["playerText"] as? String ?: "white"
            }
        if (hasMoved && isCurrentPlayer) {
            ctx.fillStyle = unitStyles["movedUnitText"] as? String ?: "#BDBDBD"
        }
        ctx.fillText("${unit.strength}", boxX + textOff, boxY + 9.0)

        if (!unit.hasFired && GameRules.unitUsesAmmo(unit) && side == rc.map?.currentPlayer?.side) {
            if (unit.getAmmo() > 0) {
                ctx.drawImage(rc.fireImage, x - 4.0, y + 2.0 * rc.v - 12.0)
            } else {
                ctx.drawImage(rc.noAmmoImage, x - 4.0, y + 2.0 * rc.v - 12.0)
            }
        }

        if (unit.leader != -1) {
            val img = if (side == 1) rc.leaderAlliedImage else rc.leaderAxisImage
            ctx.drawImage(img, boxX + boxW + 1.0, y + 2.0 * rc.v - 13.0)
        }
    }

    private fun drawUnitSprite(ctx: dynamic, x: Double, y: Double, unit: GameUnit): Boolean {
        val isBridge = !unit.hasAnimation &&
            GameRules.isGround(unit) &&
            GameRules.isBridgeForSide(unit.getHex(), unit.player?.side ?: -1)
        val icon = unit.getIcon()
        val img = if (isBridge) rc.bridgeImage else rc.unitImages[icon]
        if (img == null || img == undefined) {
            console.log(
                "[osada] drawUnitSprite missing image for icon",
                icon,
                "unit eqid",
                unit.eqid,
                "isBridge",
                isBridge,
            )
            return false
        }

        val frameW = (img.width as? Number)?.toDouble()?.div(9.0) ?: 0.0
        val imgH = (img.height as? Number)?.toDouble() ?: 0.0
        val drawX = x - frameW / 2.0 + rc.S / 2.0
        val drawY = y - imgH / 2.0 + rc.v - rc.R

        var facing = unit.facing
        var mirror = false
        if (facing > 8) {
            facing = 16 - facing
            mirror = true
        }
        val srcX = facing * frameW

        if (mirror) {
            val cx = drawX + frameW / 2.0
            ctx.save()
            ctx.translate(cx, 0.0)
            ctx.scale(-1.0, 1.0)
            ctx.translate(-cx, 0.0)
        }

        ctx.drawImage(img, srcX, 0.0, frameW, imgH, drawX, drawY, frameW, imgH)

        if (mirror) ctx.restore()
        return true
    }
}
