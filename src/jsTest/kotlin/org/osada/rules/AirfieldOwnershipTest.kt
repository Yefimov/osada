package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AirfieldOwnership]: one flag speaks for a multi-hex airfield.
 *
 * The field is Minsk Airfield from `forward2.xml` -- `(21,17)`, `(22,16)`, `(22,17)` and the
 * flagged `(22,18)` -- shifted by (-19, -14), which keeps every column's parity and therefore every
 * adjacency. `(3,2)` is the owner's MiG-3 hex: an airfield hex two hexes from the flag, beside no
 * flagged hex at all.
 */
class AirfieldOwnershipTest : OgRulesTestHarness() {
    private val field = listOf(2 to 3, 3 to 2, 3 to 3, 3 to 4)
    private val flagHex = 3 to 4

    @BeforeTest
    fun setup() {
        installTestWorld()
        friendly.country = 89
        hostile.country = 7
    }

    @AfterTest
    fun tearDown() = clearTestWorld()

    private fun minskField(flag: Int?): GameMap =
        world().also { map ->
            field.forEach { (r, c) -> map.map!![r][c].terrain = TerrainType.AIRFIELD.value }
            if (flag != null) map.map!![flagHex.first][flagHex.second].flag = flag
        }

    @Test
    fun anAircraftAnywhereOnAFlaggedFieldIsOnItsOwnAirfield() {
        val map = minskField(flag = friendly.country)
        val mig = place(map, infantryEqid, 3, 2, side = 0)

        assertTrue(MovementRules.hasAirfield(map, mig), "(3,2) is Minsk Airfield, whose flag is ours")
    }

    @Test
    fun anEnemyFlagMakesTheWholeFieldTheirs() {
        val map = minskField(flag = hostile.country)
        val mig = place(map, infantryEqid, 3, 2, side = 0)

        assertFalse(MovementRules.hasAirfield(map, mig))
        assertTrue(AirfieldOwnership.servesCountry(map, 3, 2, hostile.country))
    }

    @Test
    fun aFieldWithNoFlagAnywhereStaysScenery() {
        val map = minskField(flag = null)
        val mig = place(map, infantryEqid, 3, 2, side = 0)

        assertFalse(MovementRules.hasAirfield(map, mig))
    }

    @Test
    fun aSeparateUnflaggedStripDoesNotBorrowAnotherFieldsFlag() {
        val map = minskField(flag = friendly.country)
        // Two clear hexes away from the Minsk field: a different airfield.
        map.map!![6][6].terrain = TerrainType.AIRFIELD.value
        val mig = place(map, infantryEqid, 6, 6, side = 0)

        assertFalse(MovementRules.hasAirfield(map, mig))
    }

    @Test
    fun aWreckedHexOfAFlaggedFieldStillBasesNothing() {
        val map = minskField(flag = friendly.country)
        map.map!![3][2].rubble = true
        map.map!![3][3].rubble = true
        map.map!![2][3].rubble = true
        val mig = place(map, infantryEqid, 3, 2, side = 0)

        assertFalse(MovementRules.hasAirfield(map, mig), "OG: unusable until repaired")
    }
}
