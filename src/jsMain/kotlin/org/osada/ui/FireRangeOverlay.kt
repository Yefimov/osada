package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.GameRules
import org.osada.rules.HexGeometry
import org.osada.rules.UnitConcealment
import org.osada.rules.getUnitAttackRange
import org.osada.uiSettings

/**
 * OG's *"Show range of fire for selected unit"* (manual §3.9.2;
 * `docs/design/og-parity-followups.md` §2, built 2026-10-09): the sidebar's Range toggle
 * ([org.osada.UiSettings.fireRange], key `G`).
 *
 * It answers a different question from [ArtilleryRangeOverlay]: not *"where do all the guns
 * reach?"* (aggregate coverage, every battery at once) but *"how far does THIS formation shoot?"*
 * -- for one unit of any class: anti-tank with range, ships, fortifications, AD, artillery. The two
 * overlays use different inks and may be on together.
 *
 * **Whose envelope.** The selected own formation ([GameMap.currentUnit]); with nothing selected,
 * the enemy the player inspected on its own ([inspectedEnemyId], set by the enemy card), but only
 * while that enemy is still visible to the viewing side -- the same fog gate the sprites and the
 * Artillery overlay use, so a unit that slipped back into fog takes its envelope with it.
 *
 * **What it promises.** A geometric disc of [getUnitAttackRange] (gun range, OG's `True range 0`,
 * the Marksman commander) around the unit's hex. It is NOT attack eligibility: line of sight,
 * target layer, weather, ammunition and the move/fire order can each still refuse a shot inside it,
 * and the button's help says so. The legal targets are what the selection rings already show.
 */
internal object FireRangeOverlay {
    /** The enemy whose card is open with nothing own selected, or null. UI state only: never
     *  saved, never sent. Cleared whenever the bottom zone leaves the enemy-alone state. */
    var inspectedEnemyId: Int? = null

    /** The formation whose envelope is drawn on [map] for [spotSide], or null. */
    fun subject(
        map: GameMap,
        spotSide: Int,
    ): GameUnit? {
        val selected = map.currentUnit
        val candidate =
            selected ?: inspectedEnemyId?.let { id -> map.units.firstOrNull { it.id == id } }
        return candidate?.takeIf { unit ->
            !unit.destroyed &&
                unit.getPos() != null &&
                (unit === selected || unit.player?.side == spotSide || UnitConcealment.isVisibleTo(unit, spotSide))
        }
    }

    /** The envelope as `row * cols + col` keys, excluding the unit's own hex; empty when the
     *  unit has no reach (range 0 under `True range 0`) or nothing qualifies. */
    fun compute(
        map: GameMap,
        spotSide: Int,
    ): Set<Int> {
        val unit = if (map.rows > 0 && map.cols > 0) subject(map, spotSide) else null
        val pos = unit?.getPos()
        return if (unit == null || pos == null) emptySet() else disc(map, unit, pos.row, pos.col)
    }

    private fun disc(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Set<Int> {
        val range =
            if (map.currentUnit?.id == unit.id) {
                org.osada.multiplayer.client.OsadaMultiplayer
                    .preview(unit) { GameRules.getUnitAttackRange(unit) }
            } else {
                GameRules.getUnitAttackRange(unit)
            }
        if (range <= 0) return emptySet()
        val own = row * map.cols + col
        return HexGeometry
            .getRing(row, col, range, map.rows, map.cols, false)
            .filter { it.row in 0 until map.rows && it.col in 0 until map.cols }
            .map { it.row * map.cols + it.col }
            .filter { it != own }
            .toSet()
    }

    /** The sidebar's Range toggle. Independent of Air/Artillery: its upright brass hatch reads
     *  apart from both, so it needs no exclusivity rule ([setMapLayerMode]). */
    fun toggle(ui: UI) {
        uiSettings.fireRange = !uiSettings.fireRange
        byId("firerange")?.let { toggleButton(it, uiSettings.fireRange) }
        ui.render.render()
    }
}
