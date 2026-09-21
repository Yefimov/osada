package org.osada.model

import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Air Mode targeting on a stacked hex
 * (`docs/design/action-affordances-and-objectives.md` §7).
 *
 * The settled rule: Air Mode is a PREFERENCE that breaks the tie on a stacked hex, never a filter
 * that vetoes a legal attack. One query -- [getAttackableUnit] -- answers for the player's click,
 * the attack overlay and the AI alike, so an aircraft can shoot the ground unit under it exactly
 * as it does in the reference build.
 */
class AirModeTargetingTest {
    private val infantryEqid = 1
    private val fighterEqid = 2

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Infantry"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                softatk = 6
                hardatk = 4
                airatk = 2
                ammo = 8
            },
        )
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                name = "Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                softatk = 5
                hardatk = 3
                airatk = 7
                ammo = 8
            },
        )
    }

    @AfterTest
    fun cleanup() {
        Equipment.resetEquipment()
    }

    private fun unit(
        eqid: Int,
        owner: Player,
        id: Int,
    ) = GameUnit(eqid).apply {
        this.id = id
        this.owner = owner.id
        player = owner
        strength = 10
        ammo = 8
        fuel = 40
    }

    private val friendly =
        Player().apply {
            id = 0
            side = 0
        }
    private val enemy =
        Player().apply {
            id = 1
            side = 1
        }

    /** Spotted for side 0, holding one enemy ground unit and one enemy aircraft. */
    private fun stackedHex(): Triple<Hex, GameUnit, GameUnit> {
        val ground = unit(infantryEqid, enemy, id = 10)
        val air = unit(fighterEqid, enemy, id = 11)
        val hex =
            Hex(3, 3).apply {
                unit = ground
                airunit = air
                setSpotted(0, true)
            }
        return Triple(hex, ground, air)
    }

    @Test
    fun regularModeEngagesTheGroundOccupantOfAStackedHex() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val (hex, ground, _) = stackedHex()

        assertSame(ground, hex.getAttackableUnit(attacker, airMode = false))
    }

    @Test
    fun airModeEngagesTheAircraftOfTheSameStackedHex() {
        val attacker = unit(fighterEqid, friendly, id = 1)
        val (hex, _, air) = stackedHex()

        assertSame(air, hex.getAttackableUnit(attacker, airMode = true))
    }

    @Test
    fun theModePreferenceYieldsWhenItsOwnLayerHoldsNoLegalTarget() {
        // Infantry cannot engage an air target at all, so in Air Mode over a stacked hex the
        // preference has nothing to express and the ground unit is engaged. The opposite reading --
        // "no target" -- is what silently disarmed the player: Air Mode is re-derived from the
        // SELECTED unit after every click, so a selected aircraft forces it on, and a filter there
        // made every ground attack by an aircraft impossible.
        val groundAttacker = unit(infantryEqid, friendly, id = 1)
        val (hex, ground, _) = stackedHex()

        assertSame(ground, hex.getAttackableUnit(groundAttacker, airMode = true))
        assertSame(ground, hex.getAttackableUnit(groundAttacker, airMode = false))
    }

    @Test
    fun anAircraftEngagesTheEnemyGroundUnitBeneathIt() {
        // The reported case: the player's own aircraft occupies the air layer of the hex it is
        // attacking from, which pins Air Mode on. The enemy infantry below must still be a target.
        val bomber = unit(fighterEqid, friendly, id = 1)
        val infantry = unit(infantryEqid, enemy, id = 10)
        val hex =
            Hex(3, 3).apply {
                unit = infantry
                airunit = bomber
                setSpotted(0, true)
            }

        assertSame(infantry, hex.getAttackableUnit(bomber, airMode = true))
    }

    @Test
    fun anUnstackedHexIsTargetableInEitherMode() {
        // A lone occupant is attackable without first matching the mode to its layer.
        val infantryAttacker = unit(infantryEqid, friendly, id = 1)
        val loneGround = unit(infantryEqid, enemy, id = 10)
        val groundHex =
            Hex(3, 3).apply {
                unit = loneGround
                setSpotted(0, true)
            }

        assertSame(loneGround, groundHex.getAttackableUnit(infantryAttacker, airMode = false))
        assertSame(loneGround, groundHex.getAttackableUnit(infantryAttacker, airMode = true))

        val fighterAttacker = unit(fighterEqid, friendly, id = 2)
        val loneAir = unit(fighterEqid, enemy, id = 11)
        val airHex =
            Hex(4, 4).apply {
                airunit = loneAir
                setSpotted(0, true)
            }

        assertSame(loneAir, airHex.getAttackableUnit(fighterAttacker, airMode = false))
        assertSame(loneAir, airHex.getAttackableUnit(fighterAttacker, airMode = true))
    }

    @Test
    fun anOwnUnitOnTheOtherLayerIsNeverATarget() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val hex =
            Hex(3, 3).apply {
                airunit = unit(fighterEqid, friendly, id = 11)
                setSpotted(0, true)
            }

        assertNull(hex.getAttackableUnit(attacker, airMode = false), "own aircraft is not a target")
        assertNull(hex.getAttackableUnit(attacker, airMode = true), "own aircraft is not a target")
    }

    @Test
    fun anUnspottedEnemyIsNeverReachedByTheFallback() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val (hex, _, _) = stackedHex()
        hex.setSpotted(0, false)

        assertNull(
            hex.getAttackableUnit(attacker, airMode = false),
            "an unspotted stack offers nothing to shoot at",
        )
    }
}
