package org.osada

import org.osada.campaign.CampaignCondition
import org.osada.campaign.CampaignConditionEvaluator
import org.osada.campaign.CampaignConditionParser
import org.osada.campaign.CampaignContext
import org.osada.campaign.CampaignEffect
import org.osada.campaign.CampaignEffectApplier
import org.osada.campaign.CampaignEffectParser
import org.osada.campaign.CampaignNarrative
import org.osada.campaign.CampaignNarrativeSerializer
import org.osada.campaign.CampaignNarrativeState
import org.osada.campaign.EffectLimits
import org.osada.campaign.PendingEffect
import org.osada.campaign.ScenarioOutcomeRecord
import org.osada.model.Player
import org.osada.ui.briefing.BriefingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level guards for the campaign narrative and consequence system.
 *
 * These exercise the pure campaign package (state, conditions, effects, persistence) plus the
 * dialogue parser. They deliberately do NOT drive the DOM briefing controller or a live Game —
 * the once-only guarantees they check live in [CampaignNarrativeState] and
 * [CampaignEffectApplier], which is where every UI path funnels through.
 */
class CampaignNarrativeTest {
    private fun parse(json: String): dynamic = JSON.parse<Any>(json)

    private fun stateWith(vararg outcomes: Pair<String, String>): CampaignNarrativeState {
        val state = CampaignNarrativeState()
        outcomes.forEach { (scenario, outcome) ->
            state.recordOutcome(ScenarioOutcomeRecord(scenario, outcome, null))
        }
        return state
    }

    private fun contextFor(
        state: CampaignNarrativeState,
        scenario: String = "n_berlin.xml",
        index: Int = 3,
    ) = CampaignContext("novemberrevolution.json", scenario, index, state)

    // ------------------------------------------------------- backward compatibility

    @Test
    fun oldDialogueWithoutConditionsStillParses() {
        val raw =
            parse(
                """
                {"dialogue":[
                  {"id":"a","speaker":"Karl Artelt","role":"Sailor","text":"The fleet will not sail."},
                  {"id":"b","speaker":"Commander","role":"HQ","text":"Then we take the city."}
                ]}
                """.trimIndent(),
            )
        val briefing = BriefingParser.parse("Kiel", raw)
        assertEquals(2, briefing.dialogue.size)
        assertTrue(
            briefing.dialogue.all { it.condition.isEmpty() },
            "condition-free lines must parse to the always-matching EMPTY condition",
        )
        assertTrue(
            briefing.dialogue.all { line ->
                CampaignConditionEvaluator.matches(line.condition, contextFor(CampaignNarrativeState()))
            },
            "legacy dialogue must remain visible under every campaign state",
        )
    }

    @Test
    fun oldSaveWithoutNarrativeBlockLoadsAsEmptyState() {
        assertTrue(CampaignNarrativeSerializer.deserialize(null).isEmpty)
        assertTrue(CampaignNarrativeSerializer.deserialize(parse("""{"id":1,"file":"rhu.json"}""").narrative).isEmpty)
    }

    @Test
    fun corruptNarrativeBlockFallsBackToEmptyRatherThanFailingTheLoad() {
        val corrupt = parse("""{"outcomes":"not-an-array","flags":42,"pending":{"nope":true}}""")
        assertTrue(CampaignNarrativeSerializer.deserialize(corrupt).isEmpty)
    }

    @Test
    fun unknownConditionKeysAreIgnoredRatherThanBlockingTheLine() {
        val condition = CampaignConditionParser.parse(parse("""{"previousOutcome":["victory"],"watVerb":["x"]}"""))
        assertEquals(listOf("victory"), condition.previousOutcome)
        val state = stateWith("n_kiel.xml" to "victory")
        assertTrue(
            CampaignConditionEvaluator.matches(condition, contextFor(state)),
            "an unknown key must not turn a satisfiable condition into an unsatisfiable one",
        )
    }

    @Test
    fun malformedConditionObjectDegradesToAlwaysShown() {
        assertTrue(CampaignConditionParser.parse(parse("""[1,2,3]""")).isEmpty())
        assertTrue(CampaignConditionParser.parse(null).isEmpty())
    }

    // ------------------------------------------------------------- real outcomes

    @Test
    fun legacyBriliantSpellingIsHonouredEndToEnd() {
        val state = stateWith("rhu190523.xml" to "briliant")
        val condition = CampaignConditionParser.parse(parse("""{"previousOutcome":["briliant"]}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(state)))
        assertEquals("briliant", state.previousOutcome()?.outcome)

        val roundTripped = CampaignNarrativeSerializer.deserialize(CampaignNarrativeSerializer.serialize(state))
        assertEquals("briliant", roundTripped.previousOutcome()?.outcome, "the legacy spelling must survive a save")
    }

    @Test
    fun differentOutcomesFromTheSamePredecessorRemainDistinguishable() {
        val condition = CampaignConditionParser.parse(parse("""{"previousOutcome":["briliant","victory"]}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(stateWith("n_kiel.xml" to "briliant"))))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(stateWith("n_kiel.xml" to "victory"))))
        assertFalse(CampaignConditionEvaluator.matches(condition, contextFor(stateWith("n_kiel.xml" to "tactical"))))
    }

    @Test
    fun differentPredecessorsLeadingToTheSameScenarioRemainDistinguishable() {
        val condition = CampaignConditionParser.parse(parse("""{"previousScenario":["n_kiel.xml"]}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(stateWith("n_kiel.xml" to "victory"))))
        assertFalse(
            CampaignConditionEvaluator.matches(condition, contextFor(stateWith("n_frankfurt.xml" to "victory"))),
            "a line gated on one predecessor must not fire after a different one",
        )
    }

    @Test
    fun namedEarlierScenarioOutcomeIsQueryable() {
        val state = stateWith("rhu190416.xml" to "lose", "rhu190424.xml" to "victory")
        val condition = CampaignConditionParser.parse(parse("""{"scenarioOutcome":{"rhu190416.xml":["lose"]}}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(state)))

        val absent = CampaignConditionParser.parse(parse("""{"scenarioOutcome":{"rhu190501.xml":["lose"]}}"""))
        assertFalse(
            CampaignConditionEvaluator.matches(absent, contextFor(state)),
            "an unplayed scenario must not satisfy an outcome condition",
        )
    }

    @Test
    fun outcomeIsRecordedExactlyOnceEvenWhenBothCompletionPathsFire() {
        val state = CampaignNarrativeState()
        assertTrue(state.recordOutcome(ScenarioOutcomeRecord("n_kiel.xml", "victory", "n_willhelmsh.xml")))
        // Move-capture victory and end-turn defeat detection can both reach continueCampaign.
        assertFalse(state.recordOutcome(ScenarioOutcomeRecord("n_kiel.xml", "lose", null)))
        assertEquals(1, state.scenarioOutcomes.size)
        assertEquals("victory", state.outcomeOf("n_kiel.xml"), "the first definitive result wins")
    }

    @Test
    fun successCountsDriveCumulativeConditions() {
        val state = stateWith("a.xml" to "briliant", "b.xml" to "tactical", "c.xml" to "lose")
        assertEquals(2, state.countOutcomes("briliant", "victory", "tactical"))
        val condition = CampaignConditionParser.parse(parse("""{"minSuccesses":2}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(state)))
        assertFalse(
            CampaignConditionEvaluator.matches(
                CampaignConditionParser.parse(parse("""{"minSuccesses":3}""")),
                contextFor(state),
            ),
        )
    }

    // ------------------------------------------------------------------ choices

    @Test
    fun choiceIsCommittedExactlyOnceAndCannotBeChangedByReview() {
        val state = CampaignNarrativeState()
        assertTrue(state.recordChoice("kiel-decision", "kiel.free_prisoners"))
        // Double-click, Back-and-forward, briefing reopened, save restored: all re-enter here.
        assertFalse(state.recordChoice("kiel-decision", "kiel.free_prisoners"))
        assertFalse(
            state.recordChoice("kiel-decision", "kiel.secure_communications"),
            "inspecting another branch must not change the committed choice",
        )
        assertEquals("kiel.free_prisoners", state.choiceAt("kiel-decision"))
        assertTrue(state.chose("kiel.free_prisoners"))
        assertFalse(state.chose("kiel.secure_communications"))
    }

    @Test
    fun selectedChoiceConditionsReactToCommittedBranch() {
        val state = CampaignNarrativeState()
        state.recordChoice("kiel-decision", "kiel.free_prisoners")
        val condition = CampaignConditionParser.parse(parse("""{"selectedChoices":["kiel.free_prisoners"]}"""))
        assertTrue(CampaignConditionEvaluator.matches(condition, contextFor(state)))
    }

    // ------------------------------------------------------------------ effects

    @Test
    fun effectsApplyExactlyOnceNoMatterHowOftenTheyAreReplayed() {
        val state = CampaignNarrativeState()
        val player = Player().apply { prestige = 1000 }
        val effects = listOf(CampaignEffect.Prestige("kiel.choice.prisoners.prestige", 200))

        assertEquals(listOf("kiel.choice.prisoners.prestige"), CampaignEffectApplier.apply(effects, player, state))
        assertEquals(1200, player.prestige)

        // Reopened briefing, reviewed dialogue, double-click, restored save, re-fired transition.
        repeat(4) { CampaignEffectApplier.apply(effects, player, state) }
        assertEquals(1200, player.prestige, "a persistent effect must never stack")
    }

    @Test
    fun appliedEffectIdsSurviveASaveSoRewardsDoNotStackOnReload() {
        val state = CampaignNarrativeState()
        val player = Player().apply { prestige = 500 }
        val effects = listOf(CampaignEffect.Prestige("rhu.miskolc.industry_saved", 150))
        CampaignEffectApplier.apply(effects, player, state)
        assertEquals(650, player.prestige)

        val reloaded = CampaignNarrativeSerializer.deserialize(CampaignNarrativeSerializer.serialize(state))
        CampaignEffectApplier.apply(effects, player, reloaded)
        assertEquals(650, player.prestige, "repeated loading must not stack rewards")
    }

    @Test
    fun prestigeEffectsRespectBoundsAndNeverGoNegative() {
        val huge = CampaignEffectParser.parseList(parse("""[{"id":"e","type":"prestige","amount":99999}]"""))
        assertEquals(
            EffectLimits.MAX_PRESTIGE_DELTA,
            (huge.single() as CampaignEffect.Prestige).amount,
            "an over-large authored award must be clamped, not trusted",
        )

        val state = CampaignNarrativeState()
        val player = Player().apply { prestige = 50 }
        CampaignEffectApplier.apply(listOf(CampaignEffect.Prestige("p", -400)), player, state)
        assertEquals(0, player.prestige, "a penalty may empty the treasury but must not create debt")
    }

    @Test
    fun experienceEffectsAreClampedToTheEngineLimit() {
        val parsed = CampaignEffectParser.parseList(parse("""[{"id":"x","type":"experience","amount":9000}]"""))
        assertEquals(EffectLimits.MAX_EXPERIENCE_DELTA, (parsed.single() as CampaignEffect.GrantExperience).amount)
    }

    @Test
    fun grantUnitWithMissingEquipmentFailsSafely() {
        val state = CampaignNarrativeState()
        val player = Player()
        val before = player.getCoreUnitList().size
        // eqid 999999 is not in any efile: the effect must be skipped, not crash the campaign.
        CampaignEffectApplier.apply(listOf(CampaignEffect.GrantUnit("g", 999999, 0, 10)), player, state)
        assertEquals(before, player.getCoreUnitList().size)
        assertTrue(
            state.effects.isApplied("g"),
            "a dead reference is still marked applied so it cannot re-warn forever",
        )
    }

    @Test
    fun unknownEffectTypesAndIdlessEffectsAreDroppedWithoutLosingSiblings() {
        val parsed =
            CampaignEffectParser.parseList(
                parse(
                    """[
                      {"id":"ok","type":"setFlag","flag":"favours_rapid_offensive"},
                      {"type":"prestige","amount":100},
                      {"id":"weird","type":"summonDragon"},
                      {"id":"noflag","type":"setFlag"}
                    ]""",
                ),
            )
        assertEquals(1, parsed.size, "malformed effects drop individually; valid siblings survive")
        assertEquals("ok", parsed.single().id)
    }

    @Test
    fun duplicateEffectIdsInOneListAreRejected() {
        val parsed =
            CampaignEffectParser.parseList(
                parse("""[{"id":"d","type":"prestige","amount":10},{"id":"d","type":"prestige","amount":20}]"""),
            )
        assertEquals(1, parsed.size)
        assertEquals(10, (parsed.single() as CampaignEffect.Prestige).amount)
    }

    // ------------------------------------------------------------ pending effects

    @Test
    fun pendingEffectsApplyOnlyToTheIntendedScenario() {
        val state = CampaignNarrativeState()
        state.effects.queue(PendingEffect("n_willhelmsh.xml", CampaignEffect.Prestige("a", 100)))
        state.effects.queue(PendingEffect("n_berlin.xml", CampaignEffect.Prestige("b", 100)))

        assertTrue(state.effects.takeFor("n_frankfurt.xml").isEmpty(), "an unrelated scenario consumes nothing")
        assertEquals(listOf("a"), state.effects.takeFor("n_willhelmsh.xml").map { it.id })
        assertEquals(
            listOf("b"),
            state.effects.pending.map { it.effect.id },
            "effects for other scenarios must stay queued",
        )
    }

    @Test
    fun pendingEffectsAreConsumedOnceAndDoNotReapplyAfterRestore() {
        val state = CampaignNarrativeState()
        val player = Player().apply { prestige = 100 }
        state.effects.queue(PendingEffect("n_willhelmsh.xml", CampaignEffect.Prestige("kiel.reward", 200)))

        CampaignEffectApplier.apply(state.effects.takeFor("n_willhelmsh.xml"), player, state)
        assertEquals(300, player.prestige)
        assertTrue(state.effects.pending.isEmpty())

        // Save mid-scenario and restore: the queue is empty AND the id is marked applied.
        val restored = CampaignNarrativeSerializer.deserialize(CampaignNarrativeSerializer.serialize(state))
        CampaignEffectApplier.apply(restored.effects.takeFor("n_willhelmsh.xml"), player, restored)
        assertEquals(300, player.prestige, "restoring inside the scenario must not re-apply its setup effects")
    }

    @Test
    fun queueingAnAlreadyAppliedEffectIsANoOp() {
        val state = CampaignNarrativeState()
        state.effects.markApplied("once")
        state.effects.queue(PendingEffect("x.xml", CampaignEffect.Prestige("once", 100)))
        assertTrue(state.effects.pending.isEmpty())
    }

    // --------------------------------------------------------------- route override

    @Test
    fun routeEffectCommitsAnOverrideConsumedExactlyOnce() {
        val state = CampaignNarrativeState()
        val player = Player()
        CampaignEffectApplier.apply(listOf(CampaignEffect.Route("choice.route", scenarioIndex = 4)), player, state)
        assertEquals(4, state.route.peek(), "committing a route effect must record its target")
        assertEquals(4, state.route.take())
        assertNull(state.route.take(), "a committed route must not leak into resolving a later transition")
    }

    @Test
    fun committedRouteSurvivesASaveBeforeItIsConsumed() {
        val state = CampaignNarrativeState()
        state.route.set(7)
        val restored = CampaignNarrativeSerializer.deserialize(CampaignNarrativeSerializer.serialize(state))
        assertEquals(
            7,
            restored.route.peek(),
            "a route committed but not yet consumed must round-trip through a save",
        )
    }

    @Test
    fun campaignNarrativeExposesTheCommittedRouteExactlyOnce() {
        CampaignNarrative.reset()
        CampaignNarrative.state.route.set(2)
        assertEquals(2, CampaignNarrative.takeCommittedRoute())
        assertNull(CampaignNarrative.takeCommittedRoute(), "the facade must consume the same way the state does")
    }

    // -------------------------------------------------------------- persistence

    @Test
    fun fullNarrativeStateRoundTripsThroughASave() {
        val state = CampaignNarrativeState()
        state.recordOutcome(ScenarioOutcomeRecord("n_kiel.xml", "briliant", "n_willhelmsh.xml"))
        state.recordChoice("kiel-decision", "kiel.free_prisoners")
        state.setFlag("favours_political_mobilisation")
        state.actions.record("n_kiel.xml", "airfield_held_at_end")
        state.effects.markApplied("kiel.choice.prisoners.prestige")
        state.effects.queue(PendingEffect("n_willhelmsh.xml", CampaignEffect.GrantUnit("kiel.sailors", 42, 60, 8)))

        val restored = CampaignNarrativeSerializer.deserialize(CampaignNarrativeSerializer.serialize(state))

        assertEquals(state.scenarioOutcomes, restored.scenarioOutcomes)
        assertEquals(state.selectedChoices, restored.selectedChoices)
        assertEquals(state.flags, restored.flags)
        assertEquals(setOf("airfield_held_at_end"), restored.actions.forScenario("n_kiel.xml"))
        assertTrue(restored.effects.isApplied("kiel.choice.prisoners.prestige"))
        assertEquals(1, restored.effects.pending.size)
        val grant =
            restored.effects.pending
                .single()
                .effect as CampaignEffect.GrantUnit
        assertEquals(
            "n_willhelmsh.xml",
            restored.effects.pending
                .single()
                .targetScenario,
        )
        assertEquals(42, grant.eqid)
        assertEquals(60, grant.experience)
        assertEquals(8, grant.strength)
    }

    @Test
    fun startingANewCampaignClearsNarrativeState() {
        CampaignNarrative.reset()
        CampaignNarrative.state.recordOutcome(ScenarioOutcomeRecord("n_kiel.xml", "briliant", null))
        CampaignNarrative.state.setFlag("workers_armed")
        assertFalse(CampaignNarrative.state.isEmpty)
        assertNotNull(CampaignNarrative.snapshot(), "a run with history must produce a save block")

        CampaignNarrative.reset()
        assertTrue(CampaignNarrative.state.isEmpty, "a new campaign starts with no remembered history")
        assertNull(
            CampaignNarrative.snapshot(),
            "an empty run writes no narrative block, so saves keep their previous shape",
        )
    }

    @Test
    fun outcomesWithUnknownGradesAreRejectedOnLoad() {
        val tampered = parse("""{"outcomes":[{"scenario":"n_kiel.xml","outcome":"flawless"}]}""")
        assertTrue(
            CampaignNarrativeSerializer.deserialize(tampered).scenarioOutcomes.isEmpty(),
            "only the four engine outcome grades are accepted as truth",
        )
    }

    // --------------------------------------------------------------- flags/actions

    @Test
    fun flagConditionsCombineAllAnyAndNone() {
        val state = CampaignNarrativeState()
        state.setFlag("favours_rapid_offensive")
        state.setFlag("workers_armed")

        val ok =
            CampaignConditionParser.parse(
                parse(
                    """{"allFlags":["favours_rapid_offensive"],"anyFlags":["workers_armed","militia_raised"],
                       "noneFlags":["staff_confidence_broken"]}""",
                ),
            )
        assertTrue(CampaignConditionEvaluator.matches(ok, contextFor(state)))

        state.setFlag("staff_confidence_broken")
        assertFalse(CampaignConditionEvaluator.matches(ok, contextFor(state)), "noneFlags must veto")
    }

    @Test
    fun scenarioActionsAreQualifiedByScenarioAndQueryableByCondition() {
        val state = CampaignNarrativeState()
        state.actions.record("n_kiel.xml", "airfield_held_at_end")
        assertTrue(state.actions.has("n_kiel.xml.airfield_held_at_end"))
        assertFalse(state.actions.has("n_berlin.xml.airfield_held_at_end"), "actions must not leak across scenarios")

        val completed =
            CampaignConditionParser.parse(parse("""{"completedActions":["n_kiel.xml.airfield_held_at_end"]}"""))
        assertTrue(CampaignConditionEvaluator.matches(completed, contextFor(state)))

        val failed =
            CampaignConditionParser.parse(parse("""{"failedActions":["n_kiel.xml.airfield_held_at_end"]}"""))
        assertFalse(
            CampaignConditionEvaluator.matches(failed, contextFor(state)),
            "failedActions must be the exact negation of completedActions",
        )
    }

    @Test
    fun emptyConditionMatchesEverything() {
        assertTrue(CampaignConditionEvaluator.matches(CampaignCondition.EMPTY, contextFor(CampaignNarrativeState())))
    }
}
