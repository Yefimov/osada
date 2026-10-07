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
import org.osada.rules.AttackCalculation
import org.osada.rules.CombatResolver
import org.osada.rules.HexGeometry
import org.osada.rules.RuggedCombat
import org.osada.rules.SupplyRules
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
     * [attackerEqid]/[defenderEqid] index the mock equipment from [setup]. [defenderTerrain]
     * defaults to clear; pass a different [TerrainType] to exercise terrain-dependent rules.
     */
    private fun attack(
        attackerEqid: Int,
        defenderEqid: Int,
        defenderTerrain: Int = TerrainType.CLEAR.value,
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
            terrain = defenderTerrain
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

    /**
     * Same infantry(ini 5)-attacks-tank(ini 6) engagement as [infantryAttacksTankExactValues], but
     * the defender's hex is SWAMP (`Constants.terrainInitiative[8] == 2`) instead of clear, which
     * isolates the initiative cap (`docs/design/efile-driven-rules.md` §2; `docs/design/efile-driven-rules.md` §2
     * §4.2 caps by the DEFENDER's hex).
     *
     * This used to be MOUNTAIN. Since 2026-09-26 infantry attacking into mountain is OG's close
     * combat, which is Rugged and skips initiative altogether (`RuggedCombat`), so mountain no
     * longer tests the cap. Swamp has a cap and no close combat.
     *
     * Hand-computed. Base (cross-indexed): attacker 2 hard attack / 6 ground defence, tank 4 soft
     * attack / 8 ground defence; range defence at distance 1 adds half of each side's 4 -> 8 and 10.
     * Clear: 5 vs 6 initiative, the tank wins by 1 -> its defence 14, attack 5; kills = EV(2-14) = 1,
     * losses = EV(5-8) = q 3 -> 2. Swamp: both capped to 2, a tie goes to the attacker -> its defence
     * 12, attack +0; kills = EV(2-10) = 1, losses = EV(4-12) = 1.
     */
    @Test
    fun initiativeCapOnDefendersSwampTerrainFlipsTheInitiativeSwing() {
        val clear = attack(attackerEqid = 1, defenderEqid = 2)
        val swamp = attack(attackerEqid = 1, defenderEqid = 2, defenderTerrain = TerrainType.SWAMP.value)

        assertEquals(1, clear.kills, "clear-terrain baseline: infantry->tank kills")
        assertEquals(2, clear.losses, "clear-terrain baseline: infantry->tank losses")
        assertEquals(1, swamp.kills, "the attacker's attack is at the floor either way")
        assertEquals(1, swamp.losses, "capped initiative removes the tank's uncapped counter-bonus")
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
     * Infantry attacks infantry in a city: OG's close combat, and therefore Rugged
     * (`RuggedCombat`, 2026-09-26). Hand-computed: both sides defend on close defence (10); the
     * defender gets the Rugged +4/+4 and the city's +4 defence -> attack 12, defence 18; no range
     * defence, no initiative. The defender fires FIRST: EV(12-10) = q 8 -> 4.5 -> 5 losses. The
     * attacker answers with the 5 points it has left: EV(8-18) = q 1 -> 0.75 -> 1 kill. (Under Panzer
     * Marshal's simultaneous exchange this was 1 and 1.)
     */
    @Test
    fun infantryVsInfantryCloseCombatUsesCloseDefense() {
        val (map, atk, def) = makeMap()
        val a = unit(1, atk)
        val d = unit(1, def)
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 1, 2, TerrainType.CITY.value)
        val r = CombatResolver.calculateAttackResults(a, d, true)
        assertEquals(1, r.kills, "inf->inf(city) kills, fired with the 5 points left")
        assertEquals(5, r.losses, "inf->inf(city) losses, taken first")
        assertEquals(true, r.defcanfire)
    }

    /** OG's chance: 5% per entrenchment level, doubled by a Tenacious defender, halved by a
     *  Tenacious attacker -- the formula's TrenchRate cancels out. */
    @Test
    fun ruggedDefenceChanceIsFivePercentPerEntrenchmentLevel() {
        val (_, atk, def) = makeMap()
        val a = unit(1, atk)
        val d = unit(1, def).apply { entrenchment = 4 }
        assertEquals(20.0, RuggedCombat.ruggedDefenceChance(a, d))
        d.leader = LeaderType.TENACIOUS_DEFENSE.value
        assertEquals(40.0, RuggedCombat.ruggedDefenceChance(a, d))
        a.leader = LeaderType.TENACIOUS_DEFENSE.value
        assertEquals(20.0, RuggedCombat.ruggedDefenceChance(a, d))
    }

    /** OG raises a rugged defence only between two infantry formations, never on a forecast (the
     *  roll draws from the shared stream), and never against an `Infiltration Tactics` attacker.
     *  Entrenchment 20 makes the chance 100%, so a committed infantry-vs-infantry attack must raise it. */
    @Test
    fun ruggedDefenceIsRolledOnlyBetweenInfantryAndOnlyWhenCommitted() {
        fun raised(
            attackerEqid: Int,
            committed: Boolean,
            leader: Int = -1,
        ): Boolean {
            val (map, atk, def) = makeMap()
            val a = unit(attackerEqid, atk).apply { this.leader = leader }
            val d = unit(1, def).apply { entrenchment = 20 }
            place(map, a, 1, 1, TerrainType.CLEAR.value)
            place(map, d, 1, 2, TerrainType.CLEAR.value)
            val context = AttackCalculation.resolveCombatContext(a, d)!!
            return RuggedCombat.ruggedDefenceRaised(a, d, context, committed)
        }
        assertTrue(raised(attackerEqid = 1, committed = true), "infantry vs entrenched infantry, 100%")
        assertFalse(raised(attackerEqid = 1, committed = false), "a forecast never rolls")
        assertFalse(raised(attackerEqid = 2, committed = true), "a tank never meets a rugged defence")
        assertFalse(
            raised(attackerEqid = 1, committed = true, leader = LeaderType.INFILTRATION_TACTICS.value),
            "Infiltration Tactics avoids it",
        )
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

    /** A surprised attacker fights OG's Rugged combat -- the defender gets +4/+4 and fires first --
     *  so it deals fewer kills and takes more losses than the same attack made in the open. */
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
     * OG's Rugged rows, hand-computed from the author's combat page for infantry (eqid 1) attacking
     * a tank (eqid 2) in the open. Base stats, cross-indexed: attacker 2 hard attack / 6 ground
     * defence, defender 4 soft attack / 8 ground defence. Then: the attacker is infantry, so the
     * tank defends on its close defence (6); the tank is not infantry, so the attacker gets +4
     * attack; the defender gets its flat +4/+4. Expected attacker 6/6, defender 8/10. Panzer
     * Marshal's rule, which this replaced, would have left the attacker at 1 attack and 0 defence.
     */
    @Test
    fun ruggedCombatGivesTheDefenderTheEdgeWhenInfantryAttacksATank() {
        val (map, atk, def) = makeMap()
        val a = unit(1, atk)
        val d = unit(2, def)
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 1, 2, TerrainType.CLEAR.value)
        val context = AttackCalculation.resolveCombatContext(a, d)!!
        val stats = AttackCalculation.resolveCrossIndexedStats(context)

        RuggedCombat.applyRuggedCombat(stats, context, closeCombat = false)

        assertEquals(6, stats.attackerAttack, "2 hard attack + 4 against a non-infantry target")
        assertEquals(6, stats.attackerDefense, "the defender is not infantry: ground defence stands")
        assertEquals(8, stats.defenderAttack, "4 soft attack + the flat Rugged 4")
        assertEquals(10, stats.defenderDefense, "close defence 6 against infantry + the flat Rugged 4")
    }

    /**
     * The mirror case: a tank attacking infantry. The attacker now defends on its close defence
     * (6, against infantry); the defender gets +4 for facing a non-infantry attacker and the flat
     * +4/+4 on top. Expected attacker 4/6, defender 10/10.
     */
    @Test
    fun ruggedCombatWhenATankAttacksInfantry() {
        val (map, atk, def) = makeMap()
        val a = unit(2, atk)
        val d = unit(1, def)
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 1, 2, TerrainType.CLEAR.value)
        val context = AttackCalculation.resolveCombatContext(a, d)!!
        val stats = AttackCalculation.resolveCrossIndexedStats(context)

        RuggedCombat.applyRuggedCombat(stats, context, closeCombat = false)

        assertEquals(4, stats.attackerAttack, "soft attack, no bonus against infantry")
        assertEquals(6, stats.attackerDefense, "close defence against infantry")
        assertEquals(10, stats.defenderAttack, "2 hard attack + 4 (non-infantry attacker) + 4")
        assertEquals(10, stats.defenderDefense, "6 ground defence + 4")
    }

    /** OG's DEFENDER-FIRST sequence: the attacker answers only with the strength it has left, and
     *  a formation with nothing left does not fire at all. */
    @Test
    fun strengthLostEarlierInTheExchangeIsNotFired() {
        val (map, atk, def) = makeMap()
        val a = unit(2, atk)
        val d = unit(1, def)
        place(map, a, 1, 1, TerrainType.CLEAR.value)
        place(map, d, 1, 2, TerrainType.CLEAR.value)

        val full = CombatResolver.attackValue(14, 6, a, d, true)
        val halfLeft = CombatResolver.attackValue(14, 6, a, d, true, strengthLost = 5)
        val nothingLeft = CombatResolver.attackValue(14, 6, a, d, true, strengthLost = 10)

        assertTrue(halfLeft < full, "five strength points lost first means fewer kills")
        assertEquals(0, nothingLeft, "a unit shot to pieces before it fires inflicts nothing")
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
