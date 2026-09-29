package org.osada.rules

import org.osada.Direction
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.movTable

/**
 * A bridge-capable formation standing in a river as a crossing for its own side -- the pontoon.
 *
 * **It takes a turn to lay.** A formation that ends a move on a river hex starts laying its
 * crossing ([GameUnit.bridgeLaying]); the crossing carries traffic from the start of its owner's
 * NEXT turn, if the formation is still there and still able to span it. That is OG's behaviour as
 * the owner observed it in play (2026-09-28): the IT-28 BL in the LXF Manchurian Offensive swings
 * its bridge over the Ussuri, and nothing crosses until the following turn. Until then the hex is a
 * river for everybody, and the unit keeps its own icon -- the pontoon sprite is drawn only for a
 * crossing that is open ([MovementRules.isBridgeForSide]).
 *
 * A formation already on a river when the battle begins (authored, deployed or arriving as a
 * reinforcement) never went through [onArrival], so its crossing is open from the start.
 */
internal object PontoonBridges {
    /** River, stream and major river. OG lets a bridge unit enter the last even where its own
     *  movement method calls it impassable (`equip.cfg`'s `limit_move2set_bridge`, default 0). */
    private val RIVER_TERRAIN =
        setOf(TerrainType.RIVER.value, TerrainType.STREAM.value, TerrainType.IMPASSABLE_RIVER.value)

    fun isRiver(hex: Hex?): Boolean = hex != null && hex.terrain in RIVER_TERRAIN

    /**
     * The facing the pontoon sprite is drawn with: along the hex axis that joins two banks, so the
     * span lies ACROSS the river rather than along it.
     *
     * The unit's own facing is its direction of travel, and that is not the same thing: on the LXF
     * Manchurian Offensive the IT-28 BL enters (25,19) from the south, at the foot of a stretch of
     * the Ussuri that runs north-south, and a bridge drawn along its travel lay down the river
     * (reported 2026-09-28). Each of the hex's three axes is scored by how many of its two ends are
     * dry ground; the best one wins, and a tie goes to the axis nearest the arrival facing -- which is
     * also the whole answer on a hex with no bank on any axis.
     */
    fun spanFacing(
        map: GameMap,
        unit: GameUnit,
    ): Int {
        val pos = unit.getPos() ?: return unit.facing
        val adjacent = HexGeometry.getAdjacent(pos.row, pos.col)
        val arrivalAxis = HexGeometry.facingToAdjacentIndex(unit.facing) % AXES.size
        val axis =
            AXES.indices.maxWith(
                compareBy<Int> { axis -> AXES[axis].count { isBank(map, adjacent[it].row, adjacent[it].col) } }
                    .thenBy { axis -> if (axis == arrivalAxis) 1 else 0 },
            )
        return AXIS_FACING[axis]
    }

    private fun isBank(
        map: GameMap,
        row: Int,
        col: Int,
    ): Boolean {
        val hex = map.map?.getOrNull(row)?.getOrNull(col) ?: return false
        return !isRiver(hex) && hex.terrain != TerrainType.OCEAN.value
    }

    /** The three axes through a hex, as pairs of [HexGeometry.getAdjacent] indices (N-S, NW-SE, SW-NE). */
    private val AXES = listOf(listOf(0, 3), listOf(1, 4), listOf(2, 5))

    /** One facing per axis in [AXES]; the sprite is symmetric, so either end of an axis draws the same. */
    private val AXIS_FACING = listOf(Direction.N.value, Direction.NW.value, Direction.NE.value)

    /** Whether [unit] standing on [hex] is in a position to span it: a river, on foot or tracks
     *  rather than aboard its transport, and with a bridge capability from any source. */
    fun canSpan(
        unit: GameUnit,
        hex: Hex?,
    ): Boolean = isRiver(hex) && !unit.isMounted && MovementRules.hasBridgeCapability(unit)

    /**
     * Called wherever [unit] comes to rest on a new hex during play -- an ordinary move or a forced
     * relocation. Starts laying a crossing on a river, and abandons one that was being laid (or
     * was open) anywhere else, since the formation has left it.
     *
     * Laying the crossing is the rest of the formation's turn. Without that a phased mover -- the
     * IT-28 BL carries `Recon Skill` (attr 1032) -- paid `GameUnit.move`'s normalised 1 point for
     * the river's 254 and drove on across the far bank (reported 2026-09-28).
     *
     * Stepping off such a river ([stranded], [from]) ends the move the same way: one hex to the
     * bank, and the formation drives on as an ordinary unit next turn.
     *
     * Returns true when a crossing was started, which is what the move announcement reads.
     */
    fun onArrival(
        unit: GameUnit,
        hex: Hex?,
        from: Hex? = null,
    ): Boolean {
        val leftTheRiver = from != null && stranded(unit, from)
        unit.bridgeLaying = canSpan(unit, hex)
        if (unit.bridgeLaying || leftTheRiver) {
            unit.moveLeft = 0
            unit.hasMoved = true
        }
        return unit.bridgeLaying
    }

    /**
     * Whether [unit] stands on a river its own movement method could never have entered -- it is
     * there only as a bridge. Such a formation leaves one hex at a time: OG offers the Construction
     * on (15,6) of the LXF Manchurian Offensive only (15,7) and (16,7) across the Amur, and the IT-28
     * BL on (25,19) only its neighbours (reported 2026-09-28). A road on the hex is a real bridge,
     * and a crossing like any other.
     */
    fun stranded(
        unit: GameUnit,
        hex: Hex?,
    ): Boolean =
        isRiver(hex) &&
            hex!!.road <= RoadType.NONE.value &&
            movTable.getOrNull(unit.unitData().movmethod)?.getOrNull(hex.terrain) == IMPASSABLE_COST

    /** OG's movement-table "cannot enter" entry (`MoveRangeCalculation.IMPASSABLE_TERRAIN_COST`). */
    private const val IMPASSABLE_COST = 255

    /**
     * Opens every crossing [playerId] began laying, at the start of that player's turn. Returns the
     * formations whose crossing is now open, for the turn announcement; one that is no longer able
     * to span its hex (it mounted up, say) simply stops laying without opening anything.
     */
    fun completeLaying(
        map: GameMap,
        playerId: Int,
    ): List<GameUnit> {
        val opened = mutableListOf<GameUnit>()
        map.units.forEach { unit ->
            if (unit.bridgeLaying && unit.player?.id == playerId && !unit.destroyed) {
                unit.bridgeLaying = false
                if (canSpan(unit, unit.getHex())) opened.add(unit)
            }
        }
        return opened
    }
}
