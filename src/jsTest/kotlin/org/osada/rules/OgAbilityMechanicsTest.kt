package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.attackUnit
import org.osada.model.getUnits
import org.osada.model.unitEndTurn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The six abilities wired on 2026-08-27 from Luis Guzman's own specials and combat references
 * (`docs/og-fidelity-plan.md` §X): `Jet (Stealth)`, `Partizan`, `Exploit Success`, `Kamikaze`,
 * `Torpedo bomber` and `Saboteur`.
 *
 * **`Jet (Stealth)` is the one worth reading twice.** `OG_ABILITY_AUDIT.md` §7.1.1 had it filed as
 * `CONFIRMED-BIT, UNCONFIRMED-EFFECT` and warned against guessing from the name — so these tests
 * pin both what it does and, in `aJetIsStillInterceptedByFighters`, what it does not.
 */
class OgAbilityMechanicsTest : OgRulesTestHarness() {
    private val jetEqid = 1100
    private val propEqid = 1101
    private val jetFlakEqid = 1102
    private val plainFlakEqid = 1103
    private val partisanEqid = 1104
    private val exploiterEqid = 1105
    private val kamikazeEqid = 1106
    private val torpedoEqid = 1107
    private val saboteurEqid = 1108
    private val victimEqid = 1109

    private val attrExJetStealth = 524288
    private val attrExPartizan = 1024
    private val attrExExploit = 2048
    private val attrExKamikaze = 65536
    private val attrExTorpedo = 256
    private val attrExSaboteur = 262144

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(jetEqid, plane("Me 262", attrExJetStealth))
        Equipment.putEquipment(propEqid, plane("Bf 109", bits = 0))
        Equipment.putEquipment(jetFlakEqid, flak("SAM Battery", attrExJetStealth))
        Equipment.putEquipment(plainFlakEqid, flak("8.8cm FlaK", bits = 0))
        Equipment.putEquipment(partisanEqid, foot("Partisans", attrExPartizan))
        Equipment.putEquipment(exploiterEqid, foot("Assault Group", attrExExploit))
        Equipment.putEquipment(victimEqid, foot("Militia", bits = 0))
        Equipment.putEquipment(saboteurEqid, foot("Commandos", attrExSaboteur))
        Equipment.putEquipment(kamikazeEqid, plane("Ohka", attrExKamikaze))
        Equipment.putEquipment(torpedoEqid, plane("Nakajima B5N", attrExTorpedo).apply { navalatk = 0 })
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun plane(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.TACTICAL_BOMBER.value
        target = UnitType.AIR.value
        movmethod = MovMethod.AIR.value
        movpoints = 10
        ammo = 6
        fuel = 8
        softatk = 8
        hardatk = 6
        navalatk = 8
        airdef = 6
        attrEx = bits
    }

    private fun flak(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.AIR_DEFENCE.value
        target = UnitType.SOFT.value
        movmethod = MovMethod.TOWED.value
        movpoints = 2
        ammo = 8
        airatk = 10
        grounddef = 4
        attrEx = bits
    }

    private fun foot(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.INFANTRY.value
        target = UnitType.SOFT.value
        movmethod = MovMethod.LEG.value
        movpoints = 4
        ammo = 8
        initiative = 4
        softatk = 9
        hardatk = 5
        grounddef = 4
        attrEx = bits
    }

    private fun battlefield(): GameMap {
        ruleset()
        val map = world()
        GameHolder.instance = holderFor(map)
        EfileConfig.setForTest()
        return map
    }

    // ---- Jet (Stealth): the ability this project refused to guess at ----------------------------

    @Test
    fun onlyAJetCapableBatteryInterceptsAJet() {
        val map = battlefield()
        val jet = place(map, jetEqid, 2, 2, side = 1)
        val jetFlak = place(map, jetFlakEqid, 2, 3, side = 0)
        val plainFlak = place(map, plainFlakEqid, 3, 3, side = 0)

        assertTrue(UnitCapabilities.hasJetStealth(jet.unitData(true)))
        assertTrue(UnitCapabilities.hasJetStealth(jetFlak.unitData(true)))
        assertFalse(UnitCapabilities.hasJetStealth(plainFlak.unitData(true)))
    }

    @Test
    fun anOrdinaryAircraftIsInterceptedByAnyBattery() {
        val map = battlefield()
        val prop = place(map, propEqid, 2, 2, side = 1)

        assertFalse(
            UnitCapabilities.hasJetStealth(prop.unitData(true)),
            "the rule is about the PLANE carrying it; a propeller aircraft is caught by anything",
        )
    }

    // ---- Partizan ------------------------------------------------------------------------------

    @Test
    fun aPartisanIsNotHaltedByAnEnemyZoneOfControl() {
        val map = battlefield()
        val partisans = place(map, partisanEqid, 2, 2, side = 0)
        val militia = place(map, victimEqid, 2, 3, side = 0)

        assertTrue(UnitCapabilities.ignoresZoneOfControl(partisans.unitData(true)))
        assertFalse(UnitCapabilities.ignoresZoneOfControl(militia.unitData(true)))
    }

    // ---- Exploit Success -----------------------------------------------------------------------

    @Test
    fun exploitSuccessReturnsTheMovementButNotTheShot() {
        val map = battlefield()
        val attacker = place(map, exploiterEqid, 2, 2, side = 0)
        val defender = place(map, victimEqid, 2, 3, side = 1)
        defender.strength = 1
        // The real sequence is move-then-attack: `hasMoved` is set by moving (`MoveExecutor`), not
        // by firing, so an attacker that has not moved has nothing to be given back.
        attacker.hasMoved = true
        attacker.moveLeft = 2

        val result = map.attackUnit(attacker, defender, false)

        assertTrue(defender.destroyed, "the fixture needs the defender dead for the ability to fire")
        assertTrue(result.isExploit)
        assertFalse(attacker.hasMoved, "it may walk into the gap it just made")
        assertEquals(2, attacker.moveLeft, "its REMAINING movement — an overrun would add to it")
        assertTrue(attacker.hasFired, "and the attack is still spent — this is not an overrun")
    }

    @Test
    fun aFormationWithoutTheAbilityStaysPut() {
        val map = battlefield()
        val attacker = place(map, victimEqid, 2, 2, side = 0)
        val defender = place(map, victimEqid, 2, 3, side = 1)
        defender.strength = 1
        attacker.hasMoved = true
        attacker.moveLeft = 2

        val result = map.attackUnit(attacker, defender, false)

        assertFalse(result.isExploit)
        assertTrue(attacker.hasMoved, "an ordinary attacker that had moved stays put")
    }

    @Test
    fun exploitSuccessNeedsTheDefenderActuallyGone() {
        val map = battlefield()
        val attacker = place(map, exploiterEqid, 2, 2, side = 0)
        val defender = place(map, victimEqid, 2, 3, side = 1)
        attacker.hasMoved = true

        val result = map.attackUnit(attacker, defender, false)

        assertFalse(defender.destroyed, "a full-strength militia survives one attack")
        assertFalse(result.isExploit)
        assertTrue(attacker.hasMoved)
    }

    // ---- Kamikaze ------------------------------------------------------------------------------

    @Test
    fun theDefaultModelIsSpentByAttacking() {
        val map = battlefield()
        val ohka = place(map, kamikazeEqid, 2, 2, side = 0)
        val target = place(map, victimEqid, 2, 3, side = 1)

        map.attackUnit(ohka, target, false)

        assertTrue(ohka.destroyed, "OG: dies after being engaged in combat")
    }

    @Test
    fun theMissileModelSurvivesTheAttackAndCannotResupply() {
        val map = battlefield()
        EfileConfig.setForTest(intKeyMap = mapOf("kamikaze" to 1))
        val missile = place(map, kamikazeEqid, 2, 2, side = 0)
        val target = place(map, victimEqid, 2, 3, side = 1)

        map.attackUnit(missile, target, false)

        assertFalse(missile.destroyed, "under kamikaze=1 it dies of FUEL, not of having fought")
        assertFalse(SupplyRules.canResupply(map, missile), "and it cannot be refilled")
        missile.fuel = 0
        assertTrue(Kamikaze.strandedWithoutFuel(missile))
    }

    @Test
    fun anOrdinaryAircraftIsUnaffectedByEitherModel() {
        val map = battlefield()
        EfileConfig.setForTest(intKeyMap = mapOf("kamikaze" to 1))
        val prop = place(map, propEqid, 2, 2, side = 0)

        assertTrue(Kamikaze.canResupply(prop))
        prop.fuel = 0
        assertFalse(Kamikaze.strandedWithoutFuel(prop))
    }

    // ---- Torpedo bomber ------------------------------------------------------------------------

    @Test
    fun aTorpedoRunNeedsBothHexesOverOpenSea() {
        val map = battlefield()
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) map.map!![r][c].terrain = TerrainType.OCEAN.value
        }
        Equipment.putEquipment(
            victimEqid + 20,
            EquipmentData().apply {
                name = "Freighter"
                uclass = UnitClass.NAVAL_TRANSPORT.value
                target = UnitType.SEA.value
                movmethod = MovMethod.NAVAL.value
                movpoints = 6
                ammo = 4
                grounddef = 5
            },
        )
        val bomber = place(map, torpedoEqid, 2, 2, side = 0)
        val ship = place(map, victimEqid + 20, 2, 3, side = 1)

        assertTrue(UnitCapabilities.torpedoRunPermitted(bomber, ship))

        map.map!![2][2].terrain = TerrainType.CLEAR.value
        assertFalse(
            UnitCapabilities.torpedoRunPermitted(bomber, ship),
            "OG: both attacker and target must occupy sea terrain",
        )
    }

    // ---- Saboteur ------------------------------------------------------------------------------

    @Test
    fun aSabotagedFormationLosesAttackDefenceSupplyAndItsNextTurn() {
        val map = battlefield()
        val victim = place(map, victimEqid, 2, 3, side = 1)
        victim.sabotaged = true

        assertFalse(SupplyRules.canResupply(map, victim), "OG: cannot reinforce or resupply")
        assertEquals(
            0,
            Evade.percentFor(
                place(map, victimEqid, 2, 2, side = 0),
                victim,
                distance = 1,
                adjacentEnemies = 1,
                hasRetreatHex = true,
            ),
            "OG: cannot evade",
        )

        victim.unitEndTurn(spotSide = 0)

        assertFalse(victim.sabotaged, "the state is spent at the turn boundary")
        assertTrue(victim.hasMoved && victim.hasFired, "having cost it the next move and attack")
    }

    @Test
    fun theAttemptSpendsAmmunitionWhicheverWayItGoes() {
        val map = battlefield()
        EfileConfig.setForTest(intKeyMap = mapOf("sabotage_min" to 100, "sabotage_max" to 100))
        val commandos = place(map, saboteurEqid, 2, 2, side = 0)
        val target = place(map, victimEqid, 2, 3, side = 1)
        val ammo = commandos.getAmmo()

        val result = map.attackUnit(commandos, target, false)

        assertTrue(result.isSabotage, "a 100% chance always gets in")
        assertTrue(target.sabotaged)
        assertEquals(0, result.kills, "a successful sabotage replaces the battle")
        assertTrue(commandos.getAmmo() < ammo, "one ammunition point, whatever the outcome")
    }

    @Test
    fun aFormationWithoutTheAbilityJustFights() {
        val map = battlefield()
        EfileConfig.setForTest(intKeyMap = mapOf("sabotage_min" to 100, "sabotage_max" to 100))
        val plain = place(map, victimEqid, 2, 2, side = 0)
        val target = place(map, victimEqid, 2, 3, side = 1)

        val result = map.attackUnit(plain, target, false)

        assertFalse(result.isSabotage)
        assertFalse(target.sabotaged)
        assertTrue(result.kills > 0)
    }

    @Test
    fun anAlreadySabotagedTargetIsNotWorthASecondAttempt() {
        val map = battlefield()
        val commandos = place(map, saboteurEqid, 2, 2, side = 0)
        val target = place(map, victimEqid, 2, 3, side = 1)
        target.sabotaged = true

        assertFalse(
            Sabotage.canAttempt(commandos, target),
            "the effect does not stack, so the ammunition would buy nothing",
        )
        assertTrue(map.getUnits().isNotEmpty())
    }
}
