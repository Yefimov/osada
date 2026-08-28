package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG §6.30's *"Air units **usually** don't have a zone of control"* — the scenario option behind
 * that one word, wired 2026-08-27 as schema 10 (`docs/og-fidelity-plan.md` §U).
 *
 * The option has been imported since §O and read by nothing, which is why the OFF cases here are
 * the ones that would have passed before: they pin that an aircraft still controls nothing unless
 * both the key and the scenario ask for it.
 */
class AirZoneOfControlTest : OgRulesTestHarness() {
    private val fighterEqid = 980
    private val quietFighterEqid = 981

    /** OG's `No ZOC`, `attr2` bit 6 — the record-level refusal this option must not override. */
    private val attr2NoZoc = 64

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(fighterEqid, plane("Fighter", bits = 0))
        Equipment.putEquipment(quietFighterEqid, plane("Recon Aircraft", bits = attr2NoZoc))
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun plane(
        label: String,
        bits: Int,
    ) = EquipmentData().apply {
        name = label
        uclass = UnitClass.FIGHTER.value
        target = UnitType.AIR.value
        movmethod = MovMethod.AIR.value
        movpoints = 10
        ammo = 6
        airatk = 10
        airdef = 8
        attr2 = bits
    }

    private fun withAirZoc(
        key: Boolean,
        authored: Boolean? = null,
    ): GameMap {
        ruleset(RuleKey.AIR_ZOC to if (key) 1 else 0)
        val map = world()
        val holder = holderFor(map)
        holder.scenario?.airZoc = authored
        GameHolder.instance = holder
        return map
    }

    @Test
    fun anAircraftControlsNothingByDefault() {
        val map = withAirZoc(key = false)
        val fighter = place(map, fighterEqid, 2, 2, side = 0)

        assertFalse(UnitCapabilities.projectsZoneOfControl(fighter))
    }

    @Test
    fun theKeyAloneIsNotEnoughWhenTheScenarioRefuses() {
        val map = withAirZoc(key = true, authored = false)
        val fighter = place(map, fighterEqid, 2, 2, side = 0)

        assertFalse(UnitCapabilities.projectsZoneOfControl(fighter), "the author decides, as OG has it")
    }

    @Test
    fun aScenarioThatAsksForItGetsIt() {
        val map = withAirZoc(key = true, authored = true)
        val fighter = place(map, fighterEqid, 2, 2, side = 0)

        assertTrue(UnitCapabilities.projectsZoneOfControl(fighter))
    }

    @Test
    fun anUnreadableScenarioFollowsTheKeyAlone() {
        val map = withAirZoc(key = true, authored = null)
        val fighter = place(map, fighterEqid, 2, 2, side = 0)

        assertTrue(
            UnitCapabilities.projectsZoneOfControl(fighter),
            "a rule-level switch reads silence as permission, as extlos and extnaval do",
        )
    }

    @Test
    fun noZocOnTheRecordStillBeatsTheOption() {
        val map = withAirZoc(key = true, authored = true)
        val quiet = place(map, quietFighterEqid, 2, 2, side = 0)

        assertFalse(
            UnitCapabilities.projectsZoneOfControl(quiet),
            "a record that says it projects none projects none, whatever class it is",
        )
    }

    @Test
    fun theZoneReachesTheSixAdjacentHexesAndNoFurther() {
        // `place` goes through `GameMap.addUnit`, which is the engine's own single "a unit arrived"
        // path and already calls `setZOCRange(add = true)`. Adding a second one here would test a
        // reference count no real code path produces.
        val map = withAirZoc(key = true, authored = true)
        place(map, fighterEqid, 3, 3, side = 0)

        assertTrue(map.map!![3][4].isZOC(0), "an adjacent hex is controlled")
        assertFalse(map.map!![3][6].isZOC(0), "two hexes away is not")
    }

    @Test
    fun theZoneIsRemovedExactlyAsItWasAdded() {
        val map = withAirZoc(key = true, authored = true)
        val fighter = place(map, fighterEqid, 3, 3, side = 0)
        MovementRules.setZOCRange(map, fighter, add = false)

        assertFalse(
            map.map!![3][4].isZOC(0),
            "the reference count must return to zero, or the map keeps a ZOC no toggle can clear",
        )
    }

    @Test
    fun anAircraftAddsNoZoneToRemoveWhenTheOptionIsOff() {
        val map = withAirZoc(key = false)
        val fighter = place(map, fighterEqid, 3, 3, side = 0)
        MovementRules.setZOCRange(map, fighter, add = false)

        assertFalse(map.map!![3][4].isZOC(0), "the add was skipped, so the remove must be too")
    }
}
