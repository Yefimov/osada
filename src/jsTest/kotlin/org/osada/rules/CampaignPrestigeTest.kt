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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    }
}
