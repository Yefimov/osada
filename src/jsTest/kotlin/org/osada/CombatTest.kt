package org.osada

import org.osada.model.CombatResults
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.move
import org.osada.model.resetEquipment
import org.osada.rules.CombatResolver
import org.osada.rules.HexGeometry
import org.osada.rules.SupplyRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for combat calculation using mock equipment data.
 * Guards against regressions in CombatResolver.calculateAttackResults.
 */
class CombatTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()

        // Infantry: soft target, ground unit
        val infantry =
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
            }
        Equipment.putEquipment(1, infantry)

        // Tank: hard target, ground unit
        val tank =
            EquipmentData().apply {
                gunrange = 1
                cost = 20
                initiative = 6
                spotrange = 2
                hardatk = 12
                softatk = 4
                uclass = UnitClass.TANK.value
                airdef = 2
                fuel = 30
                rangedefmod = 4
                airatk = 0
                groundweight = 4
                movmethod = MovMethod.TRACKED.value
                movpoints = 5
                grounddef = 8
                target = UnitType.HARD.value
                closedef = 6
                ammo = 10
                attr = 0
            }
        Equipment.putEquipment(2, tank)
    }

    @Test
    fun attackResultsAreNonNegative() {
        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
            }

        val attacker =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        val defender =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(attacker)
        map.addPlayer(defender)

        val infantry =
            GameUnit(1).apply {
                owner = attacker.id
                player = attacker
                strength = 10
                experience = 0
                ammo = 10
            }
        val tankUnit =
            GameUnit(2).apply {
                owner = defender.id
                player = defender
                strength = 10
                experience = 0
                ammo = 10
            }

        map.map
            ?.get(1)
            ?.get(1)
            ?.setUnit(infantry)
        map.map
            ?.get(1)
            ?.get(2)
            ?.setUnit(tankUnit)
        map.addUnit(infantry)
        map.addUnit(tankUnit)

        val result = CombatResolver.calculateAttackResults(infantry, tankUnit, true)
        assertTrue(result.kills >= 0, "kills should be non-negative")
        assertTrue(result.losses >= 0, "losses should be non-negative")
        // Tank attacking infantry back should be possible
        assertEquals(true, result.defcanfire, "defender should fire back at range 1")
    }

    @Test
    fun distanceIsOneForAdjacentHexes() {
        assertEquals(1, HexGeometry.distance(0, 0, 0, 1))
        assertEquals(1, HexGeometry.distance(1, 1, 0, 1))
    }

    /**
     * Builds two adjacent units on clear terrain and returns the combat result.
     * [attackerEqid]/[defenderEqid] index the mock equipment from [setup].
     */
    private fun attack(
        attackerEqid: Int,
        defenderEqid: Int,
    ): CombatResults {
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

        val attacker =
            GameUnit(attackerEqid).apply {
                owner = atkPlayer.id
                player = atkPlayer
                strength = 10
                experience = 0
                ammo = 10
                entrenchment = 0
                isSurprised = false
                hasMoved = false
            }
        val defender =
            GameUnit(defenderEqid).apply {
                owner = defPlayer.id
                player = defPlayer
                strength = 10
                experience = 0
                ammo = 10
                entrenchment = 0
                isSurprised = false
                hasMoved = false
            }

        // Adjacent hexes on clear terrain, no road.
        map.map?.get(1)?.get(1)?.apply {
            terrain = TerrainType.CLEAR.value
            road = RoadType.NONE.value
            setUnit(attacker)
        }
        map.map?.get(1)?.get(2)?.apply {
            terrain = TerrainType.CLEAR.value
            road = RoadType.NONE.value
            setUnit(defender)
        }
        map.addUnit(attacker)
        map.addUnit(defender)

        return CombatResolver.calculateAttackResults(attacker, defender, true)
    }

    /**
     * Exact-value check of the deterministic (expected-value) combat path against
     * the JS reference. Infantry (soft, softatk 8 / hardatk 2 / grounddef 6 / ini 5)
     * attacks a tank (hard, hardatk 12 / softatk 4 / grounddef 8 / ini 6), adjacent
     * on clear terrain, both strength 10, no experience/entrenchment/leaders.
     *
     * Cross-indexed stats: attacker uses hardatk (vs tank's HARD target) = 2,
     * defender's counter uses softatk (vs infantry's SOFT target) = 4. After range
     * and initiative modifiers: kills=1, losses=2.
     */
    @Test
    fun infantryAttacksTankExactValues() {
        val r = attack(attackerEqid = 1, defenderEqid = 2)
        assertEquals(1, r.kills, "infantry->tank kills")
        assertEquals(2, r.losses, "infantry->tank losses")
        assertEquals(true, r.defcanfire, "tank fires back at range 1")
        assertEquals(25, r.atkExpGained, "attacker experience gained")
        assertEquals(22, r.defExpGained, "defender experience gained")
    }

    /**
     * The mirror engagement: tank attacks infantry. Attacker uses softatk (vs
     * infantry's SOFT target) = 4; defender's counter uses hardatk (vs tank's HARD
     * target) = 2. Result is the symmetric opposite: kills=2, losses=1.
     */
    @Test
    fun tankAttacksInfantryExactValues() {
        val r = attack(attackerEqid = 2, defenderEqid = 1)
        assertEquals(2, r.kills, "tank->infantry kills")
        assertEquals(1, r.losses, "tank->infantry losses")
        assertEquals(true, r.defcanfire, "infantry fires back at range 1")
        assertEquals(22, r.atkExpGained, "attacker experience gained")
        assertEquals(25, r.defExpGained, "defender experience gained")
    }

    private fun makeMap(): Triple<GameMap, Player, Player> {
        val map =
            GameMap().apply {
                rows = 5
                cols = 5
                allocMap()
            }
        val atk =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        val def =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(atk)
        map.addPlayer(def)
        return Triple(map, atk, def)
    }

    private fun place(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
        terrain: Int,
    ) {
        map.map?.get(row)?.get(col)?.apply {
            this.terrain = terrain
            road = RoadType.NONE.value
            setUnit(unit)
        }
        map.addUnit(unit)
    }

    private fun unit(
        eqid: Int,
        player: Player,
    ): GameUnit =
        GameUnit(eqid).apply {
            owner = player.id
            this.player = player
            strength = 10
            experience = 0
            ammo = 10
            entrenchment = 0
            isSurprised = false
            hasMoved = false
        }

    /**
     * Infantry attacks infantry in a city (close-combat terrain). Both sides use
     * their close-combat defence value (the attacker's was wrongly assigned to its
     * *attack* before the fix). Expected: kills=1, losses=1.
     */
    @Test
    fun infantryVsInfantryCloseCombatUsesCloseDefense() {
        val (map, atk, def) = makeMap()
        val a = unit(1, atk)
        val d = unit(1, def)
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 1, 2, TerrainType.CITY.value)
        val r = CombatResolver.calculateAttackResults(a, d, true)
        assertEquals(1, r.kills, "inf->inf(city) kills")
        assertEquals(1, r.losses, "inf->inf(city) losses")
        assertEquals(true, r.defcanfire)
    }

    /** A defender beyond range 1 cannot fire back, so the attacker takes no losses. */
    @Test
    fun defenderCannotFireBackBeyondRangeOne() {
        val (map, atk, def) = makeMap()
        val a = unit(1, atk)
        val d = unit(1, def)
        assertEquals(2, HexGeometry.distance(1, 1, 3, 1), "sanity: positions are distance 2")
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 3, 1, TerrainType.CLEAR.value)
        val r = CombatResolver.calculateAttackResults(a, d, true)
        assertEquals(false, r.defcanfire, "no return fire beyond range 1")
        assertEquals(0, r.losses, "attacker takes no losses when defender can't fire")
    }

    /** An entrenched defender raises its defence, so it should take no more kills. */
    @Test
    fun entrenchmentReducesIncomingKills() {
        fun kills(entrench: Int): Int {
            val (map, atk, def) = makeMap()
            val a = unit(2, atk) // tank
            val d = unit(1, def).apply { entrenchment = entrench } // infantry
            place(map, a, 1, 1, TerrainType.CLEAR.value)
            place(map, d, 1, 2, TerrainType.CLEAR.value)
            return CombatResolver.calculateAttackResults(a, d, true).kills
        }
        assertTrue(kills(5) <= kills(0), "entrenched defender takes <= kills")
    }

    /** A surprised attacker has zero defence and halved attack: fewer kills, more losses. */
    @Test
    fun surprisedAttackerTradesKillsForLosses() {
        fun result(surprised: Boolean): CombatResults {
            val (map, atk, def) = makeMap()
            val a = unit(1, atk).apply { isSurprised = surprised }
            val d = unit(2, def)
            place(map, a, 1, 1, TerrainType.CLEAR.value)
            place(map, d, 1, 2, TerrainType.CLEAR.value)
            return CombatResolver.calculateAttackResults(a, d, true)
        }
        val normal = result(false)
        val surprised = result(true)
        assertTrue(surprised.kills <= normal.kills, "surprised attacker deals <= kills")
        assertTrue(surprised.losses >= normal.losses, "surprised attacker takes >= losses")
    }

    /**
     * Locks JS half-up rounding (`Math.round`): net attack -2 gives q=4 and a raw
     * damage of (5*4*10+50)/100 = 2.5, which must round to 3, not 2 (banker's).
     */
    @Test
    fun attackValueRoundsHalfUp() {
        val a = GameUnit(1).apply { strength = 10 } // infantry: not artillery/bomber/fortification/naval
        val d = GameUnit(1).apply { strength = 10 }
        assertEquals(3, CombatResolver.attackValue(8, 10, a, d, true), "2.5 must round up to 3")
    }

    /**
     * Moving through enemy ZOC has terrain cost 254; the real cost is
     * floor(254/254 + 254%254) = 1 (JS `>> 0` truncation). The old `shr 1` halved
     * it to 0, so no fuel/movement was consumed.
     */
    @Test
    fun moveThroughZocConsumesFullCost() {
        val tank =
            GameUnit(2).apply {
                fuel = 30
                moveLeft = 5
            }
        tank.move(254)
        assertEquals(29, tank.fuel, "ZOC move deducts 1 fuel, not 0")
    }

    /** Over-strength reinforcement requires a unit already at full strength (10). */
    @Test
    fun overstrengthReinforceRequiresFullStrength() {
        val (mapWeak, atkW, _) = makeMap()
        val weak =
            unit(1, atkW).apply {
                strength = 8
                experience = 200
            }
        place(mapWeak, weak, 1, 1, TerrainType.CLEAR.value)
        assertEquals(
            false,
            SupplyRules.canReinforce(mapWeak, weak, overStrength = true),
            "below full strength cannot be over-strength reinforced",
        )

        val (mapFull, atkF, _) = makeMap()
        val full =
            unit(1, atkF).apply {
                strength = 10
                experience = 200
            }
        place(mapFull, full, 1, 1, TerrainType.CLEAR.value)
        assertEquals(
            true,
            SupplyRules.canReinforce(mapFull, full, overStrength = true),
            "full-strength veteran can be over-strength reinforced",
        )
    }
}
