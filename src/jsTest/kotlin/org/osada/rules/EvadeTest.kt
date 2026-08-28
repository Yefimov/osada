package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.attackUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's `Evade` (manual §7.2), wired 2026-08-27 (`docs/og-fidelity-plan.md` §W).
 *
 * The probability itself is rolled from the shared random stream, so these tests assert the two
 * things that are deterministic and that matter: **who is eligible** (the ability, `evade_special`
 * and `zoc_evade` deciding the odds) and **what an evade does to the exchange** when it lands.
 *
 * `percentFor` returning 0 is the load-bearing assertion in most of them — an ineligible defender
 * never reaches the roll, so it never draws from the stream, which is what keeps a multiplayer
 * battle in step.
 */
class EvadeTest : OgRulesTestHarness() {
    private val dodgerEqid = 1020
    private val stolidEqid = 1021
    private val planeEqid = 1022
    private val gunEqid2 = 1023

    /** OG's `Evade`, `attr2` bit 7. */
    private val attr2Evade = 128

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(dodgerEqid, scout("Recon Patrol", attr2Evade))
        Equipment.putEquipment(stolidEqid, scout("Rifle Company", bits = 0))
        Equipment.putEquipment(
            planeEqid,
            EquipmentData().apply {
                name = "Tactical Bomber"
                uclass = UnitClass.TACTICAL_BOMBER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                ammo = 6
                softatk = 12
                hardatk = 10
                airdef = 6
            },
        )
        Equipment.putEquipment(
            gunEqid2,
            EquipmentData().apply {
                name = "Field Gun"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                gunrange = 3
                ammo = 8
                softatk = 12
                hardatk = 8
                grounddef = 3
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun scout(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.RECON.value
        target = UnitType.SOFT.value
        movmethod = MovMethod.WHEELED.value
        movpoints = 6
        ammo = 6
        softatk = 6
        hardatk = 4
        grounddef = 4
        attr2 = bits
    }

    private fun ship(
        label: String,
        cls: UnitClass,
    ) = EquipmentData().apply {
        name = label
        uclass = cls.value
        target = UnitType.SEA.value
        movmethod = MovMethod.NAVAL.value
        movpoints = 8
        ammo = 8
        navalatk = 10
        grounddef = 6
    }

    private fun evadeWorld(): GameMap {
        ruleset()
        val map = world()
        GameHolder.instance = holderFor(map)
        return map
    }

    // ---- eligibility ---------------------------------------------------------------------------

    @Test
    fun withNoClassTableAndNoAbilityThereIsNoEvade() {
        val map = evadeWorld()
        EfileConfig.setForTest()
        val defender = place(map, stolidEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertFalse(Evade.hasAbility(defender))
        assertEquals(0, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
    }

    @Test
    fun theAuthorsThirtyPercentIsTheDefaultWhereTheEfileSaysNothing() {
        val map = evadeWorld()
        EfileConfig.setForTest()
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(30, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
    }

    @Test
    fun theFlatBangFormIsWhatEveryShippedEfileUses() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!30"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(30, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
    }

    @Test
    fun theTwentyThreeColumnTableIsIndexedByOgsOwnClassNumbering() {
        val map = evadeWorld()
        // OG's documented default list. Recon is column 3 (value 70), Submarine column 16 (100).
        EfileConfig.setForTest(
            rawKeyMap =
                mapOf("class_evade" to "60,20,70,20,50,0,20,0,10,50,65,65,55,30,30,100,50,20,10,10,0,0,55"),
        )
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            70,
            Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "Recon",
        )
    }

    @Test
    fun aTableOfTheWrongLengthIsIgnoredRatherThanPartlyRead() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "60,20,70"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            30,
            Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "OG: \"must define 23 values\" — a short list is malformed, and the fallback is 30%",
        )
    }

    // ---- zoc_evade -----------------------------------------------------------------------------

    @Test
    fun crowdingSpoilsTheEscape() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!30"), intKeyMap = mapOf("zoc_evade" to 5))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            30,
            Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "the attacker alone",
        )
        assertEquals(25, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 2, hasRetreatHex = true))
        assertEquals(20, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 3, hasRetreatHex = true))
    }

    @Test
    fun theDecrementCannotDriveTheChanceBelowNothing() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!10"), intKeyMap = mapOf("zoc_evade" to 5))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(0, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 6, hasRetreatHex = true))
    }

    // ---- evade_special -------------------------------------------------------------------------

    @Test
    fun aGroundFormationCannotDodgeAnAttackFromTheAir() {
        val map = evadeWorld()
        EfileConfig.setForTest()
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val bomber = place(map, planeEqid, 2, 3, side = 1)

        assertEquals(
            0,
            Evade.percentFor(bomber, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "OG's default evade_special is 9 = option 1 + option 8",
        )
    }

    @Test
    fun anAirFormationStillDodgesAnythingUnderTheDefault() {
        val map = evadeWorld()
        EfileConfig.setForTest()
        Equipment.putEquipment(
            planeEqid + 1,
            EquipmentData().apply {
                name = "Evading Fighter"
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 10
                ammo = 6
                airatk = 10
                airdef = 8
                attr2 = attr2Evade
            },
        )
        val defender = place(map, planeEqid + 1, 2, 2, side = 0)
        val bomber = place(map, planeEqid, 2, 3, side = 1)

        assertEquals(
            30,
            Evade.percentFor(bomber, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "option 8 confines options 1 and 2 to ground units",
        )
    }

    @Test
    fun optionFourTurnsEvadeOffAltogether() {
        val map = evadeWorld()
        EfileConfig.setForTest(intKeyMap = mapOf("evade_special" to 4))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(0, Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
    }

    @Test
    fun optionTwoRefusesAnEvadeBeyondRangeOne() {
        val map = evadeWorld()
        EfileConfig.setForTest(intKeyMap = mapOf("evade_special" to (2 or 8)))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 4, side = 1)

        assertEquals(
            30,
            Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "adjacent is fine",
        )
        assertEquals(0, Evade.percentFor(attacker, defender, distance = 2, adjacentEnemies = 1, hasRetreatHex = true))
    }

    // ---- what an evade does to the exchange -----------------------------------------------------

    @Test
    fun anEvadedAttackDoesNothingInEitherDirection() {
        val map = evadeWorld()
        // 100% so the roll is deterministic without reaching into the stream.
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!100"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, stolidEqid, 2, 3, side = 1)
        val defenderStrength = defender.strength
        val attackerStrength = attacker.strength

        val result = map.attackUnit(attacker, defender, false)

        assertTrue(result.isEvaded)
        assertEquals(0, result.kills)
        assertEquals(0, result.losses)
        assertFalse(result.defcanfire, "the exchange did not happen, so there is nothing to answer")
        assertEquals(defenderStrength, defender.strength)
        assertEquals(attackerStrength, attacker.strength)
    }

    @Test
    fun theAttackersShotIsStillSpent() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!100"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, stolidEqid, 2, 3, side = 1)

        map.attackUnit(attacker, defender, false)

        assertTrue(attacker.hasFired, "a miss is still a shot")
    }

    @Test
    fun anImpossibleEvadeLeavesTheExchangeExactlyAsItWas() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!0"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, stolidEqid, 2, 3, side = 1)

        val result = map.attackUnit(attacker, defender, false)

        assertFalse(result.isEvaded)
        assertTrue(result.kills > 0, "a 0% evade is the combat OSADA has always resolved")
    }

    // ---- submarines evade by class, and OG's anti-stacking exclusion ---------------------------

    @Test
    fun aSubmarineEvadesWithoutCarryingTheSpecial() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!30"))
        Equipment.putEquipment(
            dodgerEqid + 50,
            EquipmentData().apply {
                name = "Ocean SS"
                uclass = UnitClass.SUBMARINE.value
                target = UnitType.SEA.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 6
                ammo = 6
                navalatk = 12
                grounddef = 5
                // No `Evade` bit -- which is how OG's own efiles ship their submarines.
                attr2 = 0
            },
        )
        val submarine = place(map, dodgerEqid + 50, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertFalse(Evade.hasAbility(submarine), "no efile grants its submarines the special")
        assertTrue(Evade.eligible(submarine), "manual 8.3.5: submarines sometimes evade, by class")
        assertEquals(30, Evade.percentFor(attacker, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
    }

    @Test
    fun withNoClassTableTheSubmarineIsTheOnlyClassThatEvades() {
        val map = evadeWorld()
        EfileConfig.setForTest()
        Equipment.putEquipment(dodgerEqid + 51, ship("Destroyer", UnitClass.DESTROYER))
        Equipment.putEquipment(dodgerEqid + 52, ship("Ocean SS", UnitClass.SUBMARINE))
        val destroyer = place(map, dodgerEqid + 51, 2, 2, side = 0)
        val submarine = place(map, dodgerEqid + 52, 3, 3, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            0,
            Evade.percentFor(attacker, destroyer, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "the author: with class_evade undefined, only Submarines can evade",
        )
        assertEquals(
            30,
            Evade.percentFor(attacker, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "and they do so at 30%, not the manual's stale 50%",
        )
    }

    @Test
    fun aDefinedClassTableGrantsTheWholeClass() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!30"))
        Equipment.putEquipment(dodgerEqid + 53, ship("Destroyer", UnitClass.DESTROYER))
        val destroyer = place(map, dodgerEqid + 53, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            30,
            Evade.percentFor(attacker, destroyer, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "class_evade is itself a grant -- the correction of 2026-08-27",
        )
    }

    @Test
    fun aSurprisedAttackerOrARuggedDefenceCancelsTheAttempt() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!100"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            100,
            Evade.percentFor(
                attacker,
                defender,
                distance = 1,
                adjacentEnemies = 1,
                hasRetreatHex = true,
                surprisedOrRugged = false,
            ),
        )
        assertEquals(
            0,
            Evade.percentFor(
                attacker,
                defender,
                distance = 1,
                adjacentEnemies = 1,
                hasRetreatHex = true,
                surprisedOrRugged = true,
            ),
            "OpenGen 0.70.0: no evade attempt if the attacker is surprised or rugged defence is raised",
        )
    }

    // ---- the class route's own modifiers, which the special route does not get -----------------

    @Test
    fun theClassRouteCountsBothSidesExperienceAndTheSpecialRouteOnlyTheDefenders() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!30"))
        Equipment.putEquipment(dodgerEqid + 60, ship("Ocean SS", UnitClass.SUBMARINE))
        val submarine = place(map, dodgerEqid + 60, 3, 3, side = 0)
        val special = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)
        submarine.experience = 200
        special.experience = 200
        attacker.experience = 300

        assertEquals(
            30 + 5 * 2 - 5 * 3,
            Evade.percentFor(attacker, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "class route: +5 x defender bars - 5 x attacker bars",
        )
        assertEquals(
            30 + 5 * 2,
            Evade.percentFor(attacker, special, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "special route: defender's bars only, regardless of the attacker's",
        )
    }

    @Test
    fun theClassRouteIsHalvedAgainstAirAndAgainWhenMounted() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!40"), intKeyMap = mapOf("evade_special" to 0))
        Equipment.putEquipment(dodgerEqid + 61, ship("Ocean SS", UnitClass.SUBMARINE))
        val submarine = place(map, dodgerEqid + 61, 3, 3, side = 0)
        val gun = place(map, gunEqid2, 2, 3, side = 1)
        val bomber = place(map, planeEqid, 4, 4, side = 1)

        assertEquals(40, Evade.percentFor(gun, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true))
        assertEquals(
            20,
            Evade.percentFor(bomber, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "halved when the attacker is an air unit",
        )
        submarine.isMounted = true
        assertEquals(
            10,
            Evade.percentFor(bomber, submarine, distance = 1, adjacentEnemies = 1, hasRetreatHex = true),
            "and halved again when the defender is mounted",
        )
    }

    // ---- the retreat precondition ---------------------------------------------------------------

    @Test
    fun aFormationWithNowhereToRetreatDoesNotEvade() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!100"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, gunEqid2, 2, 3, side = 1)

        assertEquals(
            0,
            Evade.percentFor(attacker, defender, distance = 1, adjacentEnemies = 1, hasRetreatHex = false),
            "the author: evasion only works if the defender can find a hex to retreat to",
        )
    }

    @Test
    fun aSuccessfulEvadeMovesTheDefenderOffTheContestedHex() {
        val map = evadeWorld()
        EfileConfig.setForTest(rawKeyMap = mapOf("class_evade" to "!100"))
        val defender = place(map, dodgerEqid, 2, 2, side = 0)
        val attacker = place(map, stolidEqid, 2, 3, side = 1)

        val result = map.attackUnit(attacker, defender, false)

        assertTrue(result.isEvaded)
        val pos = defender.getPos()
        assertTrue(
            pos != null && !(pos.row == 2 && pos.col == 2),
            "an evade is a RETREAT out of contact, not a cancelled attack",
        )
    }
}
