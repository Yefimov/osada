package org.osada.rules

import org.osada.GameStateDeserializer
import org.osada.GameStateSerializer
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.FrontFactionSlot
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.buyUnit
import org.osada.model.upgradeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OG's **Fronts and Factions**, as masks rather than as the resolved `.buy4` list.
 *
 * The whole mechanic turns on one sentence — *"Any unit having front=zero is compatible with any
 * other front, and same for faction"* — so [zeroIsAWildcardOnBothSidesOfTheMatcher] is the test the
 * rest depend on. The second load-bearing one is [aPlayerWithNoAuthoredSlotsIsUnrestricted]: 294 of
 * the 502 deployed scenarios author no mask at all and must behave exactly as before.
 *
 * The rule is separately measured against OG's own resolved lists by
 * `tools/og-import/verify_fronts_factions.py`, which checks the shipped data rather than the model.
 */
class FrontsAndFactionsTest : OgRulesTestHarness() {
    private companion object {
        const val SOVIET = 20
        const val ALLY = 27

        /** `@ARMY`, slot 15 of `fronts.txt`'s faction block — the value the controlled saves show. */
        const val ARMY = 32768

        /** `#U.S.M.C./Navy CLIM Default`, front slot 5. */
        const val NAVY_CLIMATE = 32
    }

    private val armyEqid = 1000
    private val navyEqid = 1001
    private val wildcardEqid = 1002
    private val allyEqid = 1003
    private val airliftEqid = 1004

    @BeforeTest
    fun setup() {
        installTestWorld()
        put(armyEqid, "Guards Rifle Division", SOVIET, fronts = 0, factions = ARMY)
        put(navyEqid, "Naval Infantry", SOVIET, fronts = NAVY_CLIMATE, factions = 4)
        put(wildcardEqid, "Militia", SOVIET, fronts = 0, factions = 0)
        put(allyEqid, "Allied Brigade", ALLY, fronts = 0, factions = 4)
        put(
            airliftEqid,
            "Transport Aircraft",
            SOVIET,
            fronts = 0,
            factions = 0,
            uclass = UnitClass.AIR_TRANSPORT.value,
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun put(
        eqid: Int,
        label: String,
        country: Int,
        fronts: Int,
        factions: Int,
        uclass: Int = UnitClass.INFANTRY.value,
    ) {
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                name = label
                this.uclass = uclass
                this.country = country
                this.fronts = fronts
                this.factions = factions
                movmethod = MovMethod.LEG.value
                cost = 10
            },
        )
    }

    private fun buyer(vararg slots: FrontFactionSlot): Player =
        friendly.apply {
            prestige = 100_000
            purchaseCap = null
            purchaseList = null
            frontFactionSlots = slots.toList()
            transportPoolsAuthored = false
        }

    // ---- The matcher ------------------------------------------------------------------------------

    /** The engine author's own sentence, in both directions and in the intersection case. */
    @Test
    fun zeroIsAWildcardOnBothSidesOfTheMatcher() {
        assertTrue(FrontsAndFactions.matches(0, ARMY), "a unit with no front fits any front")
        assertTrue(FrontsAndFactions.matches(ARMY, 0), "an unrestricted scenario admits any unit")
        assertTrue(FrontsAndFactions.matches(0, 0))
        assertTrue(FrontsAndFactions.matches(0b0110, 0b0100), "one shared bit is enough")
        assertFalse(FrontsAndFactions.matches(0b0010, 0b0100), "disjoint non-zero masks do not match")
    }

    /** A mask with bit 31 set arrives as a negative `Int`, and must still intersect correctly. */
    @Test
    fun theTopBitSurvivesAsASignedInt() {
        val none = -2147483648 // `@NONE - No units available!`, faction slot 31
        assertTrue(FrontsAndFactions.matches(none, none))
        assertFalse(FrontsAndFactions.matches(ARMY, none))
        assertTrue(FrontsAndFactions.matches(0, none), "and zero is still the wildcard against it")
    }

    // ---- Purchase composition ---------------------------------------------------------------------

    /** 294 of the 502 deployed scenarios author no mask; nothing may change for them. */
    @Test
    fun aPlayerWithNoAuthoredSlotsIsUnrestricted() {
        val player = buyer()
        assertTrue(FrontsAndFactions.admitsForPurchase(player, navyEqid))
        assertTrue(FrontsAndFactions.admitsForPurchase(null, navyEqid))
        assertTrue(player.buyUnit(navyEqid, -1))
    }

    /** The authored mask admits what it names and refuses what it does not. */
    @Test
    fun anAuthoredFactionAdmitsOnlyItsOwnBranch() {
        val player = buyer(FrontFactionSlot(SOVIET, fronts = 0, factions = ARMY))
        assertTrue(FrontsAndFactions.admitsForPurchase(player, armyEqid))
        assertFalse(FrontsAndFactions.admitsForPurchase(player, navyEqid), "a naval faction is not @ARMY")
        assertTrue(
            FrontsAndFactions.admitsForPurchase(player, wildcardEqid),
            "a record with no faction of its own is compatible with every faction",
        )
    }

    /**
     * Masks are declared PER COUNTRY, so a slot says nothing about another nation's equipment.
     * Country eligibility is a separate, older rule and must not be answered twice.
     */
    @Test
    fun aSlotSaysNothingAboutACountryItDoesNotName() {
        val player = buyer(FrontFactionSlot(SOVIET, fronts = 0, factions = ARMY))
        assertTrue(
            FrontsAndFactions.admitsForPurchase(player, allyEqid),
            "the ally's own equipment is not judged by the Soviet slot's bits",
        )
    }

    /** OG permits the same country in more than one support slot; any compatible slot admits. */
    @Test
    fun aDuplicatedCountryIsResolvedByAnyCompatibleSlot() {
        val player =
            buyer(
                FrontFactionSlot(SOVIET, fronts = 0, factions = ARMY),
                FrontFactionSlot(SOVIET, fronts = NAVY_CLIMATE, factions = 4),
            )
        assertTrue(FrontsAndFactions.admitsForPurchase(player, armyEqid), "admitted by the first slot")
        assertTrue(FrontsAndFactions.admitsForPurchase(player, navyEqid), "admitted by the second")
    }

    /** Both masks must match; one alone is not enough. */
    @Test
    fun frontAndFactionAreBothRequired() {
        val player = buyer(FrontFactionSlot(SOVIET, fronts = 1, factions = 4))
        assertFalse(
            FrontsAndFactions.admitsForPurchase(player, navyEqid),
            "its faction fits and its front does not",
        )
    }

    /** The purchase itself is refused, not merely hidden — `buyUnit` is the one mutation gate. */
    @Test
    fun buyingSomethingOutsideTheMasksIsRefused() {
        val player = buyer(FrontFactionSlot(SOVIET, fronts = 0, factions = ARMY))
        assertTrue(player.buyUnit(armyEqid, -1))
        val before = player.prestige
        assertFalse(player.buyUnit(navyEqid, -1))
        assertEquals(before, player.prestige, "and a refused purchase costs nothing")
    }

    /** *"buy new units **or upgrade existing ones**"* — both halves of the Suite's own sentence. */
    @Test
    fun upgradingIntoSomethingOutsideTheMasksIsRefusedToo() {
        val player = buyer(FrontFactionSlot(SOVIET, fronts = 0, factions = ARMY))
        assertTrue(player.buyUnit(armyEqid, -1))
        val unit = player.getCoreUnitList().last()
        assertFalse(player.upgradeUnit(unit, navyEqid, -1))
        assertEquals(armyEqid, unit.eqid, "the formation kept its equipment")
    }

    // ---- Pool classes -----------------------------------------------------------------------------

    /**
     * OG hands air and naval transports out through a per-player POOL, so they are not in the shop —
     * but only where the scenario authored the pools. Panzer Marshal's own campaigns author none and
     * have always bought them.
     */
    @Test
    fun poolTransportsLeaveTheShopOnlyWhereTheScenarioAuthoredAPool() {
        val pmStyle = buyer().apply { transportPoolsAuthored = false }
        assertTrue(FrontsAndFactions.poolClassPurchasable(pmStyle, airliftEqid))
        assertTrue(pmStyle.buyUnit(airliftEqid, -1), "unchanged for content with no OG pools")

        val ogStyle = buyer().apply { transportPoolsAuthored = true }
        assertFalse(FrontsAndFactions.poolClassPurchasable(ogStyle, airliftEqid))
        assertFalse(ogStyle.buyUnit(airliftEqid, -1))
        assertTrue(ogStyle.buyUnit(armyEqid, -1), "and everything else is still for sale")
    }

    // ---- `ff_mustmatch` ----------------------------------------------------------------------------

    /** Off in every shipped efile, so the container rule must refuse nothing until one sets it. */
    @Test
    fun ffMustMatchIsInertUntilAnEfileSetsTheKey() {
        val cargo = GameUnit(navyEqid)
        val hull = GameUnit(armyEqid)
        assertTrue(FrontsAndFactions.cargoMatchesCarrier(cargo, hull))
    }

    /** With the key on it compares the EQUIPMENT masks of both sides — never the scenario's. */
    @Test
    fun ffMustMatchComparesCargoAgainstCarrier() {
        EfileConfig.setForTest(mapOf("ff_mustmatch" to 1))
        assertFalse(
            FrontsAndFactions.cargoMatchesCarrier(GameUnit(navyEqid), GameUnit(armyEqid)),
            "disjoint factions: 4 against @ARMY",
        )
        assertTrue(
            FrontsAndFactions.cargoMatchesCarrier(GameUnit(wildcardEqid), GameUnit(armyEqid)),
            "a wildcard cargo rides anything",
        )
    }

    // ---- Persistence -------------------------------------------------------------------------------

    /** A restore never re-reads the scenario XML, so the slots have to come out of the save. */
    @Test
    fun theSlotsSurviveASaveRoundTrip() {
        val player =
            buyer(
                FrontFactionSlot(SOVIET, fronts = 1, factions = -2147483648),
                FrontFactionSlot(ALLY, fronts = 0, factions = 4),
            ).apply { transportPoolsAuthored = true }

        val restored =
            GameStateDeserializer.deserializePlayer(reparse(GameStateSerializer.serializePlayer(player)))

        assertEquals(player.frontFactionSlots, restored.frontFactionSlots)
        assertTrue(restored.transportPoolsAuthored)
    }

    /** An unrestricted player's save keeps exactly the shape it had before the masks existed. */
    @Test
    fun anUnrestrictedPlayerWritesNoSlotKey() {
        val saved = reparse(GameStateSerializer.serializePlayer(buyer()))
        assertTrue(saved.frontFactionSlots == undefined)
        assertTrue(saved.transportPoolsAuthored == undefined)
    }

    /** The deployed attribute is text; malformed and country-less entries are dropped, not guessed. */
    @Test
    fun theDeployedAttributeParsesAndRoundTripsAsText() {
        val slots = FrontFactionSlot.parse("20:1:32768,27:0:5")
        assertEquals(
            listOf(FrontFactionSlot(20, 1, 32768), FrontFactionSlot(27, 0, 5)),
            slots,
        )
        assertEquals("20:1:32768,27:0:5", FrontFactionSlot.format(slots))
        assertEquals(emptyList(), FrontFactionSlot.parse(null))
        assertEquals(emptyList(), FrontFactionSlot.parse("0:1:2"), "a slot with no country is editor state")
        assertEquals(emptyList(), FrontFactionSlot.parse("20:1"), "and a malformed entry is dropped")
    }
}
