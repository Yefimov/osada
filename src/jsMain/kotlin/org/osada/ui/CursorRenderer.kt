package org.osada.ui

import kotlinx.browser.document
import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Hex
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

        /** `resources/ui/cursors/transport.png` is 32x17 and was sitting unused in the asset tree
         *  beside `attack.png`; the hotspot is its centre. Referenced as a plain CSS cursor rather
         *  than rendered through the back-buffer, because unlike the attack cursor it carries no
         *  per-target numbers to draw. */
        private const val TRANSPORT_CURSOR = "url('resources/ui/cursors/transport.png') 16 8, auto"

        /** OG's barrage crosshair (§9.2). `blow.png`, centred. */
        private const val BARRAGE_CURSOR = "url('resources/ui/cursors/blow.png') 12 12, crosshair"
    }

    private var cursorCell: Cell? = null
    private var cursorUnit: GameUnit? = null
    private var cursorDataUrl: String? = null

    fun drawCursor(cell: Cell) {
        val q = rc.map ?: return
        val hex = q.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return
        val currentUnit = q.currentUnit
        when {
            // Open General's own affordance: over a hex the formation can only reach by riding, the
            // pointer becomes a truck. The dashed hex ring says the same thing while the player is
            // still reading the map; this says it at the moment they are about to click
            // (`rules/AutoMount`). Attack wins over it -- a hex that offers a fight is never a hex
            // the move order is about.
            // OG: *"the pointer becomes a crosshair when it is over hexes that it can attack"*
            // (manual §9.2). `blow.png` was already in the asset tree, unused, beside `attack.png`.
            hex.isBarrageSel -> rc.cursorCanvas.style.cursor = BARRAGE_CURSOR
            showsTransportCursor(hex, currentUnit) -> rc.cursorCanvas.style.cursor = TRANSPORT_CURSOR
            hex.isAttackSel && currentUnit != null && !currentUnit.hasFired ->
                applyAttackCursor(hex, currentUnit)

            else -> rc.cursorCanvas.style.cursor = "default"
        }
    }

    /** Whether the hex under the pointer is one this formation would have to ride to. */
    private fun showsTransportCursor(
        hex: Hex,
        currentUnit: GameUnit?,
    ): Boolean = hex.needsTransport && !hex.isAttackSel && currentUnit != null && !currentUnit.hasMoved

    private fun applyAttackCursor(
        hex: Hex,
        currentUnit: GameUnit,
    ) {
        val target = hex.getActiveLayerTarget(currentUnit, uiSettings.airMode)
        if (target == null) {
            rc.cursorCanvas.style.cursor = "default"
            return
        }
        val cell = hex.getPos()
        val stale =
            cursorUnit?.id != currentUnit.id ||
                cursorCell?.row != cell.row ||
                cursorCell?.col != cell.col
        if (stale) {
            cursorUnit = currentUnit
            cursorCell = cell
            cursorDataUrl = generateAttackCursor(currentUnit, target, true) as? String
        }
        cursorDataUrl?.let { url ->
            val w = (rc.backBuffer.width as? Number)?.toInt() ?: 54
            val h = (rc.backBuffer.height as? Number)?.toInt() ?: 54
            rc.cursorCanvas.style.cursor = "url('$url') ${w / 2} ${h / 2}, auto"
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
