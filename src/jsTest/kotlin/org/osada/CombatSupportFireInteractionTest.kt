package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.rules.CombatResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two behavioural divergences `DEFERRED.md` §4.6 left open after §7.32 fixed the *sourcing* of
 * Combat Support. Both are about the interaction between Combat Support (an adjacent HQ lending
 * experience bars) and Fire Support (an adjacent unit shooting on someone else's behalf), which the
 * entry could only describe as "verify against `calculateAttackResults` before claiming either way".
 *
 * Verified here, and they turned out to be one real defect and one non-issue:
 *
 *  1. **`OG` applies the bars to attack as well as defence, "attack only when the recipient can
 *     fire" — already satisfied, no code needed.** We add the bars to all four stats, but
 *     `defenderAttack` is *consumed* only behind `if (result.defcanfire)` in
 *     `CombatResolver.calculateAttackResults`, so a defender that cannot return fire never spends
 *     them. [aDefenderThatCannotFireNeverSpendsItsCombatSupport] pins that, because the reason it is
 *     safe is a consumption-site guard rather than anything local to the support code — a future
 *     refactor that reads `defenderAttack` unconditionally would silently reintroduce the divergence.
 *  2. **A unit performing Fire Support must not itself receive Combat Support for that exchange —
 *     this was real and is now fixed** via `calculateAttackResults(attackerIsFiringSupport = true)`.
 *
 * TRAP, for anyone extending this file: in [CombatResolver.attackValue] `useRandom = true` selects the
 * **deterministic** expected-value branch and `false` rolls dice. The flag reads backwards, so a test
 * written with `false` is both flaky and too coarse to see a four-point stat delta — which is exactly
 * how the first draft of these tests produced a passing equivalence that proved nothing.
 */
class CombatSupportFireInteractionTest {
    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(EQ_INFANTRY, infantryLike("Rifle Company"))
        Equipment.putEquipment(EQ_ARTILLERY, artillery())
        // Combat Support comes from `attr` bit 16, never the name (§7.32 item 2e).
        Equipment.putEquipment(EQ_HQ, infantryLike("04 General Staff").apply { attr = COMBAT_SUPPORT_ATTR })
    }

    /**
     * Divergence 2. An artillery piece fires in support of an adjacent friendly defender while a
     * Combat Support HQ sits next to the artillery. OG gives the artillery nothing for that shot.
     *
     * Asserted as an equivalence rather than a magic number: the support shot with an HQ adjacent
     * must land exactly as hard as the same shot with no HQ on the map at all. The control below it
     * proves the HQ *would* have mattered, so the equivalence cannot pass by accident.
     */
    @Test
    fun aFireSupportShotDoesNotCollectCombatSupportForTheSupporter() {
        val withHq = supportShot(hqAdjacentToSupport = true, firingSupport = true)
        val withoutHq = supportShot(hqAdjacentToSupport = false, firingSupport = true)

        assertEquals(
            withoutHq.kills,
            withHq.kills,
            "an HQ beside a Fire Support unit must add nothing to the support shot",
        )
    }

    /** Control for the test above: the same HQ, the same artillery, but resolving an ordinary attack
     *  of its own. Here the bars DO count, which is what makes the equivalence above meaningful. */
    @Test
    fun theSameHeadquartersDoesLiftTheArtillerysOwnAttack() {
        val withHq = supportShot(hqAdjacentToSupport = true, firingSupport = false)
        val withoutHq = supportShot(hqAdjacentToSupport = false, firingSupport = false)

        assertTrue(
            withHq.kills > withoutHq.kills,
            "on its own attack the artillery keeps its Combat Support (got ${withHq.kills} vs ${withoutHq.kills})",
        )
    }

    /**
     * Divergence 1. A defender out of its own reach cannot return fire, so whatever Combat Support
     * lends its attack stat must never reach a result. Artillery shoots at range 3; the infantry
     * defender has `gunrange` 1 and is flanked by an HQ, and still inflicts nothing.
     */
    @Test
    fun aDefenderThatCannotFireNeverSpendsItsCombatSupport() {
        val (map, mine, theirs) = board()
        val artillery = place(map, unit(EQ_ARTILLERY, mine), 1, 1)
        val defender = place(map, unit(EQ_INFANTRY, theirs), 1, 4)
        val hq = place(map, unit(EQ_HQ, theirs, experience = 400), 1, 5)
        val units = listOf(artillery, defender, hq)

        val result = CombatResolver.calculateAttackResults(artillery, defender, useRandom = true, units = units)

        assertEquals(false, result.defcanfire, "gunrange 1 cannot answer a shot from three hexes away")
        assertEquals(0, result.losses, "and so its Combat Support buys it no retaliation")
    }

    // ---- fixture ----

    /** Resolves the artillery's shot at the enemy [attacker] position, optionally with a Combat
     *  Support HQ adjacent to the artillery, and optionally marked as a Fire Support exchange. */
    private fun supportShot(
        hqAdjacentToSupport: Boolean,
        firingSupport: Boolean,
    ): org.osada.model.CombatResults {
        val (map, mine, theirs) = board()
        val artillery = place(map, unit(EQ_ARTILLERY, mine), 1, 1)
        val target = place(map, unit(EQ_INFANTRY, theirs), 1, 2)
        val units = mutableListOf(artillery, target)
        if (hqAdjacentToSupport) {
            units += place(map, unit(EQ_HQ, mine, experience = 400), 0, 1)
        }
        return CombatResolver.calculateAttackResults(
            artillery,
            target,
            useRandom = true,
            units = units,
            attackerIsFiringSupport = firingSupport,
        )
    }

    private fun board(): Triple<GameMap, Player, Player> {
        val map =
            GameMap().apply {
                rows = 6
                cols = 6
                allocMap()
            }
        val mine =
            Player().apply {
                id = 0
                side = 0
                country = 0
            }
        val theirs =
            Player().apply {
                id = 1
                side = 1
                country = 1
            }
        map.addPlayer(mine)
        map.addPlayer(theirs)
        for (row in 0 until map.rows) {
            for (col in 0 until map.cols) {
                map.map
                    ?.get(row)
                    ?.get(col)
                    ?.apply {
                        terrain = TerrainType.CLEAR.value
                        road = RoadType.NONE.value
                    }
            }
        }
        return Triple(map, mine, theirs)
    }

    private fun unit(
        eqid: Int,
        owner: Player,
        experience: Int = 0,
    ): GameUnit =
        GameUnit(eqid).apply {
            this.owner = owner.id
            player = owner
            strength = 10
            this.experience = experience
            ammo = 10
            entrenchment = 0
            isSurprised = false
            hasMoved = false
        }

    private fun place(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): GameUnit {
        map.map
            ?.get(row)
            ?.get(col)
            ?.setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    private fun infantryLike(unitName: String) =
        EquipmentData().apply {
            name = unitName
            uclass = UnitClass.INFANTRY.value
            target = UnitType.SOFT.value
            movmethod = MovMethod.LEG.value
            movpoints = 3
            gunrange = 1
            spotrange = 2
            softatk = 8
            hardatk = 2
            airatk = 0
            grounddef = 6
            closedef = 10
            airdef = 2
            rangedefmod = 4
            initiative = 5
            ammo = 10
            cost = 10
            groundweight = 1
        }

    private fun artillery() =
        EquipmentData().apply {
            name = "122mm Howitzer"
            uclass = UnitClass.ARTILLERY.value
            target = UnitType.SOFT.value
            movmethod = MovMethod.WHEELED.value
            movpoints = 2
            gunrange = 3
            spotrange = 1
            softatk = 10
            hardatk = 6
            airatk = 0
            grounddef = 4
            closedef = 2
            airdef = 1
            rangedefmod = 0
            initiative = 3
            ammo = 10
            cost = 20
            groundweight = 1
        }

    private companion object {
        const val EQ_INFANTRY = 1
        const val EQ_ARTILLERY = 2
        const val EQ_HQ = 3

        /** `Equipment.attr` bit 16 — OG's `Combat Support`. */
        const val COMBAT_SUPPORT_ATTR = 65536
    }
}
