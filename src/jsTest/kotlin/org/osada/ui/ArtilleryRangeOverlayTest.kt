package org.osada.ui

import org.osada.GameHolder
import org.osada.rules.HexGeometry
import org.osada.rules.OgRulesTestHarness
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar's Artillery toggle (`ui/ArtilleryRangeOverlay`): which hexes end up hatched red and
 * which black.
 *
 * Two of these are the ones worth locking. A hidden enemy battery must contribute NOTHING — an
 * envelope drawn around a formation the player cannot see would be a free reveal, the rule hidden
 * AA is drawn by (`DEFERRED.md` §1.1). And a long-ranged NON-artillery formation must contribute
 * nothing either, which is what keeps the shading meaning "artillery" rather than "anything that
 * shoots past its own hex".
 */
class ArtilleryRangeOverlayTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun tearDown() {
        clearTestWorld()
    }

    @Test
    fun ownBatteryCoversItsOwnHexAndEverythingInsideItsGunRange() {
        val map = world()
        GameHolder.instance = holderFor(map)
        // gunEqid is the harness' ARTILLERY record, gunrange 3.
        place(map, gunEqid, 4, 4, side = 0)

        val coverage = ArtilleryRangeOverlay.compute(map, spotSide = 0)

        assertTrue(coverage.own.contains(coverage.key(4, 4, map.cols)), "the battery's own hex")
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) {
                val inside = HexGeometry.distance(4, 4, r, c) <= 3
                assertEquals(
                    inside,
                    coverage.own.contains(coverage.key(r, c, map.cols)),
                    "hex ($r,$c) at distance ${HexGeometry.distance(4, 4, r, c)}",
                )
            }
        }
        assertTrue(coverage.enemy.isEmpty(), "no enemy guns on the map")
    }

    @Test
    fun enemyBatteryIsHatchedOnlyOnceItsHexIsSpotted() {
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, gunEqid, 2, 2, side = 1)

        assertTrue(
            ArtilleryRangeOverlay.compute(map, spotSide = 0).enemy.isEmpty(),
            "an unspotted battery must not reveal its reach",
        )

        map.map!![2][2].setSpotted(0, true)
        val coverage = ArtilleryRangeOverlay.compute(map, spotSide = 0)

        assertTrue(coverage.enemy.contains(coverage.key(2, 2, map.cols)))
        assertTrue(coverage.enemy.contains(coverage.key(2, 5, map.cols)), "three hexes out")
        assertFalse(coverage.enemy.contains(coverage.key(6, 6, map.cols)), "well beyond gunrange")
        assertTrue(coverage.own.isEmpty(), "the observing side fielded no guns")
    }

    /** `riflemanEqid` is a TANK with `gunrange = 4` — longer-reaching than the battery and still
     *  not artillery, so it must leave the map unhatched. */
    @Test
    fun aLongRangedNonArtilleryFormationIsNotArtillery() {
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, riflemanEqid, 4, 4, side = 0)

        assertTrue(ArtilleryRangeOverlay.compute(map, spotSide = 0).isEmpty())
    }

    /** Both sides' envelopes are computed independently, so a hex both cover appears in both sets —
     *  that overlap is exactly what the two opposed hatch directions are for. */
    @Test
    fun contestedHexesAppearInBothEnvelopes() {
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, gunEqid, 4, 2, side = 0)
        place(map, gunEqid, 4, 6, side = 1)
        map.map!![4][6].setSpotted(0, true)

        val coverage = ArtilleryRangeOverlay.compute(map, spotSide = 0)
        val contested = coverage.key(4, 4, map.cols)

        assertTrue(coverage.own.contains(contested))
        assertTrue(coverage.enemy.contains(contested))
    }
}
