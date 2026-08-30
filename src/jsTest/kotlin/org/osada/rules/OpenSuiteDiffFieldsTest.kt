package org.osada.rules

import org.osada.TerrainType
import org.osada.model.ATTR2_MASK_NO_DIRT_AIRFIELDS
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three mechanics unblocked on 2026-08-29 by the owner's controlled OpenSuite diffs.
 *
 * `docs/og-fidelity-plan.md` §Y.1 had listed all three as *"blocked on a controlled OpenSuite diff —
 * a human must take these"* for four days. The offsets are in `SCENARIO_FORMAT_NOTES.md`; this
 * suite is about what the engine does with them now that they arrive.
 *
 * Each of the three is tested for the same two things: that it DOES what OG says, and that it is
 * OFF where it should be off. The second half is the one that would catch the failure mode this
 * project keeps hitting — a field imported and then read by nothing, or read unconditionally when
 * it should have been gated.
 */
class OpenSuiteDiffFieldsTest : OgRulesTestHarness() {
    private val jetEqid = 960
    private val propEqid = 961

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            jetEqid,
            EquipmentData().apply {
                name = "Jet Fighter"
                // OG's `Cannot use dirt airfields`, `attr2` bit 2.
                attr2 = ATTR2_MASK_NO_DIRT_AIRFIELDS
            },
        )
        Equipment.putEquipment(
            propEqid,
            EquipmentData().apply { name = "Biplane Squadron" },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun airfield(
        map: GameMap,
        row: Int,
        col: Int,
    ) = map.map!![row][col].apply { terrain = TerrainType.AIRFIELD.value }

    // ---- Dirt airfields: `.xscn` grid @19 bit 6 -------------------------------------------------

    /**
     * The half of OG's sentence that was unbuildable until the flag was found.
     *
     * *"unit can't refuel nor deploy in airfields defined as dirt or built by sappers"* — an
     * AUTHORED dirt field, which `AirfieldQuality` could not see before because no per-hex marking
     * had been located.
     */
    @Test
    fun authoredDirtFieldRefusesAJetThatNeedsARunway() {
        val map = world()
        val hex = airfield(map, 2, 2).apply { dirt = true }
        assertTrue(
            AirfieldQuality.unusableBy(hex, Equipment.equipment[jetEqid]!!),
            "an authored dirt strip must refuse a `Cannot use dirt airfields` aircraft",
        )
        assertFalse(
            AirfieldQuality.unusableBy(hex, Equipment.equipment[propEqid]!!),
            "the same strip must still serve an aircraft without the ability",
        )
    }

    /**
     * The gate correction, and the reason this test exists rather than just the one above.
     *
     * The ability used to be gated wholesale on `Engineering.enabled()`, which was right while a
     * sapper strip was the only kind of dirt field there was. An AUTHORED one is on the map
     * whatever the ruleset says, so the same gate would now let a jet refuel on it in every
     * default game.
     */
    @Test
    fun authoredDirtIsRefusedEvenWithEngineeringOff() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 0)
        val map = world()
        val authored = airfield(map, 2, 2).apply { dirt = true }
        val sapperStrip = airfield(map, 3, 3).apply { sapperBuilt = true }
        val jet = Equipment.equipment[jetEqid]!!
        assertTrue(
            AirfieldQuality.unusableBy(authored, jet),
            "an authored dirt strip does not depend on `build_and_repair`",
        )
        assertFalse(
            AirfieldQuality.unusableBy(sapperStrip, jet),
            "a sapper strip cannot exist with engineering off, so it must not be treated as dirt",
        )
    }

    // ---- The scenario Depot: `.xscn` unit @50 bit 1 ---------------------------------------------

    /**
     * OG's OLDER way to make a Depot — the designer marking a placed formation — which
     * `depot_flag_hunt.py` failed to find over 328,638 records and a controlled diff found in one
     * sitting.
     */
    @Test
    fun aScenarioDesignatedDepotIsADepotWithoutAnyEquipmentSupport() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        val map = world()
        val designated = place(map, truckEqid, 2, 2, 0).apply { isScenarioDepot = true }
        val ordinary = place(map, truckEqid, 2, 3, 0)
        assertTrue(
            DepotSupply.isDepot(designated),
            "the scenario designation alone must make a Depot -- no shipped record carries `Supply Unit`",
        )
        assertFalse(DepotSupply.isDepot(ordinary), "an undesignated truck of the same equipment is not one")
    }

    /** A destroyed formation supplies nobody, designated or not. */
    @Test
    fun aDestroyedDepotIsNotADepot() {
        ruleset(RuleKey.DEPOT_SUPPLY to 1)
        val map = world()
        val depot = place(map, truckEqid, 2, 2, 0).apply { isScenarioDepot = true }
        depot.destroyed = true
        assertFalse(DepotSupply.isDepot(depot), "a destroyed Depot must not still supply")
    }

    // ---- Trigger hexes: `.xscn` grid @20 / @21 / @22 / @26 --------------------------------------

    private fun triggerHex(
        map: GameMap,
        type: Int,
        param: Int = 0,
        message: String = "",
    ) = map.map!![4][4].apply {
        trigger = type
        triggerParam = param
        triggerMessage = message
        // `trigger_ex` defaults to 0 -- "trigger needs an owner and only units of a DIFFERENT
        // player can activate it" -- so an ownerless hex is inert and every test below would be
        // asserting nothing. 734 of the corpus's 858 trigger hexes are owned by player 2, which is
        // this fixture's hostile side.
        owner = hostile.id
    }

    /** *"the player receives extra prestige points"*, with OG's literal parameter. */
    @Test
    fun aPrestigeTriggerPaysItsAuthoredAmountOnce() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world(prestige = 100)
        val hex = triggerHex(map, TriggerHexes.PRESTIGE, param = 250, message = "A supply dump!")
        val unit = place(map, infantryEqid, 4, 4, 0)

        assertEquals("A supply dump!", TriggerHexes.fire(map, unit, hex))
        assertEquals(350, friendly.prestige, "the authored parameter is the literal amount")

        assertNull(TriggerHexes.fire(map, unit, hex), "a spent trigger must not fire again")
        assertEquals(350, friendly.prestige, "and must not pay again")
    }

    /**
     * The once-only reading is an INFERENCE (`Hex.triggerFired`), so it gets its own assertion
     * rather than riding on the one above: every action is a gift, and a repeating trigger would be
     * a tap the player farms by stepping off the hex and back on.
     */
    @Test
    fun aSpentTriggerStaysSpentForADifferentUnitToo() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world()
        val hex = triggerHex(map, TriggerHexes.PRESTIGE, param = 100)
        val first = place(map, infantryEqid, 4, 4, 0)
        TriggerHexes.fire(map, first, hex)
        val second = place(map, truckEqid, 4, 5, 0)
        assertNull(TriggerHexes.fire(map, second, hex), "the hex pays the first arrival, not every arrival")
        assertEquals(100, friendly.prestige)
    }

    /** *"the unit receives a leader, **if it hasn't one**"* — OG's own condition. */
    @Test
    fun aLeaderTriggerSkipsAFormationThatAlreadyHasOne() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world()
        val hex = triggerHex(map, TriggerHexes.LEADER, message = "An officer joins")
        val unit = place(map, infantryEqid, 4, 4, 0).apply { leader = 3 }
        assertNull(TriggerHexes.fire(map, unit, hex), "nothing happened, so there is nothing to announce")
        assertEquals(3, unit.leader, "the existing commander must not be replaced")
    }

    /** *"the unit receives extra experience points"*, clamped to the engine's own ceiling. */
    @Test
    fun anExperienceTriggerAwardsItsAuthoredAmount() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world()
        val hex = triggerHex(map, TriggerHexes.EXPERIENCE, param = 120)
        val unit = place(map, infantryEqid, 4, 4, 0).apply { experience = 50 }
        TriggerHexes.fire(map, unit, hex)
        assertEquals(170, unit.experience)
        assertTrue(unit.experience <= UnitExperience.cap(), "an award must not exceed the experience ceiling")
    }

    /**
     * **Change AI stance is imported and deliberately not executed** — §0.2 blocks any AI-stance
     * work until a benchmark suite exists. It must still be CONSUMED, so it cannot sit armed and
     * re-fire once the model arrives and change the scenario retroactively.
     */
    @Test
    fun theAiStanceActionIsConsumedWithoutDoingAnything() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world(prestige = 40)
        val hex = triggerHex(map, TriggerHexes.AI_STANCE, message = "The enemy hesitates")
        val unit = place(map, infantryEqid, 4, 4, 0)
        assertNull(TriggerHexes.fire(map, unit, hex), "nothing was applied, so nothing is announced")
        assertEquals(40, friendly.prestige, "the refused action must not pay out as some other one")
        assertFalse(TriggerHexes.isArmed(hex), "but it is spent, so it cannot fire later under a new model")
    }

    /** The key gates the whole mechanic, and the default ruleset leaves it off. */
    @Test
    fun noTriggerFiresWithTheKeyOff() {
        ruleset(RuleKey.TRIGGER_HEXES to 0)
        val map = world(prestige = 10)
        val hex = triggerHex(map, TriggerHexes.PRESTIGE, param = 100, message = "A supply dump!")
        val unit = place(map, infantryEqid, 4, 4, 0)
        assertNull(TriggerHexes.fire(map, unit, hex))
        assertEquals(10, friendly.prestige, "the key off means the map's triggers do nothing at all")
        assertTrue(TriggerHexes.isArmed(hex), "and it is not consumed either -- turning the key on must arm it")
    }

    /**
     * `trigger_ex = 0` (OG's default): *"trigger needs an owner and only units of a different
     * player can activate it"*. The owner is the hex's own, not a field of its own.
     */
    @Test
    fun theOwningPlayersOwnFormationCannotSetOffItsTrigger() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world(prestige = 7)
        val hex = triggerHex(map, TriggerHexes.PRESTIGE, param = 100)
        val ownersOwn = place(map, infantryEqid, 4, 4, 1)
        assertNull(TriggerHexes.fire(map, ownersOwn, hex), "the hex's owner collects nothing")
        assertTrue(TriggerHexes.isArmed(hex), "and does not consume it either")

        val other = place(map, infantryEqid, 4, 5, 0)
        assertFalse(other.player?.id == hex.owner)
        TriggerHexes.fire(map, other, hex)
        assertEquals(107, friendly.prestige, "the other side walks in and collects it")
    }

    /** An UNOWNED hex is inert under the default, which is the literal reading of "needs an
     *  owner" and the one that cannot hand out a contested reward for free. */
    @Test
    fun anUnownedTriggerIsInertUnderTheDefault() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world()
        val hex = triggerHex(map, TriggerHexes.PRESTIGE, param = 100).apply { owner = -1 }
        val unit = place(map, infantryEqid, 4, 4, 0)
        assertFalse(TriggerHexes.activatableBy(unit, hex))
        assertNull(TriggerHexes.fire(map, unit, hex))
    }

    /** A hex with no authored trigger is not one, whatever the key says. */
    @Test
    fun anOrdinaryHexIsNotATrigger() {
        ruleset(RuleKey.TRIGGER_HEXES to 1)
        val map = world()
        val plain = map.map!![4][4]
        val unit: GameUnit = place(map, infantryEqid, 4, 4, 0)
        assertFalse(TriggerHexes.isArmed(plain))
        assertNull(TriggerHexes.fire(map, unit, plain))
    }
}
