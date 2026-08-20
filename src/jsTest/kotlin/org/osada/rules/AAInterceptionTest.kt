package org.osada.rules

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.canUndoMove
import org.osada.model.getUnits
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AA interception of moving aircraft (DEFERRED.md §1.1, `docs/design/aa-interception.md`).
 * `g2a_intercept_mode` is a bitmask; mode 0 is NOT "off" -- hidden AA already intercepts there.
 */
class AAInterceptionTest {
    private val planeEqid = 1
    private val flakEqid = 2
    private val groundEqid = 3

    private lateinit var map: GameMap
    private lateinit var planeSidePlayer: Player
    private lateinit var aaSidePlayer: Player

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                movmethod = MovMethod.AIR.value
                target = UnitType.AIR.value
                movpoints = 6
                grounddef = 4
                airdef = 4
            },
        )
        Equipment.putEquipment(
            flakEqid,
            EquipmentData().apply {
                uclass = UnitClass.AIR_DEFENCE.value
                movmethod = MovMethod.WHEELED.value
                airatk = 8
                gunrange = 1
                ammo = 10
            },
        )
        Equipment.putEquipment(
            groundEqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                target = UnitType.SOFT.value
            },
        )
        map =
            GameMap().apply {
                rows = 10
                cols = 10
                allocMap()
            }
        planeSidePlayer =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        aaSidePlayer =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(planeSidePlayer)
        map.addPlayer(aaSidePlayer)
    }

    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
    }

    private fun plane(
        row: Int,
        col: Int,
    ): GameUnit {
        val unit =
            GameUnit(planeEqid).apply {
                owner = planeSidePlayer.id
                player = planeSidePlayer
                strength = 10
            }
        map.map
            ?.get(row)
            ?.get(col)
            ?.setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    private fun flak(
        row: Int,
        col: Int,
        spotted: Boolean,
    ): GameUnit {
        val unit =
            GameUnit(flakEqid).apply {
                owner = aaSidePlayer.id
                player = aaSidePlayer
                strength = 10
            }
        map.map
            ?.get(row)
            ?.get(col)
            ?.setUnit(unit)
        map.addUnit(unit)
        map.map
            ?.get(row)
            ?.get(col)
            ?.setSpotted(planeSidePlayer.side, spotted)
        return unit
    }

    // --- Direct rule-level tests: interceptorsFor / applyInterception, no pathfinding involved ---

    @Test
    fun hiddenFlakInterceptsAPlaneFlyingThrough() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        val plane = plane(5, 5)
        val aa = flak(5, 6, spotted = false)
        val throughCell = Cell(5, 6)

        val interceptors = AAInterception.interceptorsFor(map, plane, throughCell, isDestination = false)

        assertTrue(aa in interceptors, "hidden AA must intercept a plane merely flying through its range")
        AAInterception.applyInterception(map, plane, interceptors)
        assertTrue(plane.strength < 10, "the plane must take damage")
        assertTrue(aa.tempSpotted, "firing reveals a hidden flak")
    }

    /** OG's `No Intercept Air` (`SpecialEx` 60.5, `attrEx` bit 5): disables the INTERCEPTION path
     *  specifically, while leaving ordinary defensive fire alone -- `OG_ABILITY_AUDIT.md` §2. */
    @Test
    fun noInterceptAirVetoesInterceptionButNotTheBadgeEligibility() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        Equipment.getEquipment(flakEqid)!!.attrEx = 32 // No Intercept Air
        val plane = plane(5, 5)
        val aa = flak(5, 6, spotted = false)
        val throughCell = Cell(5, 6)

        val interceptors = AAInterception.interceptorsFor(map, plane, throughCell, isDestination = false)

        assertEquals(emptyList(), interceptors, "No Intercept Air must veto the interception path")
        assertTrue(UnitCapabilities.hasAirDefenceFire(aa.unitData()), "ordinary defensive AA fire is unaffected")
    }

    @Test
    fun spottedFlakDoesNotInterceptAPlaneFlyingThrough() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        val plane = plane(5, 5)
        flak(5, 6, spotted = true)
        val throughCell = Cell(5, 6)

        val interceptors = AAInterception.interceptorsFor(map, plane, throughCell, isDestination = false)

        assertEquals(emptyList(), interceptors, "spotted AA never intercepts a plane merely passing by (mode 0)")
    }

    @Test
    fun spottedFlakInterceptsAPlaneFinishingInRangeOnlyInMode2() {
        val plane = plane(5, 5)
        flak(5, 6, spotted = true)
        val destinationCell = Cell(5, 6)

        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        assertEquals(
            emptyList(),
            AAInterception.interceptorsFor(map, plane, destinationCell, isDestination = true),
            "mode 0: spotted AA does not intercept even at the destination",
        )

        EfileConfig.resetForTest()
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 2))
        assertEquals(
            1,
            AAInterception.interceptorsFor(map, plane, destinationCell, isDestination = true).size,
            "mode 2 (bit set): spotted AA intercepts a plane finishing its move in range",
        )
    }

    @Test
    fun interceptionUsesFlakRangeNotGunRange() {
        // This AA's own gunrange is 1 (see setup), but flak_range=4 must still reach 4 hexes.
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0, "flak_range" to 4))
        val plane = plane(5, 5)
        val aa = flak(5, 9, spotted = false)
        val farCell = Cell(5, 9)

        val interceptors = AAInterception.interceptorsFor(map, plane, farCell, isDestination = false)

        assertTrue(aa in interceptors, "flak_range=4 must reach an AA unit 4 hexes from the plane's cell")
    }

    // --- Threat overlay: visibleThreatHexes must NEVER leak hidden AA, and must never draw
    // --- spotted-AA coverage that cannot actually fire (bit 2 unset -- DEFERRED.md §3) ---

    @Test
    fun visibleThreatHexesIncludesOnlySpottedAaCoverage() {
        EfileConfig.setForTest(mapOf("flak_range" to 1, "g2a_intercept_mode" to 2))
        val plane = plane(5, 5)
        flak(5, 6, spotted = true)

        val threatened = AAInterception.visibleThreatHexes(map, planeSidePlayer.side, plane)

        assertTrue((5 to 6) in threatened, "the spotted AA's own hex is threatened")
    }

    @Test
    fun visibleThreatHexesNeverLeaksHiddenAa() {
        EfileConfig.setForTest(mapOf("flak_range" to 1, "g2a_intercept_mode" to 2))
        val plane = plane(5, 5)
        flak(5, 6, spotted = false)

        val threatened = AAInterception.visibleThreatHexes(map, planeSidePlayer.side, plane)

        assertEquals(emptySet(), threatened, "hidden AA must never appear in the drawable threat set")
    }

    @Test
    fun visibleThreatHexesIsEmptyWhenSpottedAaCannotIntercept() {
        // Mode 0 (every efile except LXF): spotted AA never intercepts, so there is nothing to warn about.
        EfileConfig.setForTest(mapOf("flak_range" to 1, "g2a_intercept_mode" to 0))
        val plane = plane(5, 5)
        flak(5, 6, spotted = true)

        val threatened = AAInterception.visibleThreatHexes(map, planeSidePlayer.side, plane)

        assertEquals(emptySet(), threatened, "mode 0 spotted AA can't fire, so it must not be drawn as a threat")
    }

    @Test
    fun mode1BlocksAirDefenceAfterIntercepting() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 1))
        val plane = plane(5, 5)
        val aa = flak(5, 6, spotted = false)
        val cell = Cell(5, 6)

        AAInterception.applyInterception(map, plane, AAInterception.interceptorsFor(map, plane, cell, false))
        assertTrue(aa.hasInterceptedThisTurn, "mode bit 1 must mark the AA as spent for air-defense")

        // Now check the SAME AA is refused as a support-fire eligible unit against a fresh attacker.
        val ground =
            GameUnit(groundEqid).apply {
                owner = planeSidePlayer.id
                player = planeSidePlayer
                strength = 10
            }
        map.map
            ?.get(6)
            ?.get(6)
            ?.setUnit(ground)
        map.addUnit(ground)
        val attackerPlane = plane(6, 5)

        val supporters = CombatResolver.getSupportFireUnits(map.getUnits().toList(), attackerPlane, ground)
        assertFalse(aa in supporters, "an AA that already intercepted this turn cannot also air-defend")
    }

    @Test
    fun absentEquipCfgStillIntercepts() {
        // KAISER-shaped case: no equip.cfg at all -- mode defaults to 0 (still intercepts), flak_range to 1.
        EfileConfig.setForTest()
        val plane = plane(5, 5)
        val aa = flak(5, 6, spotted = false)
        val cell = Cell(5, 6)

        val interceptors = AAInterception.interceptorsFor(map, plane, cell, isDestination = false)

        assertTrue(aa in interceptors, "mode 0 (the absent-config default) still runs hidden interception")
    }

    // --- End-to-end MoveExecutor tests: the mid-path destruction / undo cases ---

    @Test
    fun aPlaneDestroyedMidPathIsNotPlacedOnTheDestination() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        val plane = plane(5, 5).apply { strength = 1 }
        flak(5, 6, spotted = false)
        map.setMoveRange(plane)

        val result = map.moveUnit(plane, 5, 6)

        assertTrue(plane.destroyed, "a hidden flak with airatk 8 must destroy a 1-strength plane")
        assertTrue(result.wasIntercepted)
        assertNull(
            map.map
                ?.get(5)
                ?.get(6)
                ?.airunit,
            "the destination hex must not hold a ghost",
        )
        assertNull(
            map.map
                ?.get(5)
                ?.get(5)
                ?.airunit,
            "the origin hex must not still show the destroyed plane either",
        )
    }

    @Test
    fun anInterceptedMoveCannotBeUndone() {
        EfileConfig.setForTest(mapOf("g2a_intercept_mode" to 0))
        val plane = plane(5, 5)
        flak(5, 6, spotted = false)
        map.setMoveRange(plane)

        map.moveUnit(plane, 5, 6)

        assertFalse(map.canUndoMove(plane), "an intercepted move must never be undoable")
    }
}
