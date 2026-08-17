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
 * The settled rule: regular mode engages the ground/naval occupant, Air Mode engages the aircraft,
 * and neither silently reaches across to the other layer. The layer-agnostic question the AI and
 * the attack-range pass ask stays on [getAttackableUnit]; only the player's click narrowed.
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

        assertSame(ground, hex.getActiveLayerTarget(attacker, airMode = false))
    }

    @Test
    fun airModeEngagesTheAircraftOfTheSameStackedHex() {
        val attacker = unit(fighterEqid, friendly, id = 1)
        val (hex, _, air) = stackedHex()

        assertSame(air, hex.getActiveLayerTarget(attacker, airMode = true))
    }

    @Test
    fun theActiveLayerNeverReachesAcrossToTheOtherOne() {
        // Infantry cannot engage an air target at all. In Air Mode over a stacked hex that means
        // "no target", NOT "fall through and shoot the ground unit instead" -- which is exactly what
        // the old shared query did, and what made a click's outcome depend on eligibility rather
        // than on the mode the player had declared.
        val groundAttacker = unit(infantryEqid, friendly, id = 1)
        val (hex, ground, _) = stackedHex()

        assertNull(hex.getActiveLayerTarget(groundAttacker, airMode = true))
        assertSame(ground, hex.getActiveLayerTarget(groundAttacker, airMode = false))
    }

    @Test
    fun anUnstackedHexIsTargetableInEitherMode() {
        // The narrowing applies to the ambiguous case only. A lone occupant is still attackable
        // without first matching the mode to its layer, exactly as before.
        val infantryAttacker = unit(infantryEqid, friendly, id = 1)
        val loneGround = unit(infantryEqid, enemy, id = 10)
        val groundHex =
            Hex(3, 3).apply {
                unit = loneGround
                setSpotted(0, true)
            }

        assertSame(loneGround, groundHex.getActiveLayerTarget(infantryAttacker, airMode = false))
        assertSame(loneGround, groundHex.getActiveLayerTarget(infantryAttacker, airMode = true))

        val fighterAttacker = unit(fighterEqid, friendly, id = 2)
        val loneAir = unit(fighterEqid, enemy, id = 11)
        val airHex =
            Hex(4, 4).apply {
                airunit = loneAir
                setSpotted(0, true)
            }

        assertSame(loneAir, airHex.getActiveLayerTarget(fighterAttacker, airMode = false))
        assertSame(loneAir, airHex.getActiveLayerTarget(fighterAttacker, airMode = true))
    }

    @Test
    fun theLayerAgnosticQueryTheAiUsesIsUnchanged() {
        // The AI and the attack-range sweep must still see the whole hex, so the attack ring keeps
        // appearing over a stack that holds something engageable. Only the player's click narrowed:
        // the same hex, the same attacker, the same mode -- one query still finds the ground unit,
        // the other refuses to reach across to it.
        val groundAttacker = unit(infantryEqid, friendly, id = 1)
        val (hex, ground, _) = stackedHex()

        assertSame(ground, hex.getAttackableUnit(groundAttacker, airMode = true))
        assertNull(
            hex.getActiveLayerTarget(groundAttacker, airMode = true),
            "the player's own click stays on the layer they declared",
        )
    }

    @Test
    fun theInactiveLayerEnemyIsWhatTheHintPointsAt() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val (hex, ground, air) = stackedHex()

        assertSame(air, hex.inactiveLayerEnemy(attacker, airMode = false))
        assertSame(ground, hex.inactiveLayerEnemy(attacker, airMode = true))
    }

    @Test
    fun anUnstackedHexOffersNoOtherLayer() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val hex =
            Hex(3, 3).apply {
                unit = unit(infantryEqid, enemy, id = 10)
                setSpotted(0, true)
            }

        assertNull(hex.inactiveLayerEnemy(attacker, airMode = false))
        assertNull(hex.inactiveLayerEnemy(attacker, airMode = true))
    }

    @Test
    fun anOwnUnitOnTheOtherLayerIsNotAnEnemyHint() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val hex =
            Hex(3, 3).apply {
                unit = unit(infantryEqid, enemy, id = 10)
                airunit = unit(fighterEqid, friendly, id = 11)
                setSpotted(0, true)
            }

        assertNull(hex.inactiveLayerEnemy(attacker, airMode = false), "own aircraft is not a target hint")
    }

    @Test
    fun anUnspottedOtherLayerEnemyIsNeverRevealedByTheHint() {
        val attacker = unit(infantryEqid, friendly, id = 1)
        val (hex, _, _) = stackedHex()
        hex.setSpotted(0, false)

        assertNull(
            hex.inactiveLayerEnemy(attacker, airMode = false),
            "a hint that named an unspotted unit would leak it",
        )
    }
}
