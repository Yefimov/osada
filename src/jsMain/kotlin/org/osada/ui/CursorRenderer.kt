package org.osada.ui

import kotlinx.browser.document
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.getActiveLayerTarget
import org.osada.model.getUnits
import org.osada.rules.GameRules
import org.osada.rules.calculateCombatResults
import org.osada.uiSettings

/**
 * Builds and applies the attack cursor that previews a melee outcome (attacker/defender
 * flags and the projected losses/kills). Extracted from the former `Render` god-class;
 * caches the last cursor so it is only regenerated when the unit or target cell changes.
 */
internal class CursorRenderer(
    private val rc: RenderContext,
) {
    companion object {
        private const val CURSOR_TEXT_X_OFFSET = 4.0
    }

    private var cursorCell: Cell? = null
    private var cursorUnit: GameUnit? = null
    private var cursorDataUrl: String? = null

    fun drawCursor(cell: Cell) {
        val q = rc.map ?: return
        val hex = q.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return
        val currentUnit = q.currentUnit
        if (hex.isAttackSel && currentUnit != null && !currentUnit.hasFired) {
            val target = hex.getActiveLayerTarget(currentUnit, uiSettings.airMode)
            if (target != null) {
                if (cursorUnit?.id != currentUnit.id ||
                    cursorCell?.row != cell.row ||
                    cursorCell?.col != cell.col
                ) {
                    cursorUnit = currentUnit
                    cursorCell = cell
                    cursorDataUrl = generateAttackCursor(currentUnit, target, true) as? String
                }
                cursorDataUrl?.let { url ->
                    val w = (rc.backBuffer.width as? Number)?.toInt() ?: 54
                    val h = (rc.backBuffer.height as? Number)?.toInt() ?: 54
                    rc.cursorCanvas.style.cursor = "url('$url') ${w / 2} ${h / 2}, auto"
                }
            } else {
                rc.cursorCanvas.style.cursor = "default"
            }
        } else {
            rc.cursorCanvas.style.cursor = "default"
        }
    }

    /**
     * Renders the attack cursor into the back-buffer. Returns a data-URL string when
     * [asDataUrl] is true (for CSS `cursor:`), otherwise a detached canvas element (for
     * the touch attack preview drawn directly onto the map).
     */
    fun generateAttackCursor(
        attacker: GameUnit,
        defender: GameUnit,
        asDataUrl: Boolean = false,
    ): dynamic {
        val w = (rc.backBuffer.width as? Number)?.toInt() ?: 54
        val h = (rc.backBuffer.height as? Number)?.toInt() ?: 54
        val bb = rc.backBufferCtx

        bb.clearRect(0.0, 0.0, w.toDouble(), h.toDouble())

        if (rc.attackCursorImage != null) {
            val iw = (rc.attackCursorImage.width as? Number)?.toInt() ?: 0
            val ih = (rc.attackCursorImage.height as? Number)?.toInt() ?: 0
            bb.drawImage(rc.attackCursorImage, w / 2.0 - iw / 2.0, h / 2.0 - ih / 2.0)
        }

        val aFlag = (attacker.flag - 1).coerceAtLeast(0)
        val dFlag = (defender.flag - 1).coerceAtLeast(0)
        drawCombatantFlags(aFlag, dFlag, w)

        bb.font = "${rc.unitFontSize.toInt()}px coreUnitFont, sans-serif"
        bb.fillStyle = "yellow"
        bb.textBaseline = "top"

        val results =
            GameRules.calculateCombatResults(
                attacker,
                defender,
                rc.map?.getUnits()?.toList() ?: emptyList(),
                false,
                true,
            )
        val losses = results.losses
        val kills = results.kills

        bb.strokeText("$losses", rc.flagIconWidth / 2.0 - CURSOR_TEXT_X_OFFSET + 1.0, rc.flagIconHeight + 1.0)
        bb.fillText("$losses", rc.flagIconWidth / 2.0 - CURSOR_TEXT_X_OFFSET, rc.flagIconHeight)
        bb.strokeText("$kills", w - rc.flagIconWidth / 2.0 - CURSOR_TEXT_X_OFFSET + 1.0, rc.flagIconHeight + 1.0)
        bb.fillText("$kills", w - rc.flagIconWidth / 2.0 - CURSOR_TEXT_X_OFFSET, rc.flagIconHeight)

        return if (asDataUrl) {
            rc.backBuffer.toDataURL() as String
        } else {
            val c = document.createElement("canvas").asDynamic()
            c.width = w
            c.height = h
            c.getContext("2d").drawImage(rc.backBuffer, 0.0, 0.0)
            c
        }
    }

    /** Draws attacker (left) and defender (right) flag icons into the back-buffer. */
    private fun drawCombatantFlags(
        aFlag: Int,
        dFlag: Int,
        w: Int,
    ) {
        val bb = rc.backBufferCtx
        bb.drawImage(
            rc.flagImage,
            rc.flagIconWidth * aFlag,
            0.0,
            rc.flagIconWidth,
            rc.flagIconHeight,
            0.0,
            0.0,
            rc.flagIconWidth,
            rc.flagIconHeight,
        )
        bb.drawImage(
            rc.flagImage,
            rc.flagIconWidth * dFlag,
            0.0,
            rc.flagIconWidth,
            rc.flagIconHeight,
            w - rc.flagIconWidth.toInt(),
            0.0,
            rc.flagIconWidth,
            rc.flagIconHeight,
        )
    }
}
