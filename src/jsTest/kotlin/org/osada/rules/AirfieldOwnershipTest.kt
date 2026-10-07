package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.aircraftMayDeployOn
import org.osada.model.friendlyAirfields
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
    private val fighterEqid = 990

    @BeforeTest
    fun setup() {
        installTestWorld()
        friendly.country = 89
        hostile.country = 7
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "I-16"
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
            },
        )
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

    /** The field flagged AND owned, as a scenario's `owner=`/`flag=` pair writes it. */
    private fun ownedField(owner: Int): GameMap =
        minskField(flag = if (owner == friendly.id) friendly.country else hostile.country).also { map ->
            map.map!![flagHex.first][flagHex.second].owner = owner
        }

    private fun reserveFighter() =
        GameUnit(fighterEqid).apply {
            player = friendly
            owner = friendly.id
        }

    @Test
    fun anAircraftDeploysOnEveryHexOfAFriendlyField() {
        // Khalkhin Gol: OG deploys on (27,3) and on the bare (27,4)/(26,3) beside it.
        val map = ownedField(owner = friendly.id)
        val fighter = reserveFighter()

        field.forEach { (r, c) -> assertTrue(map.aircraftMayDeployOn(fighter, r, c), "($r,$c) is our field") }
        assertTrue(map.friendlyAirfields(friendly.side).size == field.size)
    }

    @Test
    fun noAircraftDeploysOnAnEnemyField() {
        val map = ownedField(owner = hostile.id)
        val fighter = reserveFighter()

        field.forEach { (r, c) -> assertFalse(map.aircraftMayDeployOn(fighter, r, c)) }
    }

    @Test
    fun aSeparateUnownedStripOpensNoDeployment() {
        val map = ownedField(owner = friendly.id)
        map.map!![6][6].terrain = TerrainType.AIRFIELD.value

        assertFalse(map.aircraftMayDeployOn(reserveFighter(), 6, 6))
    }
}
