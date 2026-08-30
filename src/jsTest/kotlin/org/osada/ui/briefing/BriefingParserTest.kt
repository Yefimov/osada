package org.osada.ui.briefing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefingParserTest {
    /**
     * The Kiel fixture both halves of the stable-key contract are checked against: one authored
     * briefing, one Russian translation table keyed by the STABLE ids rather than by the text.
     */
    private fun localizedKielBriefing(): ScenarioBriefing {
        val raw =
            js(
                "({ act: 'ACT I', location: 'Kiel', player: { speaker: 'Commander', role: 'HQ' }, " +
                    "dialogue: [{ id: 'decision', speaker: 'Karl', role: 'Sailor', text: 'Choose.', " +
                    "conditions: { allFlags: ['keep-routing'] }, " +
                    "choices: [{ id: 'station', text: 'Take the station.', hint: 'Gain prestige.', " +
                    "next: 'reply', effects: [{ id: 'keep-effect', type: 'prestige', amount: 50 }] }] }, " +
                    "{ id: 'reply', speaker: 'Karl', text: 'Good.' }] })",
            )
        val translations =
            mapOf(
                "header.act" to "АКТ I",
                "header.location" to "Киль",
                "player.speaker" to "Командующий",
                "player.role" to "Штаб",
                "line.decision.speaker" to "Карл",
                "line.decision.role" to "Матрос",
                "line.decision.text" to "Выбирайте.",
                "line.decision.choice.station.text" to "Занять вокзал.",
                "line.decision.choice.station.hint" to "Получите престиж.",
                "line.reply.speaker" to "Карл",
                "line.reply.text" to "Хорошо.",
            )
        return BriefingParser.parse(
            scenarioTitle = "Kiel",
            rawData = raw,
            textResolver = BriefingTextResolver { key, fallback -> translations[key] ?: fallback },
        )
    }

    /** Half one of the contract: every DISPLAYED string comes from the translation table. */
    @Test
    fun stableDialogueKeysLocalizeDisplayText() {
        val briefing = localizedKielBriefing()
        val choice =
            briefing.dialogue
                .first()
                .choices
                .single()

        assertEquals("АКТ I", briefing.actLabel)
        assertEquals("Киль", briefing.locationLabel)
        assertEquals("Командующий", briefing.player.speaker)
        assertEquals("Выбирайте.", briefing.dialogue.first().text)
        assertEquals("Занять вокзал.", choice.text)
        assertEquals("Получите престиж.", choice.hint)
    }

    /**
     * Half two, and the one that matters for saves: localizing must NOT move any identity. A
     * branch is followed by id, so a translated `next` or `id` would silently reroute the story.
     */
    @Test
    fun stableDialogueKeysLeaveBranchIdentityUnchanged() {
        val briefing = localizedKielBriefing()
        val line = briefing.dialogue.first()
        val choice = line.choices.single()

        assertEquals("decision", line.id)
        assertEquals("station", choice.id)
        assertEquals("reply", choice.next)
        assertEquals(listOf("keep-routing"), line.condition.allFlags)
        assertEquals("keep-effect", choice.effects.single().id)
    }

    @Test
    fun briefingDomainUsesOnlyCampaignAndScenarioFileStems() {
        assertEquals(
            "briefings/novemberrevolution/n_kiel",
            BriefingLocalization.domain("campaigns/NovemberRevolution.json", "scenarios/n_kiel.xml"),
        )
    }

    @Test
    fun missingBriefingDoesNotCreateReplacementForLegacyIntro() {
        val briefing =
            BriefingParser.parse(
                scenarioTitle = "Seseña",
                rawData = null,
            )

        assertTrue(briefing.dialogue.isEmpty())
        assertTrue(briefing.orders.isEmpty())
        assertFalse(briefing.hasContent())
    }

    @Test
    fun branchingDialoguePlayerAndOrdersAreParsed() {
        val raw =
            js(
                """({
            act: 'ACT I',
            location: 'Test Valley',
            player: { speaker: 'Commander', role: 'Field Command', side: 'left' },
            dialogue: [{
                id: 'opening',
                speaker: 'General Testov',
                role: 'Front Commander',
                side: 'right',
                text: 'Choose your route.',
                choices: [
                    { id: 'north', text: 'Advance north.', next: 'north-reply' },
                    { id: 'south', text: 'Advance south.', next: 'south-reply' }
                ]
            }, {
                id: 'north-reply',
                speaker: 'General Testov',
                text: 'The northern route is approved.'
            }],
            orders: {
                mission: 'Capture the bridge.',
                primaryObjectives: ['Take Bridge Hex', 'Hold it until turn 8']
            }
        })""",
            )

        val briefing = BriefingParser.parse("Test Operation", raw)

        assertEquals("ACT I", briefing.actLabel)
        assertEquals("Test Valley", briefing.locationLabel)
        assertEquals("Commander", briefing.player.speaker)
        assertEquals(2, briefing.dialogue.size)
        assertEquals("opening", briefing.dialogue[0].id)
        assertEquals(2, briefing.dialogue[0].choices.size)
        assertEquals("north-reply", briefing.dialogue[0].choices[0].next)
        assertEquals("Capture the bridge.", briefing.orders.mission)
        assertEquals(2, briefing.orders.primaryObjectives.size)
    }

    @Test
    fun malformedOptionalFieldsDoNotCreateFakeContent() {
        val raw = js("({ dialogue: [{ speaker: 12, text: '' }], orders: { mission: 9 } })")
        val briefing = BriefingParser.parse("Test", raw)

        assertTrue(briefing.dialogue.isEmpty())
        assertTrue(briefing.orders.isEmpty())
        assertFalse(briefing.hasContent())
    }

    @Test
    fun remoteOrTraversalAssetPathsAreRejected() {
        val raw =
            js(
                "({ background: 'https://example.invalid/a.jpg', dialogue: [{ speaker: 'X', text: 'Y', " +
                    "portrait: '../secret.png' }] })",
            )
        val briefing = BriefingParser.parse("Test", raw)

        assertNull(briefing.background)
        assertNull(briefing.dialogue.single().portrait)
    }

    @Test
    fun missingDialogueFallsBackToOrdersOnly() {
        val raw = js("({ orders: { mission: 'Hold the line.' } })")
        val briefing = BriefingParser.parse("Test", raw)

        assertTrue(briefing.dialogue.isEmpty())
        assertEquals("Hold the line.", briefing.orders.mission)
        assertTrue(briefing.hasContent())
    }

    @Test
    fun missingOrdersFallsBackToDialogueOnly() {
        val raw = js("({ dialogue: [{ speaker: 'X', text: 'Move out.' }] })")
        val briefing = BriefingParser.parse("Test", raw)

        assertTrue(briefing.orders.isEmpty())
        assertEquals(1, briefing.dialogue.size)
        assertTrue(briefing.hasContent())
    }

    @Test
    fun malformedRawDataNeverThrowsAndFallsBackToMinimalBriefing() {
        // A getter that throws when the parser touches it -- simulates truly hostile/corrupt
        // campaign data (not just wrong types, which the other tests already cover).
        val raw = js("Object.defineProperty({}, 'dialogue', { get() { throw new Error('boom') } })")

        val briefing = BriefingParser.parse("Test", raw)

        assertTrue(briefing.dialogue.isEmpty())
        assertTrue(briefing.orders.isEmpty())
        assertEquals("Test", briefing.title)
    }

    @Test
    fun branchChoiceNextResolvesToTargetLine() {
        val briefing =
            BriefingParser.parse(
                "Test",
                js(
                    "({ dialogue: [" +
                        "{ id: 'a', speaker: 'X', text: 'First', choices: [{ id: 'c', text: 'Go', next: 'b' }] }, " +
                        "{ id: 'b', speaker: 'X', text: 'Second' }" +
                        "] })",
                ),
            )

        val first = briefing.dialogue.first()
        val target = briefing.lineById(first.choices.single().next)

        assertEquals("b", target?.id)
        assertEquals("Second", target?.text)
    }

    @Test
    fun malformedNextRefFailsClosedInsteadOfCrashing() {
        val briefing =
            BriefingParser.parse(
                "Test",
                js("({ dialogue: [{ id: 'a', speaker: 'X', text: 'First', next: 'does-not-exist' }] })"),
            )

        val first = briefing.dialogue.single()
        assertEquals("does-not-exist", first.next)
        assertNull(briefing.lineById(first.next))
    }

    @Test
    fun nextSequentialFallsBackWhenNoExplicitNextIsGiven() {
        val briefing =
            BriefingParser.parse(
                "Test",
                js(
                    "({ dialogue: [" +
                        "{ id: 'a', speaker: 'X', text: 'First' }, " +
                        "{ id: 'b', speaker: 'X', text: 'Second' }" +
                        "] })",
                ),
            )

        val first = briefing.dialogue.first()
        val second = briefing.nextSequential(first)

        assertEquals("b", second?.id)
        assertNull(briefing.nextSequential(briefing.dialogue.last()))
    }
}
