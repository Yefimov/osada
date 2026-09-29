package org.osada.rules

import org.osada.hero.HeroCampaign
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetProfileStore
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import org.osada.scenario.nextScenarioRecord
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** OG's two `.xcam` prestige fields (`rules/CampaignPrestige.kt`, schema 19). */
class CampaignPrestigeTest {
    @BeforeTest
    fun setup() {
        ActiveRuleset.resetForTest()
        // 25 x CURRENCY_MULTIPLIER 12 = 300 prestige per unit.
        Equipment.putEquipment(EQID, EquipmentData().apply { cost = 25 })
    }

    @AfterTest
    fun teardown() {
        ActiveRuleset.resetForTest()
        HeroCampaign.reset()
    }

    @Test
    fun offInOsadaDefaultAndDeferredToTheCampaignInAuthorsVision() {
        for (key in listOf(RuleKey.CAMPAIGN_START_PRESTIGE, RuleKey.CAMPAIGN_PRESTIGE_CAP)) {
            assertEquals(0, resolved(RulesetSource.OSADA_DEFAULT).effective(key))
            assertEquals(1, resolved(RulesetSource.AUTHORS_VISION).effective(key))
        }
    }

    @Test
    fun bothAuthoredBudgetsArePaidOnTopOfWhatEachSideAlreadyHas() {
        ActiveRuleset.set(resolved(RulesetSource.AUTHORS_VISION))
        val human = player(side = 0, prestige = 40)
        val enemy = player(side = 1, prestige = 25)
        val record = js("({scenario: 'ciechan.xml', aiprestige: 150, playerprestige: 100})")
        CampaignStartPrestige.apply(record, human, listOf(human, enemy))
        assertEquals(175, enemy.prestige)
        assertEquals(140, human.prestige)
    }

    @Test
    fun noBudgetWhenTheRuleIsOffOrTheRecordAuthorsNone() {
        val human = player(side = 0, prestige = 40)
        val enemy = player(side = 1, prestige = 25)
        CampaignStartPrestige.apply(js("({aiprestige: 150, playerprestige: 100})"), human, listOf(human, enemy))
        ActiveRuleset.set(resolved(RulesetSource.AUTHORS_VISION))
        CampaignStartPrestige.apply(js("({scenario: 'x.xml'})"), human, listOf(human, enemy))
        assertEquals(25, enemy.prestige)
        assertEquals(40, human.prestige)
    }

    /**
     * The author's worked example in miniature: only bought units count, at full price whatever
     * their strength, and the award is what is left under the cap.
     */
    @Test
    fun theAwardIsCutToTheRoomLeftUnderTheCap() {
        ActiveRuleset.set(resolved(RulesetSource.AUTHORS_VISION))
        val human = player(side = 0, prestige = 1000)
        human.addCoreUnit(GameUnit(EQID).apply { isPurchased = true })
        human.addCoreUnit(
            GameUnit(EQID).apply {
                isPurchased = true
                strength = 3
            },
        )
        human.addCoreUnit(GameUnit(EQID)) // an original unit: free
        assertEquals(600, CampaignPrestigeCap.armyValue(human))
        // 2000 - 600 - 1000 = 400 of the 1000 award.
        assertEquals(400, CampaignPrestigeCap.cappedAward(1000, 2000, human))
        assertEquals(300, CampaignPrestigeCap.cappedAward(300, 2000, human))
        assertEquals(0, CampaignPrestigeCap.cappedAward(1000, 1200, human))
        assertEquals(1000, CampaignPrestigeCap.cappedAward(1000, null, human), "no authored cap")
        assertEquals(1000, CampaignPrestigeCap.cappedAward(1000, 0, human), "0 is no cap")
    }

    @Test
    fun theCapDoesNothingWhileTheRuleIsOff() {
        val human = player(side = 0, prestige = 5000)
        assertEquals(1000, CampaignPrestigeCap.cappedAward(1000, 2000, human))
    }

    /**
     * `forward`, Suomussalmi -> Minsk, with the numbers of the original `Forward.xcam`: Suomussalmi
     * caps at 2800 and pays 3600 / 1800 / 1000; every victory routes to Minsk, which caps at 10000.
     * The award is Suomussalmi's, the cap is Minsk's (OG Basic Tutorial p.3, "the cap for the next
     * scenario"). Measured against Suomussalmi's own 2800, a 2000 standing would have left 800.
     */
    @Test
    fun theAwardOfTheFinishedBattleIsCappedByTheNextBattle() {
        ActiveRuleset.set(resolved(RulesetSource.AUTHORS_VISION))
        val cap = CampaignPrestigeCap.capOf(nextScenarioRecord(forward(), 0, "briliant"))
        assertEquals(10000, cap)

        val standing2000 = playerWorth(2000)
        assertEquals(3600, CampaignPrestigeCap.cappedAward(3600, cap, standing2000))
        assertEquals(1800, CampaignPrestigeCap.cappedAward(1800, cap, standing2000))
        assertEquals(1000, CampaignPrestigeCap.cappedAward(1000, cap, standing2000))

        assertEquals(1000, CampaignPrestigeCap.cappedAward(3600, cap, playerWorth(9000)))
        val atCap = playerWorth(10000)
        assertEquals(0, CampaignPrestigeCap.cappedAward(3600, cap, atCap))
        assertEquals(0, CampaignPrestigeCap.cappedAward(3600, cap, playerWorth(12000)), "never negative")
    }

    /** Branches to battles with different caps: the grade's own branch decides, and a committed
     *  route choice overrides it exactly as `Campaign.loadNextScenario` does. */
    @Test
    fun theCapIsTheOneOfTheBranchActuallyTaken() {
        val scenarios = branching()
        assertEquals(3000, CampaignPrestigeCap.capOf(nextScenarioRecord(scenarios, 0, "victory")))
        assertEquals(8000, CampaignPrestigeCap.capOf(nextScenarioRecord(scenarios, 0, "briliant")))
        assertEquals(6000, CampaignPrestigeCap.capOf(nextScenarioRecord(scenarios, 0, "victory", routeOverride = 3)))
    }

    /** No cap from a next battle that authors none ("Dont use cap" is imported as an absent key),
     *  none at the end of the campaign, and none while the rule is off. */
    @Test
    fun noCapWithoutAnAuthoredNextCapAnEndOrTheRule() {
        val scenarios = branching()
        assertNull(CampaignPrestigeCap.capOf(nextScenarioRecord(scenarios, 0, "tactical")), "next record authors none")
        assertNull(nextScenarioRecord(scenarios, 0, "lose"), "goto 255 ends the campaign")
        assertNull(CampaignPrestigeCap.capOf(nextScenarioRecord(scenarios, 0, "lose")))
        assertEquals(3600, CampaignPrestigeCap.cappedAward(3600, null, playerWorth(20000)))
        // Rule off (OSADA Default): even an authored next cap does nothing.
        assertEquals(3600, CampaignPrestigeCap.cappedAward(3600, 10000, playerWorth(20000)))
    }

    /** A player whose bought army (300 per unit) plus prestige in hand is [total]. */
    private fun playerWorth(total: Int): Player {
        val units = total / 2 / UNIT_VALUE
        val human = player(side = 0, prestige = total - units * UNIT_VALUE)
        repeat(units) { human.addCoreUnit(GameUnit(EQID).apply { isPurchased = true }) }
        assertEquals(total, CampaignPrestigeCap.armyValue(human) + human.prestige)
        return human
    }

    private fun forward(): Array<dynamic> =
        arrayOf(
            js(
                """({scenario: 'forward1.xml', prestigecap: 2800, outcome: {
                    briliant: {goto: 1, prestige: 3600}, victory: {goto: 1, prestige: 1800},
                    tactical: {goto: 1, prestige: 1000}, lose: {goto: 255, prestige: 0}}})""",
            ),
            js("({scenario: 'forward2.xml', prestigecap: 10000, outcome: {}})"),
        )

    private fun branching(): Array<dynamic> =
        arrayOf(
            js(
                """({scenario: 'a.xml', prestigecap: 1000, outcome: {
                    briliant: {goto: 2}, victory: {goto: 1}, tactical: {goto: 4}, lose: {goto: 255}}})""",
            ),
            js("({scenario: 'b.xml', prestigecap: 3000})"),
            js("({scenario: 'c.xml', prestigecap: 8000})"),
            js("({scenario: 'd.xml', prestigecap: 6000})"),
            js("({scenario: 'e.xml'})"),
        )

    private fun resolved(source: RulesetSource) =
        RulesetResolver.resolve(RulesetProfileStore.builtIns().first { it.source == source })

    private fun player(
        side: Int,
        prestige: Int,
    ) = Player().apply {
        id = side
        this.side = side
        this.prestige = prestige
    }

    private companion object {
        const val EQID = 990_101
        const val UNIT_VALUE = 300
    }
}
