package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.Transport
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.attackUnit
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The typed availability contract from `docs/design/action-affordances-and-objectives.md` §§2-3:
 * an action that can never apply to a formation is absent, one that is merely blocked stays
 * applicable with a concrete reason, and the effect numbers are the ones the command itself uses.
 */
class UnitActionAvailabilityTest {
    private val infantryEqid = 700
    private val truckEqid = 701
    private val bunkerEqid = 702
    private val tankEqid = 703

    @AfterTest
    fun cleanup() {
        TerrainEx.resetForTest()
    }

    @BeforeTest
    fun setup() {
        TerrainEx.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Rifle Division"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
                ammo = 6
                fuel = 0
                groundweight = 8
                cost = 20
            },
        )
        Equipment.putEquipment(
            truckEqid,
            EquipmentData().apply {
                name = "Truck"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movmethod = MovMethod.WHEELED.value
                movpoints = 8
                ammo = 0
                fuel = 40
            },
        )
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                name = "Bunker"
                uclass = UnitClass.FORTIFICATION.value
                target = UnitType.HARD.value
                movmethod = MovMethod.LEG.value
                movpoints = 0
                ammo = 4
                fuel = 0
                groundweight = 0
                cost = 10
            },
        )
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                name = "Tank Brigade"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 6
                ammo = 8
                fuel = 40
                hardatk = 10
                softatk = 10
                grounddef = 4
                initiative = 5
                cost = 30
            },
        )
    }

    @Test
    fun transportlessInfantryKeepsAVisibleMountActionWithTheRealReason() {
        val world = world()
        val unit = world.place(infantryEqid, 1, 1)

        val mount = availability(world, unit, UnitActionId.MOUNT)

        assertTrue(mount.applicable, "a transportable formation must keep Mount visible")
        assertFalse(mount.enabled)
        assertEquals(listOf(ActionBlockReason.NO_ORGANIC_TRANSPORT), mount.reasons.map { it.reason })
    }

    @Test
    fun aFortificationNeverShowsMountAtAll() {
        val world = world()
        val unit = world.place(bunkerEqid, 1, 1)

        assertFalse(availability(world, unit, UnitActionId.MOUNT).applicable)
    }

    @Test
    fun infantryWithATruckCanMountAndLosesItAfterMoving() {
        val world = world()
        val unit = world.place(infantryEqid, 1, 1)
        unit.transport = Transport(truckEqid)

        assertTrue(availability(world, unit, UnitActionId.MOUNT).enabled)

        unit.hasMoved = true
        val blocked = availability(world, unit, UnitActionId.MOUNT)
        assertTrue(blocked.applicable)
        assertFalse(blocked.enabled)
        assertEquals(listOf(ActionBlockReason.ALREADY_MOVED), blocked.reasons.map { it.reason })
    }

    @Test
    fun aFullyStockedFormationStillShowsSupplyAndSaysWhy() {
        val world = world()
        val unit = world.place(infantryEqid, 1, 1)

        val supply = availability(world, unit, UnitActionId.RESUPPLY)

        assertTrue(supply.applicable)
        assertFalse(supply.enabled)
        assertTrue(supply.reasons.any { it.reason == ActionBlockReason.FULLY_SUPPLIED })
    }

    @Test
    fun theSupplyTooltipQuotesExactlyWhatTheCommandWouldRestore() {
        val world = world()
        val unit = world.place(tankEqid, 1, 1)
        unit.ammo = 2
        unit.fuel = 10

        val supply = availability(world, unit, UnitActionId.RESUPPLY)
        val committed = SupplyRules.getResupplyValue(world.map, unit)

        assertTrue(supply.enabled)
        assertEquals(
            committed.ammo,
            supply.effects.first { it.kind == ActionEffectKind.SUPPLY_AMMO }.amount,
        )
        assertEquals(
            committed.fuel,
            supply.effects.first { it.kind == ActionEffectKind.SUPPLY_FUEL }.amount,
        )
        assertEquals(
            SupplyContextRules.getSupplyContext(world.map, unit).efficiencyPercent,
            supply.effects.first { it.kind == ActionEffectKind.SUPPLY_EFFICIENCY }.amount,
        )
    }

    @Test
    fun aFormationThatCarriesNeitherAmmoNorFuelNeverShowsSupply() {
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Militia"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                ammo = 0
                fuel = 0
                groundweight = 4
            },
        )
        val world = world()
        val unit = world.place(infantryEqid, 1, 1)

        assertFalse(availability(world, unit, UnitActionId.RESUPPLY).applicable)
    }

    @Test
    fun anUnaffordableReinforcementStaysVisibleAndNamesTheShortfall() {
        val world = world(prestige = 5)
        val unit = world.place(infantryEqid, 1, 1)
        unit.strength = 4

        val reinforce = availability(world, unit, UnitActionId.REINFORCE)
        val shortfall = reinforce.reasons.first { it.reason == ActionBlockReason.NEEDS_PRESTIGE }

        assertTrue(reinforce.applicable, "a damaged formation must keep Reinforce visible")
        assertFalse(reinforce.enabled)
        assertEquals(CostCalculator.reinforceCostPerStrength(unit, false) - 5, shortfall.amount)
    }

    @Test
    fun aFullStrengthFormationShowsOverstrengthInsteadOfReinforce() {
        val world = world(prestige = 100_000)
        val unit = world.place(infantryEqid, 1, 1)

        assertFalse(availability(world, unit, UnitActionId.REINFORCE).applicable)
        val overstrength = availability(world, unit, UnitActionId.OVERSTRENGTH)
        assertTrue(overstrength.applicable)
        assertFalse(overstrength.enabled)
        assertEquals(
            100,
            overstrength.reasons.first { it.reason == ActionBlockReason.NEEDS_EXPERIENCE }.amount,
        )
    }

    @Test
    fun overstrengthQuotesTheCapItsExperienceAllows() {
        val world = world(prestige = 100_000)
        val unit = world.place(infantryEqid, 1, 1)
        unit.experience = 250

        val overstrength = availability(world, unit, UnitActionId.OVERSTRENGTH)
        val target = overstrength.effects.first { it.kind == ActionEffectKind.OVERSTRENGTH_TARGET }

        assertTrue(overstrength.enabled)
        assertEquals(SupplyRules.overstrengthCap(unit), target.detail)
        assertEquals(unit.strength + SupplyRules.getReinforceValue(world.map, unit, true), target.amount)
    }

    @Test
    fun undoExplainsItsDisappearanceAfterCombatInsteadOfVanishing() {
        val world = world()
        val attacker = world.place(tankEqid, 1, 1)
        val defender = world.place(tankEqid, 1, 2, owner = world.enemy)

        world.map.attackUnit(attacker, defender, supportFire = false)

        val undo = availability(world, attacker, UnitActionId.UNDO)
        assertTrue(undo.applicable, "Undo must stay visible long enough to explain itself")
        assertFalse(undo.enabled)
        assertEquals(listOf(ActionBlockReason.UNDO_COMBAT), undo.reasons.map { it.reason })
    }

    @Test
    fun aFormationWithNoRecordedMoveNeverShowsUndo() {
        val world = world()
        val unit = world.place(tankEqid, 1, 1)

        assertFalse(availability(world, unit, UnitActionId.UNDO).applicable)
    }

    @Test
    fun everyActionIsDisabledOutsideTheLocalTurn() {
        val world = world()
        val unit = world.place(tankEqid, 1, 1)
        unit.ammo = 1

        val context = context(world, unit).copy(localTurn = false)
        val applicable = UnitActionAvailability.all(context).filter { it.applicable }

        assertTrue(applicable.isNotEmpty())
        applicable.forEach { availability ->
            assertFalse(availability.enabled, "${availability.action} must be disabled")
            assertTrue(availability.reasons.any { it.reason == ActionBlockReason.NOT_LOCAL_TURN })
        }
    }

    @Test
    fun theStripKeepsItsFixedOrder() {
        val world = world()
        val unit = world.place(tankEqid, 1, 1)

        assertEquals(
            listOf(
                UnitActionId.MOUNT,
                UnitActionId.EMBARK,
                UnitActionId.RESUPPLY,
                UnitActionId.REINFORCE,
                // Undo rides right after Reinforce, not at the tail -- it is the strip's one rescue
                // action and must stay reachable rather than risk scrolling out of view.
                UnitActionId.UNDO,
                UnitActionId.OVERSTRENGTH,
                // The two minefield commands are always RESOLVED -- `all()` returns every action,
                // applicable or not -- but a tank carries neither ability and the `minefields` key
                // is off in this harness, so both come back not-applicable and the strip omits them
                // (`docs/og-fidelity-plan.md` C.1).
                UnitActionId.LAY_MINES,
                UnitActionId.CLEAR_MINES,
                // OG 9.3's six engineering commands, added 2026-08-25, on exactly the same terms:
                // always resolved, and all six not-applicable here because a tank is neither a
                // sapper nor a demolition unit and `build_and_repair` is off in this harness.
                UnitActionId.BUILD_BRIDGE,
                UnitActionId.BUILD_FORTIFICATION,
                UnitActionId.BUILD_AIRFIELD,
                UnitActionId.BUILD_PORT,
                UnitActionId.REPAIR,
                UnitActionId.DEMOLISH,
                UnitActionId.SLEEP,
            ),
            UnitActionAvailability.all(context(world, unit)).map { it.action },
        )
        // A tank carries neither mine ability and the harness leaves `minefields` off, so both
        // chips resolve as not-applicable and never reach the strip.
        assertTrue(
            UnitActionAvailability
                .all(context(world, unit))
                .filter { it.action == UnitActionId.LAY_MINES || it.action == UnitActionId.CLEAR_MINES }
                .none { it.applicable },
        )
    }

    // ---- harness ------------------------------------------------------------------------------

    private class World(
        val map: GameMap,
        val friendly: Player,
        val enemy: Player,
    ) {
        private var nextId = 1

        fun place(
            eqid: Int,
            row: Int,
            col: Int,
            owner: Player = friendly,
        ): GameUnit {
            val unit =
                GameUnit(eqid).apply {
                    id = nextId++
                    this.owner = owner.id
                    player = owner
                }
            map.map!![row][col].terrain = TerrainType.CLEAR.value
            map.map!![row][col].setUnit(unit)
            map.addUnit(unit)
            return unit
        }
    }

    private fun world(prestige: Int = 1_000): World {
        val map =
            GameMap().apply {
                rows = 6
                cols = 6
                allocMap()
            }
        val friendly =
            Player().apply {
                id = 0
                side = 0
                this.prestige = prestige
            }
        val enemy =
            Player().apply {
                id = 1
                side = 1
            }
        map.addPlayer(friendly)
        map.addPlayer(enemy)
        map.currentPlayer = friendly
        return World(map, friendly, enemy)
    }

    private fun context(
        world: World,
        unit: GameUnit,
    ): UnitActionContext =
        UnitActionContext(
            map = world.map,
            unit = unit,
            currentPlayer = world.friendly,
        )

    private fun availability(
        world: World,
        unit: GameUnit,
        action: UnitActionId,
    ): ActionAvailability = UnitActionAvailability.forAction(action, context(world, unit))
}
