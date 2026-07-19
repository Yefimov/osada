package org.osada.ui

import kotlinx.browser.document
import org.osada.GameHolder
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getAttackableUnit
import org.osada.rules.GameRules
import org.osada.rules.getRing
import org.osada.rules.getUnitAttackRange
import org.osada.ui.AttackRingBuilder.MEASURED_HOVER_SAFE
import org.osada.uiSettings

/**
 * Task 6: red hex-contour rings on enemy units attackable by the currently selected own unit.
 * Pure DOM overlay — zero canvas code touched. A container div lives inside `#game` (the map's
 * own scroll container) at the same (0,0) offset as the map canvases, so it scrolls with the map
 * for free; each ring is positioned with the SAME hex→pixel conversion the canvases themselves
 * use ([Render.cellToScreen], already `internal`/same-package — reused, not reimplemented).
 *
 * Hex geometry constants (S/Y/v) mirror [RenderContext]'s literal values (30/15/25) — replicated
 * locally per the spec's own fallback, since RenderContext itself is private inside [Render] and
 * not otherwise exposed.
 *
 * Which enemies to mark reuses the exact same building blocks the existing click-to-attack path
 * and cursor forecast already use: [GameRules.getRing] for the range ring and
 * [Hex.getAttackableUnit] for the per-cell availability check — no range/LOS math is
 * reimplemented, only orchestrated (mirroring [GameRules.getUnitAttackCells]'s own approach,
 * parameterized by an explicit row/col so it can ALSO answer "attackable from hex X" for the
 * (measured, see report) hover-preview extension without moving the real unit).
 */
internal object AttackRingBuilder {
    // Mirrors RenderContext's hex geometry (S=30, Y=15, v=25) — see class doc. Only Y is needed
    // directly here (to offset the ring's left edge from cellToScreen's anchor point); the ring's
    // CSS width/height (60/50 = S+2Y / 2v) and clip-path hexagon are literal in osada-theme.css.
    private const val Y = 15.0
    private var container: dynamic = null

    // Per-selection cache for the hover-preview extension (row,col -> attackable cell list),
    // invalidated on selection change / unit move / turn change (whenever rebuild() runs for a
    // new "current position" set).
    private val hoverCache = mutableMapOf<Long, List<Cell>>()
    private var cacheOwnerUnitId: Int = -1

    fun build() {
        val game = byId("game") ?: return
        val div = document.createElement("div").asDynamic()
        div.id = "osada-attack-rings"
        game.appendChild(div)
        container = div
    }

    private fun anyModalOpen(): Boolean =
        isVisible("equipment") ||
            isVisible("smSettings") ||
            isVisible("startmenu") ||
            isVisible("combatLog") ||
            isVisible("dossier")

    /** Clears all rings — called on deselection, end of turn, and modal open (spec). */
    fun clear() {
        val c = container ?: return
        clearTag(c.unsafeCast<org.w3c.dom.Element>())
        hoverCache.clear()
        cacheOwnerUnitId = -1
    }

    /** Rebuilds rings for the CURRENTLY SELECTED unit's actual position. Called from the same
     *  touchpoints Minimap/BottomZone already use (updateStatusBar, uiUnitSelect) — covers
     *  selection, move, and combat-end refreshes without extra wiring. */
    fun refresh() {
        val map = GameHolder.instance?.scenario?.map
        val unit = map?.currentUnit
        if (map == null || unit == null || anyModalOpen()) {
            clear()
            return
        }
        // Always recompute from ground truth here, don't just clear on a unit-id change: refresh()
        // runs after every move/attack (via updateStatusBar), and firing never moves the unit, so
        // the cache key (same row,col) was still a hit — repainting a ring from BEFORE the attack
        // around a target that's now unattackable (hasFired) or destroyed. The hover-preview cache
        // only needs to survive within one mouse-sweep session between previewFromHover calls,
        // which never calls refresh() itself, so clearing unconditionally here costs nothing there.
        hoverCache.clear()
        cacheOwnerUnitId = unit.id
        val pos =
            unit.getPos() ?: run {
                clear()
                return
            }
        val targets = attackableCellsFrom(map, unit, pos.row, pos.col)
        paint(targets)
    }

    /** Attackable-enemy cells from an ARBITRARY position (not necessarily the unit's real one) —
     *  same building blocks as GameRules.getUnitAttackCells, reused rather than reimplemented,
     *  just parameterized by row/col instead of reading unit.getPos() internally. Cached per
     *  (row,col) within the current selection for the hover-preview extension. */
    private fun attackableCellsFrom(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): List<Cell> {
        val key = row.toLong() * 10000L + col.toLong()
        hoverCache[key]?.let { return it }
        val result = mutableListOf<Cell>()
        if (!unit.hasFired && unit.getAmmo() > 0) {
            val range = GameRules.getUnitAttackRange(unit)
            val ring = GameRules.getRing(row, col, range, map.rows, map.cols, false)
            ring.add(Cell(row, col))
            for (cell in ring) {
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: continue
                if (hex.getAttackableUnit(unit, uiSettings.airMode) != null) result.add(cell)
            }
        }
        hoverCache[key] = result
        return result
    }

    /** Hover-preview extension: "what if I moved here" — only wired if [MEASURED_HOVER_SAFE] is
     *  true (see report for the measured timing this decision is based on). Marks enemies
     *  attackable from a REACHABLE MOVE hex while hovering it, with an own unit selected. */
    fun previewFromHover(
        row: Int,
        col: Int,
    ) {
        if (!MEASURED_HOVER_SAFE || anyModalOpen()) return
        val map = GameHolder.instance?.scenario?.map ?: return
        val unit = map.currentUnit
        val hex = map.map?.getOrNull(row)?.getOrNull(col)
        // only preview genuinely reachable hexes
        if (unit != null && hex != null && hex.isMoveSel) {
            paint(attackableCellsFrom(map, unit, row, col))
        }
    }

    /** Reverts to the real (non-hypothetical) position's rings — called when hover leaves a
     *  reachable move hex, or immediately if the hover extension isn't enabled. */
    fun revertHoverPreview() {
        if (!MEASURED_HOVER_SAFE) return
        refresh()
    }

    private fun paint(targets: List<Cell>) {
        val c = container ?: return
        val ui = GameHolder.instance?.ui ?: return
        clearTag(c.unsafeCast<org.w3c.dom.Element>())
        for (cell in targets) {
            val p = ui.render.cellToScreen(cell.row, cell.col, false)
            val ring = document.createElement("div").asDynamic()
            ring.className = "osada-atk-ring"
            ring.style.left = "${p.x - Y}px"
            ring.style.top = "${p.y}px"
            c.appendChild(ring)
        }
    }

    /** Measured (see report): mean 0.017ms, p95 0.1ms, max 0.2ms for the attack-availability
     *  check at one hypothetical position, swept across ~half the map (288 positions) against 46
     *  enemies — comfortably under the ~5ms budget, so the hover-preview extension is enabled. */
    private const val MEASURED_HOVER_SAFE = true
}
