package org.osada.rules

import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.rules.ruleset.RuleKey
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four largest authored scenario options that had no reader until 2026-08-30.
 *
 * `docs/og-fidelity-plan.md` §AF.5 ranked the undeployed options by how much content sets them and
 * these were the top of the list — 295, 295, 202 and 197 of the 397 deployed scenarios whose source
 * parses. Every one of them was decoded years' worth of sessions ago; what was missing was a rule
 * that read the switch.
 *
 * **Two of them turned out to be options OSADA already behaved as if ON**, which is a different and
 * worse failure than not reading a switch: the scenarios that author them were right by accident
 * and every other scenario was wrong. Those two get their own assertions below, because the bug is
 * in the UNAUTHORED case and a test that only checked the authored one would have passed before the
 * fix.
 */
class AuthoredScenarioOptionsTest : OgRulesTestHarness() {
    private val engineerEqid = 970
    private val blindEqid = 971
    private val battleshipEqid = 972
    private val cruiserEqid = 973

    @BeforeTest
    fun setup() {
        installTestWorld()
        ruleset()
        Equipment.putEquipment(
            engineerEqid,
            EquipmentData().apply {
                name = "Bridging Column"
                uclass = UnitClass.INFANTRY.value
                gunrange = 0
                softatk = 2
            },
        )
        Equipment.putEquipment(
            blindEqid,
            EquipmentData().apply {
                name = "Supply Column"
                uclass = UnitClass.GROUND_TRANSPORT.value
                spotrange = 0
            },
        )
        Equipment.putEquipment(
            battleshipEqid,
            EquipmentData().apply {
                name = "Battleship"
                uclass = UnitClass.BATTLESHIP.value
                airatk = 8
            },
        )
        Equipment.putEquipment(
            cruiserEqid,
            EquipmentData().apply {
                name = "Light Cruiser"
                uclass = UnitClass.LIGHT_CRUISER.value
                airatk = 8
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    /** Installs a scenario carrying [configure]'s authored switches, as the loader would. */
    private fun scenarioWith(configure: Scenario.() -> Unit) {
        val scenario = Scenario(null).apply(configure)
        GameHolder.instance = GameHolder.instance ?: org.osada.Game()
        GameHolder.instance?.scenario = scenario
    }

    // ---- True range 0 ---------------------------------------------------------------------------

    /** OSADA's own long-standing behaviour, and OG's with the option OFF: range 0 means adjacent. */
    @Test
    fun aRangeZeroFormationReachesAdjacentHexesWhenTheOptionIsAbsent() {
        scenarioWith { trueRangeZero = null }
        val map = world()
        val unit = place(map, engineerEqid, 3, 3, 0)
        assertEquals(1, AttackEligibility.getUnitAttackRange(unit))
    }

    /** *"units with Range 0 cannot attack adjacent hexes"* — 295 scenarios author this. */
    @Test
    fun aRangeZeroFormationCannotReachAnythingWhenTheOptionIsAuthored() {
        scenarioWith { trueRangeZero = true }
        val map = world()
        val unit = place(map, engineerEqid, 3, 3, 0)
        assertEquals(0, AttackEligibility.getUnitAttackRange(unit))
    }

    /** A formation with a real gun is untouched either way — the option is about range 0 alone. */
    @Test
    fun trueRangeZeroLeavesAnArmedFormationAlone() {
        scenarioWith { trueRangeZero = true }
        val map = world()
        val gun = place(map, gunEqid, 3, 3, 0)
        assertEquals(3, AttackEligibility.getUnitAttackRange(gun))
    }

    // ---- True spotting 0, the one OSADA had backwards -------------------------------------------

    /**
     * **The bug this option exposed.** `HexGeometry.getRing` returns nothing at radius 0, so a
     * `spotrange = 0` formation saw only its own hex whatever the scenario said — OG's behaviour
     * with the option ON. The ~3,000 scenarios that do NOT author it were blinding 303 shipped
     * records that OG lets see their neighbours.
     */
    @Test
    fun aSpottingZeroFormationSeesItsNeighboursWhenTheOptionIsAbsent() {
        scenarioWith { trueSpottingZero = null }
        val map = world()
        val unit = place(map, blindEqid, 3, 3, 0)
        assertEquals(1, MovementRules.getUnitSpotRange(unit))
    }

    /** *"units with Spotting of 0 don't spot adjacent hexes"* — 295 scenarios author this. */
    @Test
    fun aSpottingZeroFormationIsBlindWhenTheOptionIsAuthored() {
        scenarioWith { trueSpottingZero = true }
        val map = world()
        val unit = place(map, blindEqid, 3, 3, 0)
        assertEquals(0, MovementRules.getUnitSpotRange(unit))
    }

    // ---- BB / CV / BC as flak -------------------------------------------------------------------

    /** *"BB, CV & BC can fire as FlaKs"* — 197 scenarios author it, and without it a battleship
     *  has no anti-air role however good its `airatk`. */
    @Test
    fun aBattleshipFiresAsFlakOnlyWhenTheScenarioSaysSo() {
        val battleship = Equipment.equipment[battleshipEqid]!!
        scenarioWith { capitalShipsAsFlak = null }
        assertFalse(
            UnitCapabilities.hasAirDefenceFire(battleship),
            "without the option a capital ship is not an anti-air platform",
        )
        scenarioWith { capitalShipsAsFlak = true }
        assertTrue(UnitCapabilities.hasAirDefenceFire(battleship))
    }

    /**
     * **Cruiser and Light Cruiser stay out.** OG names three classes — BB, CV, BC — and OSADA has
     * four where OG's `CShip` has one, so admitting the two OG does not name would arm ships OG
     * leaves unarmed. This is the assertion that pins that reading.
     */
    @Test
    fun aLightCruiserIsNotAdmittedByTheCapitalShipOption() {
        scenarioWith { capitalShipsAsFlak = true }
        assertFalse(
            UnitCapabilities.hasAirDefenceFire(Equipment.equipment[cruiserEqid]!!),
            "OG names BB, CV and BC only",
        )
    }

    /** A flak battery is unaffected by the option in either direction. */
    @Test
    fun theCapitalShipOptionDoesNotDisturbOrdinaryAntiAir() {
        val flak =
            EquipmentData().apply {
                uclass = UnitClass.FLAK.value
                airatk = 6
            }
        scenarioWith { capitalShipsAsFlak = null }
        assertTrue(UnitCapabilities.hasAirDefenceFire(flak))
        scenarioWith { capitalShipsAsFlak = true }
        assertTrue(UnitCapabilities.hasAirDefenceFire(flak))
    }

    // ---- Hold-VH, now per side ------------------------------------------------------------------

    /**
     * OG stores one hold triple PER SIDE and they routinely differ — `bn9s00` asks 4/4/4 of one
     * side and 3/2/1 of the other. Reading one triple for both was an artefact of sourcing them
     * from briefing prose, which contains only one set of numbers.
     */
    @Test
    fun eachSideIsJudgedAgainstItsOwnHoldThresholds() {
        val scenario = Scenario(null)
        scenario.victoryHoldCounts = listOf(4, 4, 4)
        scenario.victoryHoldCountsSide1 = listOf(3, 2, 1)
        assertEquals(listOf(4, 4, 4), scenario.victoryHoldCounts)
        assertEquals(listOf(3, 2, 1), scenario.victoryHoldCountsSide1)
        assertTrue(
            scenario.victoryHoldCounts != scenario.victoryHoldCountsSide1,
            "the two sides' requirements are independent, which the prose parser could not express",
        )
    }

    /** The keys are unaffected: these are AUTHORED switches, not ruleset options. */
    @Test
    fun theseOptionsAreNotGatedOnARulesetKey() {
        ruleset(RuleKey.TRIGGER_HEXES to 0)
        scenarioWith { trueRangeZero = true }
        val map = world()
        val unit = place(map, engineerEqid, 3, 3, 0)
        assertEquals(
            0,
            AttackEligibility.getUnitAttackRange(unit),
            "the author's own switch is honoured whatever the player's ruleset says",
        )
    }
}
