package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's per-scenario **Game settings**, imported 2026-08-26 from the `.xscn` bitfield
 * (`docs/og-fidelity-plan.md` §O).
 *
 * OG opens §9.3 with *"All this options must be enabled by the scenario designer to work"*, and
 * §9.5 with *"When this scenario option is activated"*. Until these attributes existed, the Open
 * General Fidelity profile applied one set of engineering and sight rules to 502 scenarios that
 * authored their own — the `authored_options` gap.
 *
 * The third case is the one worth having a test for: a scenario whose source could not be read
 * carries **no** attribute, and must behave exactly as it did before the import rather than losing
 * a mechanic to silence.
 */
class AuthoredScenarioOptionsTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun aScenarioThatForbidsBuildingOffersNoConstructionEvenWithTheKeyOn() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBuild = false
        GameHolder.instance = holder
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.AIRFIELD in Engineering.availableWork(sapper))
        assertFalse(Engineering.authorisedByScenario(EngineeringWork.AIRFIELD))
    }

    @Test
    fun forbiddingOneSwitchLeavesTheOtherTwoAlone() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBuild = false
        holder.scenario?.canBlow = true
        holder.scenario?.canRepair = true
        GameHolder.instance = holder
        map.map!![2][2].terrain = TerrainType.CITY.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        val offered = Engineering.availableWork(sapper)
        assertTrue(EngineeringWork.RAZE in offered, "Can Blow is authorised, so the demolition stands")
        assertFalse(EngineeringWork.FORTIFICATION in offered, "Can Build is not")
    }

    @Test
    fun aScenarioThatForbidsBlowingKeepsItsBridges() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.canBlow = false
        GameHolder.instance = holder
        map.map!![2][2].terrain = TerrainType.RIVER.value
        map.map!![2][2].road = PARTIAL_ROAD_MASK
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(EngineeringWork.BLOW_BRIDGE in Engineering.availableWork(sapper))
    }

    @Test
    fun anUnimportedScenarioKeepsEveryJobItHadBefore() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        // Every switch left null: the 105 deployed scenarios whose `.xscn` cannot be read or found.
        GameHolder.instance = holderFor(map)
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(EngineeringWork.AIRFIELD in Engineering.availableWork(sapper))
        assertTrue(Engineering.authorisedByScenario(EngineeringWork.REPAIR))
    }

    @Test
    fun extendedLosNeedsBothTheKeyAndTheScenario() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        val holder = holderFor(map)
        GameHolder.instance = holder

        holder.scenario?.extendedLos = null
        assertTrue(ExtendedLos.enabled(), "an unimported scenario follows the key alone")

        holder.scenario?.extendedLos = true
        assertTrue(ExtendedLos.enabled())

        holder.scenario?.extendedLos = false
        assertFalse(ExtendedLos.enabled(), "OG: 'when this scenario option is activated'")
    }

    // ---- the two line-of-fire options, built 2026-08-26 (§T) -----------------------------------

    /**
     * OG's `TrueDLOF`: *"Mountains,Cities && Forest blocks direct LOF even if range>2"* (string
     * template line 575), so **without** it the terrain check stops at two hexes.
     *
     * Asserted at both ranges from the same mountain, because the bug this replaces was that the
     * check had no range bound at all: every one of the 190 `extlos` scenarios that do NOT set
     * `TrueDLOF` was getting the option's behaviour anyway.
     */
    @Test
    fun trueDlofDecidesHowFarTerrainCutsTheLineOfFire() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        val holder = holderFor(map)
        GameHolder.instance = holder
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val shooter = place(map, riflemanEqid, 2, 2, side = 0)
        val near = place(map, infantryEqid, 2, 4, side = 1)
        val far = place(map, infantryEqid, 2, 6, side = 1)

        holder.scenario?.trueDirectLof = false
        assertFalse(
            AttackEligibility.isInAttackRange(shooter, near),
            "range 2 is inside §6.18's own reach, so the mountain blocks it with or without the option",
        )
        assertTrue(
            AttackEligibility.isInAttackRange(shooter, far),
            "range 4 is past it: without TrueDLOF the terrain check does not reach",
        )

        holder.scenario?.trueDirectLof = true
        assertFalse(AttackEligibility.isInAttackRange(shooter, far), "with TrueDLOF it reaches any range")
    }

    /** An unreadable scenario gets OG's default for an unset bit, which here is also the reading
     *  that leaves the player the most legal attacks. */
    @Test
    fun anUnimportedScenarioGetsTheShorterLineOfFire() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val shooter = place(map, riflemanEqid, 2, 2, side = 0)
        val far = place(map, infantryEqid, 2, 6, side = 1)

        assertTrue(
            AttackEligibility.isInAttackRange(shooter, far),
            "TrueDLOF left null must not silently behave as though the author had set it",
        )
    }

    /**
     * §6.18 opens by separating *"ranged fire"* from *"artillery and air defense fire"* and then
     * cuts the line of fire of *"these units"* — the former. A battery shooting over a mountain is
     * the case OSADA got wrong until §T.
     */
    @Test
    fun terrainNeverCutsArtilleryFire() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        val holder = holderFor(map)
        GameHolder.instance = holder
        holder.scenario?.trueDirectLof = true
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value
        val battery = place(map, gunEqid, 2, 2, side = 0)
        val rifleman = place(map, riflemanEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)

        assertTrue(AttackEligibility.isInAttackRange(battery, target), "artillery is exempt from §6.18")
        assertFalse(AttackEligibility.isInAttackRange(rifleman, target), "ranged fire is not")
    }

    /**
     * OG's `UnitsBlockDLOF`: *"Friend Units ALSO block LOF (except if light special)"* (template
     * line 576), where the special is `Allow LOF` (*"unit doesn't cut LOF"*, line 864).
     *
     * The screen here carries NEITHER attribute — an ordinary formation, which is the whole point:
     * with the option off it blocks nothing, and turning it on is what gives `Allow LOF` its 561
     * records something to be an exception to.
     */
    @Test
    fun unitsBlockDlofMakesEveryFormationBlockAndAllowLofTheExceptionToIt() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        val holder = holderFor(map)
        GameHolder.instance = holder
        val shooter = place(map, riflemanEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        place(map, infantryEqid, 2, 3, side = 0)

        holder.scenario?.unitsBlockLof = false
        assertTrue(
            AttackEligibility.isInAttackRange(shooter, target),
            "with the option off only a Cut LOS unit blocks, exactly as before §T",
        )

        holder.scenario?.unitsBlockLof = true
        assertFalse(AttackEligibility.isInAttackRange(shooter, target), "with it on, a friendly screen blocks")

        Equipment.putEquipment(
            infantryEqid + 70,
            EquipmentData().apply {
                name = "Skirmish Screen"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                attr2 = ATTR2_ALLOW_LOF
            },
        )
        map.map!![2][3].setUnit(null)
        place(map, infantryEqid + 70, 2, 3, side = 0)
        assertTrue(
            AttackEligibility.isInAttackRange(shooter, target),
            "Allow LOF is UnitsBlockDLOF's only exception",
        )
    }

    /** An unreadable scenario must not invent an extra rank of blockers for itself. */
    @Test
    fun anUnimportedScenarioDoesNotMakeEveryUnitBlockFire() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val shooter = place(map, riflemanEqid, 2, 2, side = 0)
        val target = place(map, infantryEqid, 2, 4, side = 1)
        place(map, infantryEqid, 2, 3, side = 0)

        assertTrue(AttackEligibility.isInAttackRange(shooter, target))
    }

    /**
     * OG's `no prototypes`, wired 2026-08-29 (§AF). 43 of the 397 deployed scenarios whose source
     * parses forbid the award; the attribute is deployed INVERTED so `1` reads as "allowed", which
     * is what lets an unreadable source fall through as permitted with no special case.
     */
    @Test
    fun aScenarioThatForbidsPrototypesReadsAsForbidden() {
        val holder = holderFor(world())
        holder.scenario?.prototypesAllowed = false
        GameHolder.instance = holder

        assertFalse(holder.scenario?.prototypesAllowed != false, "the award gate must refuse")
    }

    @Test
    fun anUnreadableScenarioStillAwardsPrototypes() {
        val holder = holderFor(world())
        // Null is "source could not be read" -- 105 of the 502 deployed files.
        holder.scenario?.prototypesAllowed = null
        GameHolder.instance = holder

        assertTrue(holder.scenario?.prototypesAllowed != false, "silence is permission (§AD)")
    }

    /**
     * OG's `Subs no need DLOF` exempts submarines from `ExtendedNaval`'s bullet 4.
     *
     * Zero deployed scenarios author it, so this test is the only thing exercising the branch --
     * which is exactly why it exists. The rule it overrides was already built, so the whole cost of
     * honouring the switch was one condition.
     */
    @Test
    fun aScenarioMayExemptSubmarinesFromNeedingLineOfFire() {
        val holder = holderFor(world())
        holder.scenario?.subsNeedLineOfFire = false
        GameHolder.instance = holder

        assertFalse(
            holder.scenario?.subsNeedLineOfFire != false,
            "the exemption must reach ExtendedNaval.submarineLacksLineOfFire",
        )
    }

    @Test
    fun theScenarioCannotTurnARuleOnThatTheRulesetHasOff() {
        ruleset(RuleKey.EXTENDED_LOS to 0, RuleKey.BUILD_AND_REPAIR to 0)
        val map = world(prestige = 100)
        val holder = holderFor(map)
        holder.scenario?.extendedLos = true
        holder.scenario?.canBuild = true
        GameHolder.instance = holder
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(ExtendedLos.enabled())
        assertEquals(emptyList(), Engineering.availableWork(sapper))
    }
}
