package org.osada.rules

import org.osada.campaign.CampaignEffect
import org.osada.campaign.CampaignEffectParser
import org.osada.campaign.CampaignEffectSerializer
import org.osada.campaign.CampaignNarrative
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Open General's free refit between campaign battles (`rules/CampaignAutoRefit`, schema 18).
 *
 * The case that motivated it: `forward1` (Finland) -> `forward2` (Pocket of Minsk). OG refits the
 * army for free at that transition, `forward2` gives the Soviet side no prestige per turn, and
 * OSADA's paid tray refit left a player who kept every unit unable to restore them.
 */
class CampaignAutoRefitTest {
    @BeforeTest
    fun setup() {
        ActiveRuleset.resetForTest()
        CampaignNarrative.reset()
        Equipment.putEquipment(
            EQID,
            EquipmentData().apply {
                name = "Test Rifles"
                cost = 10
                ammo = 6
                fuel = 4
            },
        )
    }

    @AfterTest
    fun teardown() {
        ActiveRuleset.resetForTest()
        CampaignNarrative.reset()
        HeroCampaign.reset()
    }

    @Test
    fun offInOsadaDefaultAndDeferredToTheCampaignInAuthorsVision() {
        assertEquals(0, resolved(RulesetSource.OSADA_DEFAULT).effective(RuleKey.CAMPAIGN_AUTO_REFIT))
        assertEquals(1, resolved(RulesetSource.AUTHORS_VISION).effective(RuleKey.CAMPAIGN_AUTO_REFIT))
        assertFalse(CampaignAutoRefit.enabled(), "with nothing resolved the call-site default is the paid refit")
    }

    @Test
    fun theCampaignRecordCanSwitchItOffAndSaysNothingByDefault() {
        assertTrue(CampaignAutoRefit.authoredFor(js("({scenario: 'forward2.xml'})")))
        assertFalse(CampaignAutoRefit.authoredFor(js("({scenario: 'forward26.xml', autorefit: false})")))
        assertTrue(CampaignAutoRefit.authoredFor(null), "no record is OG's default, not a refusal")
    }

    /** `n_willhelmsh`: OG refits it, but the story lets the player trade for a full resupply. */
    @Test
    fun aStoryResupplyChoiceKeepsTheDecisionWithThePlayer() {
        val record =
            js(
                "({scenario: 'n_willhelmsh.xml', briefing: {dialogue: [" +
                    "{id: 'intro'}," +
                    "{choices: [{effects: [{type: 'prestige', amount: 100}]}," +
                    "{effects: [{type: 'resupply', strength: 10, refuel: true, rearm: true}]}]}" +
                    "]}})",
            )
        val flagsOnly = js("({briefing: {dialogue: [{choices: [{effects: [{type: 'setFlag', flag: 'x'}]}]}]}})")

        assertFalse(CampaignAutoRefit.authoredFor(record))
        assertTrue(CampaignAutoRefit.authoredFor(flagsOnly), "only a resupply choice takes the refit away")
    }

    @Test
    fun theArmyComesBackWholeAndNothingIsCharged() {
        val owner = player(prestige = 1000)
        val battered = unit(owner, strength = 3, ammo = 0, fuel = 1)
        val overstrength = unit(owner, strength = 12, ammo = 6, fuel = 4)

        val changed = CampaignAutoRefit.apply(owner)

        assertEquals(1, changed)
        assertEquals(10, battered.strength)
        assertEquals(6, battered.ammo)
        assertEquals(4, battered.fuel)
        assertEquals(12, overstrength.strength, "an overstrength formation keeps its extra points")
        assertEquals(1000, owner.prestige, "OG's auto-refit is free")
    }

    @Test
    fun theDestroyedStayDestroyed() {
        val owner = player(prestige = 0)
        val lost = unit(owner, strength = 0).apply { destroyed = true }

        CampaignAutoRefit.apply(owner)

        assertEquals(0, lost.strength)
    }

    /** The queued effect must survive a save made between the transition and the next battle. */
    @Test
    fun theQueuedEffectRoundTripsThroughTheSaveFormat() {
        val effect = CampaignEffect.AutoRefit(CampaignAutoRefit.effectId(0, "forward1.xml", "forward2.xml"))

        val reread = CampaignEffectParser.parseSingle(CampaignEffectSerializer.serialize(effect))

        assertIs<CampaignEffect.AutoRefit>(reread)
        assertEquals("og-autorefit:0:forward1.xml>forward2.xml", reread.id)
    }

    /** Reloading inside the next battle must not refit the army a second time. */
    @Test
    fun itAppliesOnceEvenIfTheLoadHandlerRunsAgain() {
        val owner = player(prestige = 0)
        val formation = unit(owner, strength = 4)
        CampaignNarrative.queueForNextScenario(
            "forward2.xml",
            listOf(CampaignEffect.AutoRefit(CampaignAutoRefit.effectId(0, "forward1.xml", "forward2.xml"))),
        )

        CampaignNarrative.consumePendingFor("forward2.xml", owner)
        formation.strength = 5
        CampaignNarrative.consumePendingFor("forward2.xml", owner)

        assertEquals(5, formation.strength, "the second pass found nothing queued")
    }

    /**
     * `rcampdfr`: a defeat in `rcampper` loops back to `rcampper`. The outcome ledger records each
     * scenario file once per run, so the refit must not depend on it -- every real transition,
     * including the second pass over the same pair, gets its own refit.
     */
    @Test
    fun aReplayedMissionStillRefitsTheArmyOnItsNextTransition() {
        val owner = player(prestige = 0)
        val formation = unit(owner, strength = 4)
        val ledger = CampaignNarrative.state.effects

        repeat(2) { pass ->
            val effect = CampaignAutoRefit.nextEffect(ledger, "rcampper.xml", "rcampper.xml")
            assertIs<CampaignEffect.AutoRefit>(effect, "pass $pass must queue a refit")
            CampaignNarrative.queueForNextScenario("rcampper.xml", listOf(effect))
            CampaignNarrative.consumePendingFor("rcampper.xml", owner)
            assertEquals(10, formation.strength, "pass $pass refitted")
            formation.strength = 4
        }
    }

    /** The move-capture and end-turn paths can both complete one battle: one refit, not two. */
    @Test
    fun aDuplicateCompletionOfTheSameBattleQueuesNothingMore() {
        val ledger = CampaignNarrative.state.effects
        val first = CampaignAutoRefit.nextEffect(ledger, "forward1.xml", "forward2.xml")
        CampaignNarrative.queueForNextScenario("forward2.xml", listOfNotNull(first))

        assertEquals(null, CampaignAutoRefit.nextEffect(ledger, "forward1.xml", "forward2.xml"))
    }

    private fun resolved(source: RulesetSource) =
        RulesetResolver.resolve(RulesetProfileStore.builtIns().first { it.source == source })

    private fun player(prestige: Int) =
        Player().apply {
            id = 0
            this.prestige = prestige
        }

    private fun unit(
        owner: Player,
        strength: Int,
        ammo: Int = 6,
        fuel: Int = 4,
    ) = GameUnit(EQID).apply {
        this.owner = owner.id
        player = owner
        this.strength = strength
        this.ammo = ammo
        this.fuel = fuel
        isDeployed = false
        owner.addCoreUnit(this)
    }

    private companion object {
        const val EQID = 9_701
    }
}
