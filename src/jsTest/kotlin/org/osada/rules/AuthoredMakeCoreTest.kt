package org.osada.rules

import org.osada.GameHolder
import org.osada.hero.HeroCampaign
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addUnit
import org.osada.model.buildCoreUnitList
import org.osada.model.enrollAuthoredCoreUnits
import org.osada.model.restoreCoreUnitList
import org.osada.model.undeployCoreUnits
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OG's **Make Core** (`.xscn` unit `@44` bit 2), and the difference between wearing the marker and
 * being ENROLLED.
 *
 * `core="1"` set [org.osada.model.GameUnit.isCore] from the day it was imported, which is enough
 * for display and for the rules that read the flag. Campaign PERSISTENCE is a different thing: it
 * is owned by `Player`'s private core roster, and `Player.setPlayerToHQ` walks that roster and
 * nothing else — so a marked-but-unenrolled formation disappeared at the scenario transition. 95
 * formations across 26 deployed scenarios are authored this way.
 *
 * The three enrollment paths the backlog names each get a test, and each asserts the same three
 * things: enrolled exactly once, carries a formation id, and survives `setPlayerToHQ`.
 */
class AuthoredMakeCoreTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
        HeroCampaign.reset()
    }

    @AfterTest
    fun teardown() {
        HeroCampaign.reset()
        clearTestWorld()
    }

    /** A map whose (2,2) hex is one of [friendly]'s deployment hexes. */
    private fun campaignWorld(): GameMap =
        world().apply {
            map!![2][2].isDeployment = friendly.id
        }

    private fun makeCore(unit: GameUnit): GameUnit = unit.apply { isCore = true }

    private fun assertEnrolledOnce(
        player: Player,
        unit: GameUnit,
    ) {
        assertEquals(
            1,
            player.getCoreUnitList().count { it === unit },
            "enrolled exactly once — every enrollment path is idempotent",
        )
        assertTrue(
            !assertNotNull(unit.formationId).isEmpty(),
            "an enrolled formation carries the id it must keep across the transition",
        )
    }

    /** `setPlayerToHQ` is the scenario transition: it keeps the roster and drops nothing else. */
    private fun survivesTransition(
        player: Player,
        unit: GameUnit,
    ): Boolean {
        val id = unit.formationId
        player.setPlayerToHQ()
        return player.getCoreUnitList().any { it === unit && it.formationId == id }
    }

    // ---- Path 1: pre-placed in scenario 1, away from any deployment hex --------------------------

    /**
     * The case `buildCoreUnitList` could never see. Its sweep enrols by deployment-hex occupancy,
     * and an author who ticks Make Core on a formation standing anywhere else on the map is saying
     * the same thing about it.
     */
    @Test
    fun aPrePlacedMakeCoreUnitAwayFromTheDeployHexIsEnrolled() {
        val map = campaignWorld()
        val onDeployHex = place(map, infantryEqid, 2, 2, 0)
        val elsewhere = makeCore(place(map, infantryEqid, 5, 5, 0))

        map.buildCoreUnitList(friendly)

        assertTrue(friendly.getCoreUnitList().any { it === onDeployHex }, "the original rule still holds")
        assertEnrolledOnce(friendly, elsewhere)
        assertTrue(survivesTransition(friendly, elsewhere))
    }

    /**
     * Placed content stays placed. `undeployCoreUnits` lifts a first-scenario core into the buy/
     * deploy tray, and it must lift only what the deployment-hex sweep put there — an authored
     * Make Core formation elsewhere on the map was positioned by the author.
     */
    @Test
    fun theTrayLiftLeavesAnAuthoredPlacementWhereTheAuthorPutIt() {
        val map = campaignWorld()
        val onDeployHex = place(map, infantryEqid, 2, 2, 0)
        val elsewhere = makeCore(place(map, infantryEqid, 5, 5, 0))
        map.buildCoreUnitList(friendly)

        map.undeployCoreUnits(friendly)

        assertFalse(map.units.contains(onDeployHex), "the deploy-hex occupant goes to the tray")
        assertTrue(map.units.contains(elsewhere), "the authored placement stays on the map")
        assertTrue(elsewhere.isDeployed || elsewhere.getPos() != null, "and stays where it was placed")
        assertTrue(friendly.getCoreUnitList().any { it === elsewhere }, "while still being core")
    }

    // ---- Path 2: a Make Core formation that arrives mid-battle ----------------------------------

    /**
     * A reinforcement wave or a scenario event's spawn reaches the map through `GameMap.addUnit`
     * long after the load sweep has run, so that funnel enrols too.
     */
    @Test
    fun aMakeCoreReinforcementArrivingLaterIsEnrolled() {
        val map = campaignWorld()
        map.buildCoreUnitList(friendly)
        val game = holderFor(map)
        game.campaignPlayer = friendly
        game.scenario!!.isLoaded = true
        GameHolder.instance = game

        val arriving = makeCore(GameUnit(infantryEqid).apply { owner = friendly.id })
        map.map!![4][4].setUnit(arriving)
        map.addUnit(arriving)

        assertEnrolledOnce(friendly, arriving)
        assertTrue(survivesTransition(friendly, arriving))
    }

    /** The AI's own authored Make Core formations are not the human's army. */
    @Test
    fun anEnemyMakeCoreFormationIsNeverEnrolledIntoThePlayersRoster() {
        val map = campaignWorld()
        val enemy = makeCore(place(map, infantryEqid, 6, 6, 1))

        map.buildCoreUnitList(friendly)
        map.enrollAuthoredCoreUnits(friendly)
        map.restoreCoreUnitList(friendly, emptyList())

        assertTrue(friendly.getCoreUnitList().none { it === enemy })
    }

    /** A formation lent for one battle must never join the permanent roster. */
    @Test
    fun aTemporarilyBorrowedFormationIsNotEnrolledEvenWhenMarkedCore() {
        val map = campaignWorld()
        val borrowed =
            makeCore(place(map, infantryEqid, 5, 5, 0)).apply { isTemporaryBorrowed = true }

        map.enrollAuthoredCoreUnits(friendly)

        assertTrue(friendly.getCoreUnitList().none { it === borrowed })
    }

    // ---- Path 3: scenario 2+, beside a roster carried over from the previous battle --------------

    /**
     * Scenario 2+ never runs `buildCoreUnitList` — its roster arrives by `Player.copy` from the
     * previous battle — so the load-time sweep is the only thing that can see a Make Core formation
     * authored into a later scenario. Without it the unit wears the marker, is spared by
     * `removeNonCampaignUnits` BECAUSE it wears it, and still vanishes at the next transition.
     */
    @Test
    fun aMakeCoreUnitAddedInScenarioTwoJoinsTheRestoredRoster() {
        val carried = GameUnit(infantryEqid).apply { owner = friendly.id }
        friendly.addCoreUnit(carried)
        val carriedId = assertNotNull(carried.formationId)

        val map = campaignWorld()
        val authored = makeCore(place(map, infantryEqid, 5, 5, 0))

        map.enrollAuthoredCoreUnits(friendly)

        assertEnrolledOnce(friendly, authored)
        assertTrue(friendly.getCoreUnitList().any { it === carried }, "the carried roster is untouched")
        assertEquals(carriedId, carried.formationId, "and keeps its own id")
        assertTrue(
            authored.formationId != carriedId,
            "a newly enrolled formation is minted past every id already present",
        )
        assertTrue(survivesTransition(friendly, authored))
    }

    /** Every path is idempotent, so the loader may call the sweep on top of any other path. */
    @Test
    fun repeatedSweepsNeverEnrolTheSameFormationTwice() {
        val map = campaignWorld()
        val unit = makeCore(place(map, infantryEqid, 2, 2, 0))

        map.buildCoreUnitList(friendly)
        map.enrollAuthoredCoreUnits(friendly)
        map.enrollAuthoredCoreUnits(friendly)

        assertEnrolledOnce(friendly, unit)
    }
}
