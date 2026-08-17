package org.osada.ui

import org.osada.GameHolder
import org.osada.hero.HeroCampaign
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.isBridgeForSide
import org.osada.rules.isGround
import org.osada.rules.unitUsesAmmo
import org.osada.uiSettings

/**
 * Draws unit sprites and their stat overlays (strength box, ammo/fire indicator,
 * leader badge) onto a canvas context. Extracted from the former `Render` god-class;
 * reads its geometry, cached images and current map through the shared [RenderContext].
 */
internal class UnitRenderer(
    private val rc: RenderContext,
) {
    companion object {
        // Icons already reported missing, so a missing OG-import sprite is logged once rather than
        // every frame it is drawn — the per-frame spam was pinning the console and the render loop.
        private val loggedMissingIcons = mutableSetOf<String>()

        private const val STRENGTH_TEXT_Y_OFFSET = 9.0
        private const val AMMO_ICON_X_OFFSET = 4.0
        private const val AMMO_ICON_Y_OFFSET = 12.0
        private const val LEADER_ICON_Y_OFFSET = 13.0

        // Facing is a 16-direction index (0..15); indices past MAX_FACING_INDEX are the mirror
        // of (FACING_MIRROR_BASE - facing), so only 9 sprite frames are needed per unit.
        private const val MAX_FACING_INDEX = 8
        private const val FACING_MIRROR_BASE = 16

        // Recessed, not hidden: the inactive layer is still part of the battlefield the player is
        // reading. Chosen lighter than the disabled action chips' .5, which sit on a flat panel
        // rather than over painted terrain.
        private const val RECESSED_ALPHA = 0.65
        private const val RECESSED_FILTER = "saturate(45%)"
    }

    /**
     * [dimmed] recesses the unit because a click on its hex will NOT act on it -- it is the
     * occupant of the layer Air Mode is not currently commanding
     * (`docs/design/action-affordances-and-objectives.md` §7).
     *
     * Deliberately subdued rather than dark: the player still has to read the battlefield while in
     * Air Mode, and this shares a visual axis with fog and the moved-unit text, which must stay
     * legible next to it.
     */
    fun drawUnit(
        ctx: dynamic,
        x: Double,
        y: Double,
        unit: GameUnit,
        showStats: Boolean,
        dimmed: Boolean = false,
    ) {
        // A recessed unit keeps its sprite but loses its stat overlay: the strength box, ammo mark
        // and leader badge all describe an action, and no action on this hex reaches this unit.
        if (dimmed) {
            withRecessedInk(ctx) { drawUnitSprite(ctx, x, y, unit) }
            return
        }
        if (!drawUnitSprite(ctx, x, y, unit) || !showStats || unit.strength < 1) return

        val side = unit.player?.side ?: 0
        val isCore = unit.isCore

        val fontSize = rc.unitFontSize.toInt()
        ctx.font = "${fontSize}px ${if (isCore) "coreUnitFont" else "unitInfo"}, sans-serif"

        val boxX = x + rc.hexSlantWidth / 2.0
        val boxY = y + 2.0 * rc.v - (rc.unitFontSize + 4.0)
        val boxW = if (unit.strength < 10) 12.0 else 19.0
        val boxH = rc.unitFontSize + 4.0
        val textOff = if (unit.strength < 10) 2.0 else 1.0

        drawStrengthBox(ctx, unit, side, isCore, boxX, boxY, boxW, boxH, textOff)
        drawAmmoIndicator(ctx, x, y, unit, side)
        drawLeaderBadge(ctx, y, unit, side, boxX, boxW)
    }

    private fun drawStrengthBox(
        ctx: dynamic,
        unit: GameUnit,
        side: Int,
        isCore: Boolean,
        boxX: Double,
        boxY: Double,
        boxW: Double,
        boxH: Double,
        textOff: Double,
    ) {
        ctx.fillStyle = boxFillStyle(side)
        if (isCore) {
            ctx.strokeStyle = boxBorderStyle(side)
            ctx.lineWidth = 2.0
            ctx.strokeRect(boxX, boxY - 1.0, boxW, boxH)
        }
        ctx.fillRect(boxX, boxY - 1.0, boxW, boxH)

        ctx.fillStyle = strengthTextStyle(unit)
        ctx.fillText("${unit.strength}", boxX + textOff, boxY + STRENGTH_TEXT_Y_OFFSET)
    }

    private fun boxFillStyle(side: Int): String {
        if (uiSettings.markEnemyUnits == true && side != GameHolder.instance?.spotSide) {
            return unitStyles["enemyBoxMarked"] as? String ?: "#FF0000"
        }
        return if (side == 1) {
            unitStyles["alliedBox"] as? String ?: "#808000"
        } else {
            unitStyles["axisBox"] as? String ?: "#383838"
        }
    }

    private fun boxBorderStyle(side: Int): String =
        if (side == 1) {
            unitStyles["alliedBorder"] as? String ?: "rgba(127,255,0,1)"
        } else {
            unitStyles["axisBorder"] as? String ?: "rgba(211,211,211,1)"
        }

    private fun strengthTextStyle(unit: GameUnit): String {
        val isCurrentPlayer = unit.player?.id == rc.map?.currentPlayer?.id
        val hasMoved = unit.hasMoved || rc.map?.let { TurnSleep.isAsleep(it, unit) } == true
        if (hasMoved && isCurrentPlayer) {
            return unitStyles["movedUnitText"] as? String ?: "#BDBDBD"
        }
        return if (unit.player?.id != rc.map?.currentPlayer?.id && unit.player?.side == rc.map?.currentPlayer?.side) {
            unitStyles["alliedPlayerText"] as? String ?: "#696969"
        } else {
            unitStyles["playerText"] as? String ?: "white"
        }
    }

    private fun drawAmmoIndicator(
        ctx: dynamic,
        x: Double,
        y: Double,
        unit: GameUnit,
        side: Int,
    ) {
        if (unit.hasFired || !GameRules.unitUsesAmmo(unit) || side != rc.map?.currentPlayer?.side) return
        val img = if (unit.getAmmo() > 0) rc.fireImage else rc.noAmmoImage
        ctx.drawImage(img, x - AMMO_ICON_X_OFFSET, y + 2.0 * rc.v - AMMO_ICON_Y_OFFSET)
    }

    private fun drawLeaderBadge(
        ctx: dynamic,
        y: Double,
        unit: GameUnit,
        side: Int,
        boxX: Double,
        boxW: Double,
    ) {
        if (!HeroCampaign.hasAnyCommander(unit)) return
        val img = if (side == 1) rc.leaderAlliedImage else rc.leaderAxisImage
        ctx.drawImage(img, boxX + boxW + 1.0, y + 2.0 * rc.v - LEADER_ICON_Y_OFFSET)
    }

    /**
     * Runs [draw] with the recessed-ink treatment applied, then restores the context exactly.
     *
     * Alpha carries the effect on its own, because `CanvasRenderingContext2D.filter` is not
     * universally available (Safari only shipped it in 17). Desaturation is applied on top where
     * the browser has it, and simply omitted where it does not -- a slightly less pronounced cue
     * beats a blank sprite.
     */
    private inline fun withRecessedInk(
        ctx: dynamic,
        draw: () -> Unit,
    ) {
        ctx.save()
        ctx.globalAlpha = RECESSED_ALPHA
        if (ctx.filter != undefined) ctx.filter = RECESSED_FILTER
        draw()
        ctx.restore()
    }

    private fun drawUnitSprite(
        ctx: dynamic,
        x: Double,
        y: Double,
        unit: GameUnit,
    ): Boolean {
        val isBridge =
            !unit.hasAnimation &&
                GameRules.isGround(unit) &&
                GameRules.isBridgeForSide(unit.getHex(), unit.player?.side ?: -1)
        val displayedEqid = unit.getEqid()
        val icon = UnitIconResolver.forCurrentScenario(displayedEqid, unit.getIcon())
        val img = if (isBridge) rc.bridgeImage else rc.unitImages[icon]
        if (img == null || img == undefined) {
            if (loggedMissingIcons.add(icon)) {
                console.log("[osada] drawUnitSprite missing image (logged once) for icon", icon, "eqid", displayedEqid)
            }
            return false
        }

        val frameW = (img.width as? Number)?.toDouble()?.div(9.0) ?: 0.0
        val imgH = (img.height as? Number)?.toDouble() ?: 0.0
        val drawX = x - frameW / 2.0 + rc.hexTopWidth / 2.0
        val drawY = y - imgH / 2.0 + rc.v - rc.unitFontSize

        var facing = unit.facing
        var mirror = false
        if (facing > MAX_FACING_INDEX) {
            facing = FACING_MIRROR_BASE - facing
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
