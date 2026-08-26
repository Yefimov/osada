package org.osada.rules

import org.osada.GameHolder
import org.osada.GameStateSerializer
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.EngineeringActionResult
import org.osada.model.Hex
import org.osada.model.beginEngineering
import org.osada.restoreEngineering
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Open General's **Build and Repair** optional rule (manual §9.3), split out of
 * [OgOptionalRulesTest] on 2026-08-26 when the two together outgrew detekt's class-size budget.
 * The fixture is [OgRulesTestHarness]; the other two schema-6 optional rules stay there.
 *
 * **With `build_and_repair` off the mechanic does not exist**, which is the first thing asserted
 * here and the state all 502 shipped scenarios run in. The rest is in two groups: what the rule
 * does when it is on, and the defects two review passes found in it — a Repair that built free
 * bridges, a facility nobody owned, a facility that offered to demolish itself, and work that
 * forgot who was paying for it.
 */
class OgEngineeringRulesTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun tearDown() = clearTestWorld()

    // ---- Build and Repair (OG 9.3) ---------------------------------------------------------------

    @Test
    fun engineeringDoesNothingWithTheKeyOff() {
        val map = world()
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(Engineering.enabled())
        assertEquals(emptyList(), Engineering.availableWork(sapper))
        assertEquals(
            EngineeringActionResult.NOT_ALLOWED,
            map.beginEngineering(sapper, EngineeringWork.BRIDGE),
        )
    }

    @Test
    fun aSapperBridgesARiverOverSeveralTurnsAndPaysForIt() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(EngineeringWork.BRIDGE in Engineering.availableWork(sapper))
        assertEquals(EngineeringActionResult.STARTED, map.beginEngineering(sapper, EngineeringWork.BRIDGE))
        assertEquals(100 - EngineeringWork.BRIDGE.cost, friendly.prestige, "the cost is charged at the start")
        assertTrue(sapper.hasMoved && sapper.hasFired, "starting work spends the whole turn")
        assertEquals(RoadType.NONE.value, map.map!![2][2].road, "nothing exists until the work finishes")

        repeat(EngineeringWork.BRIDGE.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertTrue(map.map!![2][2].road > 0, "the bridge is there once the countdown runs out")
    }

    /** The other side's turn end must not advance work this side is paying for. */
    @Test
    fun onlyTheBuildingSideAdvancesItsOwnWork() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        map.beginEngineering(sapper, EngineeringWork.BRIDGE)

        val before = map.map!![2][2].constructionTurns
        Engineering.advanceTurn(map.map, 1, hostileOwner())
        assertEquals(before, map.map!![2][2].constructionTurns, "side 1's turn end leaves side 0's bridge alone")
    }

    @Test
    fun demolitionIsInstantAndRepairPutsItBack() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.CITY.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertTrue(EngineeringWork.RAZE in Engineering.availableWork(sapper))
        assertEquals(EngineeringActionResult.DEMOLISHED, map.beginEngineering(sapper, EngineeringWork.RAZE))
        assertEquals(TerrainType.CLEAR.value, map.map!![2][2].terrain, "a demolition takes effect at once")
        assertEquals(TerrainType.CITY.value, map.map!![2][2].razedTerrain, "and records what to put back")

        sapper.hasMoved = false
        sapper.hasFired = false
        assertTrue(EngineeringWork.REPAIR in Engineering.availableWork(sapper))
        map.beginEngineering(sapper, EngineeringWork.REPAIR)
        repeat(EngineeringWork.REPAIR.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertEquals(TerrainType.CITY.value, map.map!![2][2].terrain, "repair restores the exact terrain")
    }

    /** OG: the unit *"hasn't done any action"*. A sapper that walked to the river this turn waits. */
    @Test
    fun aSapperThatHasAlreadyActedCannotStartWork() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)
        sapper.hasMoved = true

        assertEquals(
            EngineeringActionResult.NOT_ALLOWED,
            map.beginEngineering(sapper, EngineeringWork.BRIDGE),
        )
    }

    /** Terrain decides what can be built, so no chip ever offers a meaningless order. */
    @Test
    fun terrainDecidesWhichJobsAreOffered() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        val onClearGround = Engineering.availableWork(sapper)
        assertTrue(EngineeringWork.AIRFIELD in onClearGround, "an airfield needs clear ground, and this is clear")
        assertFalse(EngineeringWork.BRIDGE in onClearGround, "there is no crossing to bridge")
        assertFalse(EngineeringWork.PORT in onClearGround, "and no water to put a port on")
        assertFalse(EngineeringWork.RAZE in onClearGround, "clear ground has no feature to raze")
    }

    /** A formation with neither ability is offered nothing, whatever it is standing on. */
    @Test
    fun aFormationWithoutTheAbilitiesIsOfferedNothing() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val riflemen = place(map, infantryEqid, 2, 2, side = 0)

        assertEquals(emptyList(), Engineering.availableWork(riflemen))
    }

    /**
     * Repair may only rebuild what was actually destroyed.
     *
     * It was offered on any river with no road and laid a full bridge mask, so a sapper could
     * build a crossing for nothing by pressing Repair instead of the 16-prestige Build Bridge.
     */
    @Test
    fun repairCannotBridgeARiverThatWasNeverBridged() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        map.map!![2][2].terrain = TerrainType.RIVER.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        assertFalse(
            EngineeringWork.REPAIR in Engineering.availableWork(sapper),
            "nothing was destroyed here, so there is nothing to repair",
        )
        assertTrue(EngineeringWork.BRIDGE in Engineering.availableWork(sapper), "building one is offered")
    }

    /** ...and after a bridge really is blown, Repair restores the mask it had, not a full one. */
    @Test
    fun repairRestoresExactlyTheBridgeThatWasBlown() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.terrain = TerrainType.RIVER.value
        hex.road = PARTIAL_ROAD_MASK
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.BLOW_BRIDGE)
        assertEquals(RoadType.NONE.value, hex.road, "the crossing is gone")
        assertEquals(PARTIAL_ROAD_MASK, hex.blownRoad, "and what it was is remembered")

        sapper.hasMoved = false
        sapper.hasFired = false
        assertTrue(EngineeringWork.REPAIR in Engineering.availableWork(sapper))
        map.beginEngineering(sapper, EngineeringWork.REPAIR)
        repeat(EngineeringWork.REPAIR.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertEquals(PARTIAL_ROAD_MASK, hex.road, "the original mask comes back, not an invented full one")
        assertEquals(0, hex.blownRoad, "and the record of the loss is spent")
    }

    /**
     * A built airfield has to actually be an airfield — driven through `GameMap.endTurn`.
     *
     * `MovementRules.hasAirfield` compares `hex.flag` against the unit's country, so a field
     * raised on unflagged ground was scenery: aircraft could neither base nor resupply on the
     * thing the help text had just promised them.
     *
     * The countdown is run by ending turns rather than by calling `Engineering.advanceTurn`
     * directly, because the ownership half only exists on the production path: `endTurn` is what
     * decides which player is paying and hands `advanceEngineering` its owner. Each of the
     * builder's turns costs two `endTurn` calls, since the opponent's turn end lies between them
     * and must not advance the job.
     */
    @Test
    fun aBuiltAirfieldBelongsToTheSideThatPaidForIt() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.flag = -1
        hex.owner = -1
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.AIRFIELD)
        repeat(EngineeringWork.AIRFIELD.turns) {
            map.endTurn()
            map.endTurn()
        }

        assertEquals(TerrainType.AIRFIELD.value, hex.terrain)
        assertEquals(friendly.country, hex.flag, "an unflagged airfield serves nobody")
        assertEquals(friendly.id, hex.owner, "and the opponent takes it the ordinary way, by capture")
    }

    /**
     * The builder is remembered, so an ALLY's turn end neither advances the job nor takes it.
     *
     * Multi-turn work used to store only the paying SIDE. Two allied players on one side would
     * therefore each tick every job the side was paying for — a two-turn bridge finishing inside
     * one round — and the finished facility was flagged to whichever of them happened to end the
     * turn it completed on. No shipped scenario has two players on a side, so this was latent;
     * hot-seat play is what it was documented as supporting. Found in review 2026-08-26.
     */
    @Test
    fun anAlliedPlayerNeitherAdvancesNorInheritsAnotherPlayersWork() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.flag = -1
        hex.owner = -1
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.AIRFIELD)
        assertEquals(friendly.id, hex.constructionPlayer, "the job knows who began it")
        assertEquals(friendly.country, hex.constructionCountry)

        // The ally is on the SAME side, and that used to be the whole test the tick applied.
        val remaining = hex.constructionTurns
        repeat(EngineeringWork.AIRFIELD.turns) { Engineering.advanceTurn(map.map, friendly.side, allyOwner()) }
        assertEquals(remaining, hex.constructionTurns, "an ally on the same side does not tick it")
        assertEquals(TerrainType.CLEAR.value, hex.terrain, "so nothing of theirs finishes on the ally's turn")

        repeat(EngineeringWork.AIRFIELD.turns) { Engineering.advanceTurn(map.map, friendly.side, builderOwner()) }
        assertEquals(TerrainType.AIRFIELD.value, hex.terrain, "the builder's own turns finish it")
        assertEquals(friendly.country, hex.flag, "and it flies the builder's flag")
        assertEquals(-1, hex.constructionPlayer, "the finished job releases its builder")
    }

    /**
     * The fallback, which is the only thing `constructionSide` is still kept for: a job restored
     * from a save written before the builder was recorded has no builder, so it ticks on its
     * SIDE's turn end and takes the flag of whoever ended that turn. Better than a job that never
     * finishes and an airfield with no flag, which is what dropping the fallback would produce.
     */
    @Test
    fun aBuilderlessJobFromAnOlderSaveStillFinishes() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.flag = -1
        hex.owner = -1
        hex.construction = EngineeringWork.FORTIFICATION.ordinal
        hex.constructionTurns = 1
        hex.constructionSide = friendly.side

        assertEquals(1, Engineering.advanceTurn(map.map, friendly.side, builderOwner()).size)
        assertEquals(TerrainType.FORTIFICATION.value, hex.terrain)
        assertEquals(friendly.country, hex.flag, "an unflagged facility would be scenery")
    }

    /**
     * A freshly finished facility is not a destroyed one.
     *
     * Construction recorded the terrain it covered in `razedTerrain`, and `razedTerrain >= 0` is
     * exactly how Repair asks whether anything was destroyed here — so completing an airfield
     * handed the player a Repair chip that took it back down to the ground it stood on. The
     * second half is the same defect from the other direction: raze a forest, build on the clear
     * hex it leaves, and the forest's record would otherwise still be waiting under the finished
     * work. Found in review 2026-08-26.
     */
    @Test
    fun aFreshlyBuiltFacilityIsNotOfferedRepair() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.AIRFIELD)
        repeat(EngineeringWork.AIRFIELD.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }
        assertEquals(TerrainType.AIRFIELD.value, hex.terrain)

        sapper.hasMoved = false
        sapper.hasFired = false
        assertEquals(-1, hex.razedTerrain, "nothing was destroyed to build this")
        assertFalse(
            EngineeringWork.REPAIR in Engineering.availableWork(sapper),
            "a new airfield must not offer to be repaired back into clear ground",
        )
    }

    /** The other direction: building over razed ground SPENDS the record, so the thing that was
     *  demolished cannot come back underneath the work that replaced it. */
    @Test
    fun buildingOverRazedGroundSettlesWhatTheHexIs() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.terrain = TerrainType.FOREST.value
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.RAZE)
        assertEquals(TerrainType.FOREST.value, hex.razedTerrain, "the forest is remembered while it is gone")

        sapper.hasMoved = false
        sapper.hasFired = false
        map.beginEngineering(sapper, EngineeringWork.AIRFIELD)
        repeat(EngineeringWork.AIRFIELD.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }

        assertEquals(TerrainType.AIRFIELD.value, hex.terrain)
        assertEquals(-1, hex.razedTerrain, "the airfield settles what that hex is now")
        sapper.hasMoved = false
        sapper.hasFired = false
        assertFalse(EngineeringWork.REPAIR in Engineering.availableWork(sapper), "and no forest waits under it")
    }

    /** The same rule for the road half: a bridge built where one was blown spends that record, so
     *  Repair is not offered on the new crossing and cannot downgrade its mask. */
    @Test
    fun aRebuiltBridgeIsNotOfferedRepair() {
        ruleset(RuleKey.BUILD_AND_REPAIR to 1)
        val map = world(prestige = 100)
        GameHolder.instance = holderFor(map)
        val hex = map.map!![2][2]
        hex.terrain = TerrainType.RIVER.value
        hex.road = PARTIAL_ROAD_MASK
        val sapper = place(map, sapperEqid, 2, 2, side = 0)

        map.beginEngineering(sapper, EngineeringWork.BLOW_BRIDGE)
        sapper.hasMoved = false
        sapper.hasFired = false
        map.beginEngineering(sapper, EngineeringWork.BRIDGE)
        repeat(EngineeringWork.BRIDGE.turns) { Engineering.advanceTurn(map.map, 0, builderOwner()) }

        assertTrue(hex.road > PARTIAL_ROAD_MASK, "the built bridge carries the full mask")
        assertEquals(0, hex.blownRoad, "and the record of the loss is spent by rebuilding")

        sapper.hasMoved = false
        sapper.hasFired = false
        assertFalse(
            EngineeringWork.REPAIR in Engineering.availableWork(sapper),
            "there is a crossing here, so there is nothing to repair",
        )
    }

    /**
     * A job in progress survives a save: through the real serializer and the real restore.
     *
     * The name/ordinal helpers are asserted here too, because the FORMAT is the point — an ordinal
     * is a position in `EngineeringWork`, and inserting a job mid-enum would silently reinterpret
     * every save carrying one. But the helpers alone cannot catch a writer and a reader
     * disagreeing about a KEY, which is how the builder could have been dropped, so the round trip
     * runs through `serializeHex` and `restoreEngineering` with a JSON hop between them.
     */
    @Test
    fun workInProgressRoundTripsThroughTheSerializer() {
        assertEquals(EngineeringWork.PORT.ordinal, Engineering.workOrdinal("PORT"))
        assertEquals(-1, Engineering.workOrdinal("A_JOB_THIS_BUILD_DOES_NOT_HAVE"))
        assertEquals(-1, Engineering.workOrdinal(null), "absent is not job zero")

        val source = Hex(2, 2)
        source.terrain = TerrainType.CLEAR.value
        source.construction = EngineeringWork.PORT.ordinal
        source.constructionTurns = 2
        source.constructionSide = 0
        source.constructionPlayer = 3
        source.constructionCountry = 7
        source.razedTerrain = TerrainType.FOREST.value
        source.blownRoad = PARTIAL_ROAD_MASK
        assertEquals("PORT", Engineering.workName(source), "the job travels as its name")

        val restored = Hex(2, 2)
        restoreEngineering(restored, reparse(GameStateSerializer.serializeHex(source)))

        assertEquals(EngineeringWork.PORT.ordinal, restored.construction)
        assertEquals(2, restored.constructionTurns)
        assertEquals(0, restored.constructionSide)
        assertEquals(3, restored.constructionPlayer, "the builder survives the save")
        assertEquals(7, restored.constructionCountry)
        assertEquals(TerrainType.FOREST.value, restored.razedTerrain)
        assertEquals(PARTIAL_ROAD_MASK, restored.blownRoad)

        // A save written before the builder existed carries no such key, and has to restore as
        // "builder unknown" rather than as player 0 -- that is the case `constructionSide` is
        // still kept for.
        val legacy = Hex(2, 2)
        val payload = reparse(GameStateSerializer.serializeHex(source))
        payload.constructionPlayer = undefined
        payload.constructionCountry = undefined
        restoreEngineering(legacy, payload)
        assertEquals(-1, legacy.constructionPlayer, "absent is not player zero")
        assertEquals(0, legacy.constructionSide, "and the side is what such a job falls back on")
    }
}
