package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addDestroyedUnitToDossier
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.initDossier
import org.osada.model.resetEquipment
import org.osada.model.surrenderUnit
import org.osada.rules.CombatPositioning
import org.osada.rules.CombatResolver
import org.osada.rules.HexGeometry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Surrender-on-failed-retreat (SURRENDER_ON_FAILED_RETREAT), a deliberate divergence from
 * PM 3.2.14, which left a unit that had to retreat but could not in place, unharmed.
 *
 * Covers the rules decision ([CombatResolver.shouldDefenderSurrender]), the precondition that
 * makes it reachable ([CombatPositioning.getRetreatPosition] returning null), and the state
 * mutation (`GameMap.surrenderUnit`).
 */
class SurrenderTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()

        // Foot infantry: LEG movement, so ocean/impassable hexes are illegal retreat targets.
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                gunrange = 1
                cost = 10
                initiative = 5
                spotrange = 2
                hardatk = 2
                softatk = 8
                uclass = UnitClass.INFANTRY.value
                airdef = 2
                fuel = 0
                rangedefmod = 4
                airatk = 0
                groundweight = 1
                movmethod = MovMethod.LEG.value
                movpoints = 3
                grounddef = 6
                target = UnitType.SOFT.value
                closedef = 10
                ammo = 10
                attr = 0
            },
        )
    }

    /** 3x3 map; the defender sits at (1,1) and every surrounding hex uses [surroundTerrain]. */
    private fun buildMap(surroundTerrain: Int): Triple<GameMap, GameUnit, GameUnit> {
        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
            }
        val atkPlayer =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        val defPlayer =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(atkPlayer)
        map.addPlayer(defPlayer)

        for (r in 0 until 3) {
            for (c in 0 until 3) {
                map.map?.get(r)?.get(c)?.apply {
                    terrain = surroundTerrain
                    road = RoadType.NONE.value
                }
            }
        }
        map.map
            ?.get(1)
            ?.get(1)
            ?.terrain = TerrainType.CLEAR.value

        val attacker =
            GameUnit(1).apply {
                owner = atkPlayer.id
                player = atkPlayer
                strength = 10
                ammo = 10
            }
        val defender =
            GameUnit(1).apply {
                owner = defPlayer.id
                player = defPlayer
                strength = 4
                ammo = 10
            }
        map.map
            ?.get(1)
            ?.get(1)
            ?.setUnit(defender)
        map.addUnit(defender)
        map.addUnit(attacker)
        return Triple(map, attacker, defender)
    }

    @Test
    fun plainUnitSurrenders() {
        val (_, _, defender) = buildMap(TerrainType.OCEAN.value)
        assertTrue(
            CombatResolver.shouldDefenderSurrender(defender),
            "a unit with no protecting leader should surrender when it cannot retreat",
        )
    }

    @Test
    fun ferociousDefenseExemptsFromSurrender() {
        val (_, _, defender) = buildMap(TerrainType.OCEAN.value)
        defender.leader = LeaderType.FEROCIOUS_DEFENSE.value
        assertFalse(
            CombatResolver.shouldDefenderSurrender(defender),
            "Ferocious Defense should exempt the unit, mirroring OG",
        )
    }

    /**
     * OG's OTHER exemption, named in [SURRENDER_ON_FAILED_RETREAT]'s own comment from the start but
     * unimplemented until the `attr` bit was identified (bit 23, 2026-07-27 — see
     * `EquipmentCombatEligibility.ATTR_MASK_NO_SURRENDER`).
     *
     * This is the case that actually mattered: 56% of the Fortification class carries the flag, and
     * a bunker has `movpoints == 0`, so it can never complete a legal retreat — every forced retreat
     * was an automatic destruction.
     */
    @Test
    fun noSurrenderAttributeExemptsFromSurrender() {
        val bunkerEqid = 2
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                uclass = UnitClass.FORTIFICATION.value
                movmethod = MovMethod.LEG.value
                movpoints = 0
                target = UnitType.SOFT.value
                ammo = 10
                attr = NO_SURRENDER_ATTR
            },
        )
        val (_, _, defender) = buildMap(TerrainType.OCEAN.value)
        defender.eqid = bunkerEqid

        assertFalse(
            CombatResolver.shouldDefenderSurrender(defender),
            "an OG unit flagged No Surrender is never destroyed-as-surrendered",
        )
    }

    /** Control: the same immobile fortification WITHOUT the bit still surrenders, so the exemption
     *  is the attribute and not the class or the zero move allowance. */
    @Test
    fun anImmobileFortificationWithoutTheAttributeStillSurrenders() {
        val plainFortEqid = 3
        Equipment.putEquipment(
            plainFortEqid,
            EquipmentData().apply {
                uclass = UnitClass.FORTIFICATION.value
                movmethod = MovMethod.LEG.value
                movpoints = 0
                target = UnitType.SOFT.value
                ammo = 10
                attr = 0
            },
        )
        val (_, _, defender) = buildMap(TerrainType.OCEAN.value)
        defender.eqid = plainFortEqid

        assertTrue(CombatResolver.shouldDefenderSurrender(defender))
    }

    /** The precondition: a leg unit ringed by ocean has no legal retreat hex. */
    @Test
    fun encircledByImpassableTerrainHasNoRetreatPosition() {
        val (map, _, defender) = buildMap(TerrainType.OCEAN.value)
        assertNull(
            CombatPositioning.getRetreatPosition(map.map, defender, map.rows),
            "ocean is impassable to LEG movement, so no retreat hex should be found",
        )
    }

    /** Control: on open ground the same unit retreats instead of surrendering. */
    @Test
    fun openGroundStillYieldsARetreatPosition() {
        val (map, _, defender) = buildMap(TerrainType.CLEAR.value)
        assertNotNull(
            CombatPositioning.getRetreatPosition(map.map, defender, map.rows),
            "a unit on open ground must retreat rather than surrender",
        )
    }

    /**
     * THE ordering guarantee: surrender is a consequence of an owed retreat, never of geography
     * alone. A unit ringed by impassable terrain that is NOT being forced to retreat must survive.
     *
     * `shouldDefenderRetreat` is the gate `applyDefenderRetreat` checks before it ever looks for a
     * retreat hex, so an untouched full-strength defender can sit encircled indefinitely.
     */
    @Test
    fun encircledUnitDoesNotSurrenderWithoutAForcedRetreat() {
        val (_, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        defender.strength = 10
        assertFalse(
            CombatResolver.shouldDefenderRetreat(attacker, defender, 10),
            "an undamaged defender is not forced to retreat, so surrender must never be reached",
        )
    }

    /**
     * Being pinned by terrain / the map edge is encirclement and must kill the unit — this is the
     * case the rule exists for, and it needs no enemy adjacency at all.
     */
    @Test
    fun terrainAndMapEdgeCountAsEncirclement() {
        val (map, _, defender) = buildMap(TerrainType.OCEAN.value)
        assertFalse(
            CombatPositioning.isRetreatBlockedByOwnUnitsOnly(map.map, defender, map.rows),
            "water/edge is not a friendly traffic-jam",
        )
        assertTrue(
            CombatResolver.shouldDefenderSurrender(defender, blockedByOwnUnitsOnly = false),
            "a unit pinned by water and the map edge surrenders",
        )
    }

    /** A unit merely crowded out by its OWN side is jammed, not encircled, and must survive. */
    @Test
    fun ownUnitsBlockingTheRetreatDoNotCauseSurrender() {
        val (map, _, defender) = buildMap(TerrainType.CLEAR.value)
        val defPlayer = defender.player ?: error("defender player missing")
        // Ring the defender at (1,1) with its own units, leaving no free hex.
        HexGeometry.getAdjacent(1, 1).forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            val friend =
                GameUnit(1).apply {
                    owner = defPlayer.id
                    player = defPlayer
                    strength = 10
                }
            hex.setUnit(friend)
            map.addUnit(friend)
        }

        assertNull(
            CombatPositioning.getRetreatPosition(map.map, defender, map.rows),
            "precondition: every adjacent hex is occupied, so no retreat hex exists",
        )
        assertTrue(
            CombatPositioning.isRetreatBlockedByOwnUnitsOnly(map.map, defender, map.rows),
            "the block is caused by friendly units",
        )
        assertFalse(
            CombatResolver.shouldDefenderSurrender(defender, blockedByOwnUnitsOnly = true),
            "a unit boxed in by its own side must not surrender",
        )
    }

    @Test
    fun surrenderMarksTheUnitAsSurrenderedNotMerelyDestroyed() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)

        map.surrenderUnit(defender, attacker)

        assertTrue(defender.destroyed, "a surrendered unit is also destroyed")
        assertTrue(defender.surrendered, "surrender must stay distinguishable from an ordinary kill")
    }

    @Test
    fun surrenderDestroysUnitAndRemovesItFromTheMap() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        assertTrue(map.units.contains(defender), "precondition: defender is on the map")

        map.surrenderUnit(defender, attacker)

        assertEquals(0, defender.strength, "surrendered unit loses all remaining strength")
        assertTrue(defender.destroyed, "surrendered unit is destroyed")
        assertFalse(map.units.contains(defender), "surrendered unit is swept from the unit list")
        assertNull(
            map.map
                ?.get(1)
                ?.get(1)
                ?.unit,
            "surrendered unit is cleared from its hex",
        )
    }

    @Test
    fun surrenderCreditsTheCaptorWithTheRemainingStrength() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        val before = attacker.player?.score ?: 0

        map.surrenderUnit(defender, attacker)

        assertTrue(
            (attacker.player?.score ?: 0) > before,
            "the captor's side should be credited for the surrendered strength",
        )
    }

    /** Guards against double-scoring if the unit was already destroyed by damage. */
    @Test
    fun surrenderIsANoOpForAnAlreadyDeadUnit() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        defender.strength = 0
        val before = attacker.player?.score ?: 0
        val beforePrestige = attacker.player?.prestige ?: 0

        val awarded = map.surrenderUnit(defender, attacker)

        assertEquals(0, awarded, "an already-dead unit surrenders nothing")
        assertEquals(before, attacker.player?.score ?: 0, "no score should be awarded twice")
        assertEquals(beforePrestige, attacker.player?.prestige ?: 0, "no prestige should be awarded twice")
    }

    /**
     * PC2's economic payoff: the captor banks the value of the strength still standing, which is
     * what makes encircling worth more than shelling the unit to death.
     */
    @Test
    fun surrenderAwardsPrestigeForTheSurvivingStrength() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        val before = attacker.player?.prestige ?: 0

        val awarded = map.surrenderUnit(defender, attacker)

        assertTrue(awarded > 0, "surrender must pay the captor for the captured strength")
        assertEquals(
            before + awarded,
            attacker.player?.prestige ?: 0,
            "the awarded prestige must actually reach the captor",
        )
    }

    /** Surrendered units are a SUBSET of killed, so the AAR can split "Destroyed / Surrendered". */
    @Test
    fun surrenderIsTalliedSeparatelyInTheDossier() {
        val (map, attacker, defender) = buildMap(TerrainType.OCEAN.value)
        val captor = attacker.player ?: error("captor player missing")
        captor.initDossier()
        val key = defender.unitData().uclass.toString()

        map.surrenderUnit(defender, attacker)
        captor.addDestroyedUnitToDossier(defender)

        assertEquals(1, captor.dossier.units.killed[key] as? Int ?: 0, "surrender still counts as a kill")
        assertEquals(1, captor.dossier.units.captured[key] as? Int ?: 0, "and is also counted as captured")
    }

    private companion object {
        /** `Equipment.attr` bit 23 — OG's `No Surrender`. Mirrors the private mask in
         *  `EquipmentCombatEligibility`; identified from BASEKORP's `Fort` (`E 335`). */
        const val NO_SURRENDER_ATTR = 8388608
    }
}
