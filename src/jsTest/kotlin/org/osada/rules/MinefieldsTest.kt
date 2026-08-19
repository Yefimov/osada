package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.MineActionResult
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.clearMinefield
import org.osada.model.layMinefield
import org.osada.model.moveUnit
import org.osada.model.resetEquipment
import org.osada.model.setMoveRange
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Land minefields (`docs/og-fidelity-plan.md` C.1, OG manual 9.9).
 *
 * The two properties worth locking hardest are not the arithmetic — they are the promises the
 * mechanic was allowed to ship on (`DEFERRED.md` §1.1):
 *
 *  1. **With `minefields` off, nothing exists.** No chip, no cost, no damage, even on a hex a
 *     scenario authored as mined.
 *  2. **A DETECTED field never damages and is always drawn; only an UNDETECTED one ambushes**, and
 *     it reveals itself in the same instant so the loss is never unexplained.
 */
class MinefieldsTest {
    private val engineerEqid = 900
    private val infantryEqid = 901

    @BeforeTest
    fun setup() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            engineerEqid,
            EquipmentData().apply {
                name = "Sapper Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 8
                fuel = 0
                softatk = 6
                // OG's `Drop mines`, attr bit 0.
                attr = 1
            },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Rifle Division"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 6
                ammo = 8
                fuel = 0
                softatk = 8
            },
        )
    }

    @AfterTest
    fun tearDown() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
    }

    // ---- the key is the whole gate ------------------------------------------------------------

    @Test
    fun withTheKeyOffAnAuthoredMinefieldDoesNothing() {
        val map = world()
        val hex = map.map!![2][3]
        hex.mines = 0b10 // authored by side 1, as a scenario would import it
        assertFalse(Minefields.threatens(hex, 0), "no rule may read a field the player did not enable")
        assertFalse(Minefields.enabled())
    }

    @Test
    fun withTheKeyOffNoUnitCanLayOrClear() {
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        assertFalse(MineAbilities.canDropMines(engineer))
        assertFalse(MineAbilities.canClearMines(engineer))
        assertEquals(MineActionResult.NOT_ALLOWED, map.layMinefield(engineer))
    }

    // ---- laying ---------------------------------------------------------------------------------

    @Test
    fun anEngineerLaysAFieldForTwoAmmunitionAndItsWholeTurn() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        val startingAmmo = engineer.ammo

        assertEquals(MineActionResult.LAID, map.layMinefield(engineer))
        assertEquals(startingAmmo - Minefields.LAY_MINES_AMMO_COST, engineer.ammo)
        assertTrue(engineer.hasMoved && engineer.hasFired, "laying spends the turn")
        assertTrue(Minefields.isDetectedBy(map.map!![2][2], 0), "the layer always sees its own field")
    }

    @Test
    fun aFormationWithoutTheAbilityCannotLay() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val rifles = place(map, infantryEqid, 2, 2)
        assertFalse(MineAbilities.canDropMines(rifles))
        assertEquals(MineActionResult.NOT_ALLOWED, map.layMinefield(rifles))
    }

    @Test
    fun aFormationThatHasAlreadyActedCannotLay() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        engineer.hasMoved = true
        assertEquals(MineActionResult.NOT_ALLOWED, map.layMinefield(engineer))
    }

    @Test
    fun ownFieldsDoNotThreatenTheirOwner() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        map.layMinefield(engineer)
        assertFalse(Minefields.threatens(map.map!![2][2], 0), "you cross your own minefields")
        assertTrue(Minefields.threatens(map.map!![2][2], 1), "the enemy does not")
    }

    // ---- clearing -------------------------------------------------------------------------------

    @Test
    fun clearingEitherSucceedsOrSuppresses() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        Minefields.lay(map.map!![2][2], 1)

        val outcome = map.clearMinefield(engineer)
        assertTrue(outcome == MineActionResult.CLEARED || outcome == MineActionResult.FAILED_ATTEMPT)
        if (outcome == MineActionResult.CLEARED) {
            assertEquals(0, map.map!![2][2].mines)
            assertEquals(0, engineer.hits)
        } else {
            assertTrue(map.map!![2][2].mines != 0, "a failed attempt leaves the field")
            assertEquals(1, engineer.hits, "and suppresses the engineer, which is OG's stated penalty")
        }
        assertTrue(engineer.hasFired, "either way the turn is spent")
    }

    @Test
    fun thereIsNothingToClearOnACleanHex() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val engineer = place(map, engineerEqid, 2, 2)
        assertEquals(MineActionResult.NOT_ALLOWED, map.clearMinefield(engineer))
    }

    // ---- movement -------------------------------------------------------------------------------

    @Test
    fun anUndetectedFieldDamagesAndRevealsItself() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val mover = place(map, infantryEqid, 2, 2)
        val mined: Hex = map.map!![2][3]
        mined.mines = 0b10 // laid by side 1, undetected by side 0

        val before = mover.strength
        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 2, 3)

        assertTrue(result.hitMinefield)
        assertTrue(result.minefieldWasHidden)
        assertEquals(Minefields.UNDETECTED_MINE_DAMAGE, result.minefieldLosses)
        assertEquals(before - Minefields.UNDETECTED_MINE_DAMAGE, mover.strength)
        assertTrue(Minefields.isDetectedBy(mined, 0), "walking into it reveals it in the same instant")
        assertEquals(0, mover.moveLeft, "and consumes all remaining movement")
    }

    @Test
    fun aDetectedFieldCostsTheMoveButNoStrength() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val mover = place(map, infantryEqid, 2, 2)
        val mined = map.map!![2][3]
        mined.mines = 0b10
        Minefields.markDetected(mined, 0)

        val before = mover.strength
        map.setMoveRange(mover)
        val result = map.moveUnit(mover, 2, 3)

        assertTrue(result.hitMinefield)
        assertFalse(result.minefieldWasHidden)
        assertEquals(0, result.minefieldLosses, "a field you can see does not ambush you")
        assertEquals(before, mover.strength)
        assertEquals(0, mover.moveLeft)
    }

    @Test
    fun aFormationStandingInAFieldHasOneMovementPoint() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        val mover = place(map, infantryEqid, 2, 2)
        assertTrue(MovementRules.getUnitMoveRange(mover) > Minefields.MOVEMENT_IN_MINEFIELD)
        map.map!![2][2].mines = 0b10
        assertEquals(Minefields.MOVEMENT_IN_MINEFIELD, MovementRules.getUnitMoveRange(mover))
    }

    // ---- detection ------------------------------------------------------------------------------

    @Test
    fun standingBesideAFieldRevealsIt() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        place(map, infantryEqid, 2, 2)
        val mined = map.map!![2][3]
        mined.mines = 0b10
        assertFalse(Minefields.isDetectedBy(mined, 0))

        Minefields.revealAdjacent(map, 0)
        assertTrue(Minefields.isDetectedBy(mined, 0), "sappers find mines by being next to them")
    }

    @Test
    fun aFieldNobodyIsNearStaysHidden() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        place(map, infantryEqid, 2, 2)
        val mined = map.map!![6][6]
        mined.mines = 0b10

        Minefields.revealAdjacent(map, 0)
        assertFalse(Minefields.isDetectedBy(mined, 0))
    }

    // ---- harness --------------------------------------------------------------------------------

    private fun ruleset(vararg overrides: Pair<RuleKey, Int>) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "Test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + overrides.toMap(),
            ),
        )
    }

    private val friendly =
        Player().apply {
            id = 0
            side = 0
        }

    private fun world(): GameMap =
        GameMap().apply {
            rows = 8
            cols = 8
            allocMap()
            addPlayer(friendly)
            addPlayer(
                Player().apply {
                    id = 1
                    side = 1
                },
            )
            currentPlayer = friendly
            for (r in 0 until rows) {
                for (c in 0 until cols) map!![r][c].terrain = TerrainType.CLEAR.value
            }
        }

    private fun place(
        map: GameMap,
        eqid: Int,
        row: Int,
        col: Int,
    ): GameUnit {
        val unit =
            GameUnit(eqid).apply {
                id = row * 100 + col
                owner = 0
                player = friendly
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }
}
