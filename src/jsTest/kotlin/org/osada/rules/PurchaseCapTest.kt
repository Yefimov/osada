package org.osada.rules

import org.osada.GameHolder
import org.osada.GameStateDeserializer
import org.osada.GameStateSerializer
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.buyUnit
import org.osada.model.disbandUnit
import org.osada.model.enrollAuthoredCoreUnits
import org.osada.model.updateUnitList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OG's **purchase cap** — *"You can repurchase only the lost units"* (`EFILE_KAISER/rhu190613`,
 * whose `purchasecap` byte is 0).
 *
 * The property the whole rule turns on is [aCapOfZeroStillAllowsReplacingALoss]: zero means "no
 * growth", not "no purchases", and a rule that folded the two together would silently disarm twelve
 * scenarios' economy. The second load-bearing test is
 * [aDeliberateRemovalDoesNotMintAReplacementCredit] — a live "units now vs units then" expression
 * would pass every other test here and fail that one.
 */
class PurchaseCapTest : OgRulesTestHarness() {
    private val rifleEqid = 990

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            rifleEqid,
            EquipmentData().apply {
                name = "Rifle Regiment"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                cost = 10
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun buyer(cap: Int?): Player =
        friendly.apply {
            prestige = 100_000
            purchaseCap = cap
            purchaseGrowthSpent = 0
            replacementCredits = 0
        }

    /** A live map with [player] on it, so the death sweep can run. */
    private fun battlefield(): GameMap {
        val map = world(prestige = 100_000)
        GameHolder.instance = holderFor(map)
        return map
    }

    private fun kill(
        map: GameMap,
        unit: GameUnit,
    ) {
        unit.destroyed = true
        map.updateUnitList()
    }

    // ---- The counters ---------------------------------------------------------------------------

    /** 490 of the 502 deployed scenarios author nothing, and must behave exactly as before. */
    @Test
    fun anUncappedPlayerIsNeverRefusedAndNeverCounted() {
        val player = buyer(null)
        repeat(5) { assertTrue(player.buyUnit(rifleEqid, -1)) }
        assertTrue(PurchaseCap.allows(player))
        assertNull(PurchaseCap.remainingGrowth(player))
        assertEquals(0, player.purchaseGrowthSpent, "an uncapped player is not counted at all")
    }

    /** The cap counts net-new formations, so it runs out exactly at its own number. */
    @Test
    fun aCapSpendsOneGrowthSlotPerPurchaseAndThenRefuses() {
        val player = buyer(2)
        assertTrue(player.buyUnit(rifleEqid, -1))
        assertTrue(player.buyUnit(rifleEqid, -1))
        assertEquals(0, PurchaseCap.remainingGrowth(player))

        val prestigeBefore = player.prestige
        assertFalse(player.buyUnit(rifleEqid, -1), "the third purchase is over the cap")
        assertEquals(prestigeBefore, player.prestige, "and a refused purchase costs nothing")
    }

    /**
     * **`purchasecap="0"` is the author forbidding GROWTH, not purchases.** The scenario that
     * carries it says so in its own briefing prose.
     */
    @Test
    fun aCapOfZeroStillAllowsReplacingALoss() {
        val map = battlefield()
        val player = buyer(0)
        assertFalse(player.buyUnit(rifleEqid, -1), "no growth at all")

        val lost = place(map, rifleEqid, 3, 3, 0)
        kill(map, lost)

        assertEquals(1, PurchaseCap.replacementCreditsFor(player))
        assertTrue(player.buyUnit(rifleEqid, -1), "the loss may be repurchased")
        assertEquals(0, player.purchaseGrowthSpent, "and it did not consume a growth slot")
        assertFalse(player.buyUnit(rifleEqid, -1), "the credit is spent, and growth is still zero")
    }

    /** Credits are spent before slots, or replacing a loss would quietly cost the author's number. */
    @Test
    fun aReplacementCreditIsSpentBeforeAGrowthSlot() {
        val map = battlefield()
        val player = buyer(1)
        kill(map, place(map, rifleEqid, 3, 3, 0))

        assertTrue(player.buyUnit(rifleEqid, -1))
        assertEquals(0, player.purchaseGrowthSpent, "paid for out of the credit")
        assertTrue(player.buyUnit(rifleEqid, -1), "the growth slot is still there")
        assertEquals(1, player.purchaseGrowthSpent)
        assertFalse(player.buyUnit(rifleEqid, -1))
    }

    // ---- What does and does not count as a loss --------------------------------------------------

    /**
     * The test a live `currentUnits < initialUnits + cap` expression cannot pass. Disbanding marks
     * the unit `nodossier`, which is the same signal the campaign dossier uses to decide a removal
     * was not a casualty — so the two can never disagree.
     */
    @Test
    fun aDeliberateRemovalDoesNotMintAReplacementCredit() {
        val map = battlefield()
        val player = buyer(0)
        val unit = place(map, rifleEqid, 3, 3, 0)

        assertTrue(map.disbandUnit(unit.id))

        assertEquals(0, PurchaseCap.replacementCreditsFor(player), "disbanding is not a loss")
        assertFalse(player.buyUnit(rifleEqid, -1))
    }

    /** A formation lent for one battle was never the player's to replace. */
    @Test
    fun losingABorrowedFormationMintsNothing() {
        val map = battlefield()
        val player = buyer(0)
        val borrowed = place(map, rifleEqid, 3, 3, 0).apply { isTemporaryBorrowed = true }

        kill(map, borrowed)

        assertEquals(0, PurchaseCap.replacementCreditsFor(player))
    }

    // ---- `opt_cores_off_cap` ----------------------------------------------------------------------

    /**
     * OG's *"core units added by design do not count against the CAP"* only says anything because by
     * default they DO. Inert on shipped content — no deployed scenario authors both — and built
     * because the cap rule cannot otherwise express what an author asked for.
     */
    @Test
    fun anAuthorAddedCoreFormationSpendsASlotUnlessTheOptionExemptsIt() {
        val map = battlefield()
        val player = buyer(1)
        place(map, rifleEqid, 3, 3, 0).isCore = true

        map.enrollAuthoredCoreUnits(player)

        assertEquals(1, player.purchaseGrowthSpent, "a design-added core formation is growth")
        assertFalse(player.buyUnit(rifleEqid, -1), "and it used the scenario's only slot")

        val exempt = buyer(1)
        exempt.id = friendly.id
        GameHolder.instance!!.scenario!!.coresExemptFromPurchaseCap = true
        place(map, rifleEqid, 4, 4, 0).isCore = true
        map.enrollAuthoredCoreUnits(exempt)

        assertEquals(0, exempt.purchaseGrowthSpent, "the option exempts it")
    }

    /** Enrolment is idempotent, so a re-run of the load sweep must not charge the cap twice. */
    @Test
    fun aRepeatedEnrolmentSweepChargesTheCapOnlyOnce() {
        val map = battlefield()
        val player = buyer(3)
        place(map, rifleEqid, 3, 3, 0).isCore = true

        map.enrollAuthoredCoreUnits(player)
        map.enrollAuthoredCoreUnits(player)

        assertEquals(1, player.purchaseGrowthSpent)
    }

    // ---- Persistence -------------------------------------------------------------------------------

    /**
     * A restore never re-reads the scenario XML, so both the authored cap and the counters have to
     * come out of the save — otherwise reloading is a way to restore spent slots, and the whole rule
     * evaporates on the first reload.
     */
    @Test
    fun theCapAndItsCountersSurviveASaveRoundTrip() {
        val player = buyer(3)
        player.purchaseList = setOf(rifleEqid, rifleEqid + 1)
        assertTrue(player.buyUnit(rifleEqid, -1))
        player.replacementCredits = 2

        val restored =
            GameStateDeserializer.deserializePlayer(reparse(GameStateSerializer.serializePlayer(player)))

        assertEquals(3, restored.purchaseCap)
        assertEquals(1, restored.purchaseGrowthSpent)
        assertEquals(2, restored.replacementCredits)
        assertEquals(setOf(rifleEqid, rifleEqid + 1), restored.purchaseList)
    }

    /** An uncapped player's save keeps exactly the shape it had before this rule existed. */
    @Test
    fun anUncappedPlayerWritesNoneOfTheNewKeys() {
        val saved = reparse(GameStateSerializer.serializePlayer(buyer(null)))
        assertTrue(saved.purchaseCap == undefined, "no cap key")
        assertTrue(saved.purchaseGrowthSpent == undefined, "no growth counter")
        assertTrue(saved.replacementCredits == undefined, "no credit counter")

        val restored = GameStateDeserializer.deserializePlayer(saved)
        assertNull(restored.purchaseCap)
        assertNull(restored.purchaseList)
    }

    /** The cap is per SCENARIO: `setPlayerToHQ` is the transition, and it must clear both counters. */
    @Test
    fun theCountersResetAtTheScenarioTransition() {
        val player = buyer(2)
        assertTrue(player.buyUnit(rifleEqid, -1))
        player.replacementCredits = 3

        player.setPlayerToHQ()

        assertNull(player.purchaseCap, "the next scenario supplies its own")
        assertEquals(0, player.purchaseGrowthSpent)
        assertEquals(0, player.replacementCredits)
    }

    // ---- The AI reads the same rule ------------------------------------------------------------------

    /** The AI must stop CHOOSING units the mutation layer is going to refuse. */
    @Test
    fun theAiShoppingListStopsAtTheCap() {
        val player = buyer(2)
        assertTrue(PurchaseCap.allowsAfter(player, 0))
        assertTrue(PurchaseCap.allowsAfter(player, 1), "one already chosen still leaves a slot")
        assertFalse(PurchaseCap.allowsAfter(player, 2), "two chosen exhausts the cap")

        player.replacementCredits = 1
        assertTrue(PurchaseCap.allowsAfter(player, 2), "the credit pays for a third")
        assertFalse(PurchaseCap.allowsAfter(player, 3))
    }
}
