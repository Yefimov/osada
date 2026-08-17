package org.osada.ui

import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getActiveLayerTarget
import org.osada.uiSettings

/**
 * [UnitInfoPanel]'s bottom-zone attack forecast + attack-ring preview on map hover, and the
 * enemy-alone inspection card. Split out purely to keep [UnitInfoPanel] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal class UnitHoverForecast(
    private val ui: UI,
) {
    /** Bottom-zone forecast+enemy-card on attack-target hover (Task 3). Reuses the EXACT same
     *  attack-availability check the existing cursor forecast and click-to-attack path already
     *  use (`hex.isAttackSel` + `getAttackableUnit`) — no new range/LOS math, and the bottom zone
     *  can only ever show what those paths already reveal (fog-of-war safe). Guarded by a
     *  last-cell cache so mousemove stays cheap. */
    fun updateHoverInfo(
        row: Int,
        col: Int,
    ) {
        if (row == lastHoverRow && col == lastHoverCol) return
        lastHoverRow = row
        lastHoverCol = col
        val map = ui.game.scenario?.map
        val hex =
            if (map != null && row in 0 until map.rows && col in 0 until map.cols) {
                map.map?.get(row)?.get(col)
            } else {
                null
            }
        if (map == null || hex == null) return
        updateForecast(map.currentUnit, map.currentPlayer?.side, hex, row, col)
    }

    private fun updateForecast(
        selected: GameUnit?,
        ownSide: Int?,
        hex: Hex,
        row: Int,
        col: Int,
    ) {
        val target =
            if (selected != null && hex.isAttackSel && !selected.hasFired) {
                hex.getActiveLayerTarget(selected, uiSettings.airMode)
            } else {
                null
            }

        if (selected != null && target != null) {
            if (ownSide == null) return
            BottomZoneBuilder.renderForecast(selected, target, ownSide)
        } else {
            BottomZoneBuilder.onHoverLeft()
        }

        // Task 6 hover-preview extension (measured comfortably under the ~5ms budget — see
        // AttackRingBuilder's doc comment): while hovering a reachable MOVE hex (not an attack
        // target — a different hex category), preview which enemies would become attackable
        // from THAT hex; otherwise revert to the rings for the unit's actual current position.
        if (selected != null && hex.isMoveSel) {
            AttackRingBuilder.previewFromHover(row, col)
        } else {
            AttackRingBuilder.revertHoverPreview()
        }
    }

    private var lastHoverRow = -1
    private var lastHoverCol = -1

    /** Forces the next [updateHoverInfo] call to recompute even if the cursor hasn't moved
     *  (used when the SELECTION changes, since the cache only tracks cell position). */
    fun resetHoverCache() {
        lastHoverRow = -1
        lastHoverCol = -1
    }

    /** Foreign-unit inspection (right-click, or a plain click with nothing own selected) — the
     *  enemy card, never `#unit-info` (which is now reserved for the player's own unit, Task 3). */
    fun showEnemyCard(unit: GameUnit) {
        ui.game.scenario
            ?.map
            ?.currentPlayer
            ?.side ?: return
        BottomZoneBuilder.showEnemyAlone(unit)
    }
}
