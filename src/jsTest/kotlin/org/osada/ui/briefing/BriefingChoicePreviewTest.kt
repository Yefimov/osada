package org.osada.ui.briefing

import org.osada.campaign.CampaignEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consequence preview shown under a dialogue choice.
 *
 * The contract has two halves, and the second is the important one: an authored `hint` wins, and
 * **narrative consequences are never disclosed**. `setFlag`/`clearFlag`/`route` drive later
 * scenarios and campaign branching — showing them would spoil the story and reduce a roleplay
 * decision to picking the bigger number.
 */
class BriefingChoicePreviewTest {
    private fun choice(
        effects: List<CampaignEffect> = emptyList(),
        hint: String = "",
    ) = BriefingChoice(id = "c", text = "Decide.", next = null, effects = effects, hint = hint)

    @Test
    fun authoredHintWinsOverGeneratedMechanics() {
        val c =
            choice(
                effects = listOf(CampaignEffect.Prestige("e1", 250)),
                hint = "Command will note your caution",
            )
        assertEquals("Command will note your caution", BriefingChoicePreview.of(c))
    }

    @Test
    fun immediateMechanicsAreSummarisedWhenNoHintIsAuthored() {
        val c =
            choice(
                effects =
                    listOf(
                        CampaignEffect.Prestige("e1", 250),
                        CampaignEffect.Resupply("e2", strength = 8, refuel = true, rearm = true),
                    ),
            )
        val preview = BriefingChoicePreview.of(c)
        assertTrue(preview.contains("+250 prestige"), "prestige should be summarised: $preview")
        assertTrue(preview.contains("Resupply"), "resupply should be summarised: $preview")
        assertTrue(preview.contains("+8 strength"), "resupply detail should be summarised: $preview")
    }

    @Test
    fun negativeAmountsKeepTheirSign() {
        val c = choice(effects = listOf(CampaignEffect.Prestige("e1", -120)))
        assertTrue(BriefingChoicePreview.of(c).contains("-120 prestige"))
    }

    /** THE rule: narrative state must never leak into the preview. */
    @Test
    fun flagsAndRoutingAreNeverDisclosed() {
        val c =
            choice(
                effects =
                    listOf(
                        CampaignEffect.SetFlag("e1", "favours_rapid_offensive"),
                        CampaignEffect.ClearFlag("e2", "favours_methodical_consolidation"),
                        CampaignEffect.Route("e3", scenarioIndex = 4),
                    ),
            )
        val preview = BriefingChoicePreview.of(c)
        assertEquals("", preview, "a purely narrative choice must preview as nothing: '$preview'")
    }

    /** Mixed effects: the mechanics show, the flag stays hidden. */
    @Test
    fun mechanicsShowWhileFlagsStayHiddenInTheSameChoice() {
        val c =
            choice(
                effects =
                    listOf(
                        CampaignEffect.SetFlag("e1", "favours_methodical_consolidation"),
                        CampaignEffect.Resupply("e2", strength = 8, refuel = true, rearm = true),
                    ),
            )
        val preview = BriefingChoicePreview.of(c)
        assertTrue(preview.contains("Resupply"), "the mechanical half should show: $preview")
        assertFalse(preview.contains("favours"), "the narrative half must not: $preview")
    }

    @Test
    fun experienceIsSummarised() {
        val c = choice(effects = listOf(CampaignEffect.GrantExperience("e1", amount = 60, unitClass = null)))
        assertTrue(BriefingChoicePreview.of(c).contains("+60 experience"))
    }

    /**
     * Effects that `CampaignEffectApplier` still no-ops (see the effect catalogue in
     * `docs/campaign-dialogue-and-consequences.md` §7) must not be previewed: promising the player
     * something that does not happen is worse than saying nothing. Re-enable each as its applier
     * lands — this test is the reminder.
     */
    @Test
    fun effectsThatAreNotAppliedYetAreNotPreviewed() {
        val c =
            choice(
                effects =
                    listOf(
                        CampaignEffect.DeploymentSlots("e1", delta = 2),
                        CampaignEffect.UnlockEquipment("e2", eqid = 1),
                        CampaignEffect.ShiftReinforcements("e3", side = 0, turns = -2),
                    ),
            )
        assertEquals(
            "",
            BriefingChoicePreview.of(c),
            "unapplied effects must not be promised in the preview",
        )
    }

    @Test
    fun aChoiceWithNoEffectsAndNoHintPreviewsAsNothing() {
        assertEquals("", BriefingChoicePreview.of(choice()))
    }
}
