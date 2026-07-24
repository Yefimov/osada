package org.osada.model

import org.osada.TerrainType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the OG purchase gate: a player may only be offered purchases when there is somewhere to
 * put the unit — a designated deployment zone, or an owned PORT (OG's persistent supply hex).
 *
 * The four cases below are the real OG scenarios the rule was derived from:
 *  - `bn9s00` (Seseña): 0 deploy hexes, 0 ports  -> OG offers NEITHER side anything to buy.
 *  - `bn9s02` / `Forward0`: designated deploy hexes -> buying works.
 *  - `n_kiel`: 0 deploy hexes and 3 ports, all enemy-owned at start -> no buying until a port is
 *    captured, at which point deployment opens on it and adjacent land (observed in-game).
 */
class PurchaseAnchorTest {
    private fun buildMap(): GameMap {
        val map =
            GameMap().apply {
                rows = 5
                cols = 5
                allocMap()
            }
        map.addPlayer(
            Player().apply {
                id = 0
                side = 0
                country = 0
            },
        )
        map.addPlayer(
            Player().apply {
                id = 1
                side = 1
                country = 1
            },
        )
        return map
    }

    private fun hex(
        map: GameMap,
        row: Int,
        col: Int,
    ): Hex = map.map!![row][col]

    /** bn9s00: no deployment zone, no port -> nothing to buy, for either side. */
    @Test
    fun noDeployZoneAndNoPortBlocksPurchase() {
        val map = buildMap()
        assertFalse(map.hasPurchaseAnchor(0), "side 0 has no anchor")
        assertFalse(map.hasPurchaseAnchor(1), "side 1 has no anchor either")
    }

    /** bn9s02 / Forward0: a designated deployment hex is enough on its own. */
    @Test
    fun designatedDeployHexAllowsPurchase() {
        val map = buildMap()
        hex(map, 2, 2).isDeployment = 0
        assertTrue(map.hasPurchaseAnchor(0))
        assertFalse(map.hasPurchaseAnchor(1), "deploy zone belongs to player 0 only")
    }

    /** n_kiel: an enemy-held port is not an anchor — ownership is what counts. */
    @Test
    fun enemyOwnedPortIsNotAnAnchor() {
        val map = buildMap()
        hex(map, 1, 1).apply {
            terrain = TerrainType.PORT.value
            owner = 1
        }
        assertFalse(map.hasPurchaseAnchor(0), "port is enemy-owned")
        assertTrue(map.hasPurchaseAnchor(1), "its owner does have an anchor")
    }

    /** n_kiel after the capture: taking the port opens purchasing mid-scenario. */
    @Test
    fun capturingPortOpensPurchase() {
        val map = buildMap()
        val port =
            hex(map, 1, 1).apply {
                terrain = TerrainType.PORT.value
                owner = 1
            }
        assertFalse(map.hasPurchaseAnchor(0))
        port.owner = 0
        assertTrue(map.hasPurchaseAnchor(0), "captured port becomes a supply hex")
    }

    /** A plain owned city is NOT an anchor: Seseña is owned at scenario start and still permits
     *  no purchases, which is what separates this rule from "own any city". */
    @Test
    fun ownedCityIsNotAnAnchor() {
        val map = buildMap()
        hex(map, 3, 3).apply {
            terrain = TerrainType.CITY.value
            owner = 0
        }
        assertFalse(map.hasPurchaseAnchor(0))
    }
}
