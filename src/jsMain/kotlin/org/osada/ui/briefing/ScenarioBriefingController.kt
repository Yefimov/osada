package org.osada.ui.briefing

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

/**
 * Owns conversation branching, briefing navigation, focus management and replay.
 *
 * Dialogue navigation and keyboard/focus handling live as extension functions in the sibling
 * `ScenarioBriefingNavigation.kt` and `ScenarioBriefingKeyboard.kt` files (same package) to
 * stay within the project's function-count limits; this object keeps the lifecycle
 * (show/reopen/close) and stage rendering, plus the shared mutable state.
 */
internal object ScenarioBriefingController {
    internal data class DialogueStep(
        val lineId: String,
        var selectedChoice: BriefingChoice? = null,
    )

    internal var briefing: ScenarioBriefing? = null
    internal var facts: ScenarioFacts? = null
    private var lastSource: BriefingSource? = null
    private var lastFacts: ScenarioFacts? = null
    internal var currentSource: BriefingSource? = null
    internal var beginLabelKey: String = "briefing.begin.label"
    internal var view: ScenarioBriefingView? = null

    /** Text element of the turn currently typewriter-revealing, so an advance input can complete
     *  it in place (each turn owns its own element now that the log keeps them all). */
    internal var revealingLine: HTMLElement? = null
    internal var stage: BriefingStage = BriefingStage.ORDERS
    internal val path = mutableListOf<DialogueStep>()
    private var finishCallback: (() -> Unit)? = null
    private var previousFocus: HTMLElement? = null

    init {
        I18n.onLanguageChanged { refreshLocalization() }
    }

    fun show(
        campaignFile: String,
        scenarioFile: String,
        scenarioFacts: ScenarioFacts,
        rawData: dynamic,
        onFinished: () -> Unit,
    ) {
        val source = BriefingSource(campaignFile, scenarioFile, scenarioFacts.title, rawData)
        lastSource = source
        lastFacts = scenarioFacts
        BriefingLocalization.ensure(source) {
            if (lastSource !== source) return@ensure
            val localizedFacts = BriefingLocalization.localizeFacts(source, scenarioFacts)
            showParsed(
                BriefingLocalization.parse(source),
                source,
                localizedFacts,
                "briefing.begin.label",
                onFinished,
            )
        }
    }

    /** Cache the briefing for the reopen button WITHOUT showing it — used by the retry
     *  fast-path, which skips the ceremony but must keep the briefing reachable in battle. */
    fun prime(
        campaignFile: String,
        scenarioFile: String,
        scenarioFacts: ScenarioFacts,
        rawData: dynamic,
    ) {
        val source = BriefingSource(campaignFile, scenarioFile, scenarioFacts.title, rawData)
        lastSource = source
        lastFacts = scenarioFacts
        // Preload now so reopening the reference sheet never flashes the English source first.
        BriefingLocalization.ensure(source) {}
    }

    /**
     * Re-opens the briefing from the HUD button — on the **orders sheet**, not the conversation.
     *
     * Replaying the dialogue was actively harmful, not just verbose. Every decision in it has
     * already been committed (`CampaignNarrative.commitChoice` is a once-only gate), so the choice
     * buttons came back live but inert; and pressing SKIP on the replay made
     * [pendingChoiceLine] report the decision as unanswered again, which
     * [renderPendingDecision] answers by DISABLING "RETURN TO BATTLE" until the player re-decides
     * something that can no longer have any effect. Reported 2026-08-01. The conversation is a
     * scene that plays once; the orders are the reference sheet, and that is what a re-read wants.
     */
    fun reopenLast(onClosed: () -> Unit): Boolean {
        val source = lastSource
        val reopenFacts = lastFacts
        if (source == null || reopenFacts == null) return false
        BriefingLocalization.ensure(source) {
            if (lastSource !== source) return@ensure
            val localizedFacts = BriefingLocalization.localizeFacts(source, reopenFacts)
            showParsed(
                BriefingLocalization.parse(source),
                source,
                localizedFacts,
                "briefing.return_to_battle.label",
                onClosed,
                reviewing = true,
            )
        }
        return true
    }

    fun isVisible(): Boolean = view != null

    /** Esc on the ORDERS stage acts like the primary button: begin the operation — unless a
     *  decision is still owed, which BEGIN itself is disabled for. Esc must not be a way around it. */
    internal fun finishBriefing() {
        if (pendingChoiceLine() != null) return
        close(runCallback = true)
    }

    fun clearLast() {
        lastSource = null
        lastFacts = null
    }

    private fun showParsed(
        parsed: ScenarioBriefing,
        source: BriefingSource,
        scenarioFacts: ScenarioFacts,
        beginKey: String,
        onFinished: () -> Unit,
        reviewing: Boolean = false,
    ) {
        close(runCallback = false)

        briefing = parsed
        currentSource = source
        facts = scenarioFacts
        beginLabelKey = beginKey
        finishCallback = onFinished
        previousFocus = document.activeElement as? HTMLElement
        stage = if (parsed.dialogue.isNotEmpty() && !reviewing) BriefingStage.DIALOGUE else BriefingStage.ORDERS
        path.clear()
        // A review leaves the path EMPTY on purpose: `pendingChoiceLine` reads the head of the
        // path, so an empty path is what makes the decision block (and its BEGIN gate) stay away.
        if (!reviewing) parsed.dialogue.firstOrNull()?.let { path += DialogueStep(it.id) }

        val created =
            ScenarioBriefingBuilder.create(
                onAdvance = { advanceOrComplete() },
                onSkip = { skipToNextChoiceOrOrders() },
                onBegin = { close(runCallback = true) },
            )
        view = created
        renderLocalizedChrome(created, parsed, scenarioFacts)
        created.root.addEventListener("keydown", { e -> handleKeyDown(e) })

        renderCurrentStage()
        js("setTimeout")({ focusPrimaryControl() }, 0)
    }

    internal fun renderCurrentStage() {
        val data = briefing ?: return
        val currentView = view ?: return
        ScenarioBriefingBuilder.showStage(currentView, stage)
        if (stage == BriefingStage.DIALOGUE) {
            renderDialogueStage(currentView)
        } else {
            BriefingTypewriter.cancel()
            ScenarioBriefingBuilder.renderOrders(currentView, data.orders, facts)
            // A decision the player skipped past follows them here and gates BEGIN.
            renderPendingDecision(currentView, pendingChoiceLine()) { chooseFromOrders(it) }
        }
    }

    /** Turns already in the transcript DOM, so [renderDialogueStage] can APPEND the new ones
     *  instead of rebuilding the log. Reset by [close] and whenever the branch is re-rendered from
     *  scratch. */
    internal var renderedTurns: List<DialogueTurn> = emptyList()

    /** Extends the conversation log to the current branch, then typewriter-reveals only its newest
     *  turn -- everything the player has already read stays on screen, in order, and stays
     *  scrollable.
     *
     *  Every advance used to `clearTranscript` and re-append every turn from the beginning. That is
     *  what the player saw as "all dialogue disappears for a second and then reloads" when clicking
     *  to skip the typewriter: the click completes the line, the completion callback re-renders the
     *  stage, and the whole log is torn out of the DOM and rebuilt one node at a time. Appending
     *  only what is new keeps the read history untouched, so nothing flashes. The full rebuild is
     *  still there for the cases that need it -- a first render, and going BACK up the branch after
     *  a choice, where the tail of the log is no longer what is on screen. */
    private fun renderDialogueStage(currentView: ScenarioBriefingView) {
        val data = briefing
        val current = currentLine()
        val turns = if (data != null && current != null) buildTurns(data) else emptyList()
        val newest = turns.lastOrNull()
        if (current == null || newest == null) {
            showOrders()
            return
        }

        val alreadyRendered = turns.size > renderedTurns.size && turns.startsWithTurns(renderedTurns)
        val from =
            if (alreadyRendered) {
                renderedTurns.size
            } else {
                ScenarioBriefingBuilder.clearTranscript(currentView)
                0
            }
        turns.subList(from, turns.size - 1).forEach { turn ->
            ScenarioBriefingBuilder.appendTurn(currentView, turn).textContent = plainText(turn.text)
        }
        val newestEl = ScenarioBriefingBuilder.appendTurn(currentView, newest)
        renderedTurns = turns
        revealingLine = newestEl

        val pendingChoice = path.lastOrNull()?.selectedChoice
        val choices = if (pendingChoice == null) current.choices else emptyList()
        ScenarioBriefingBuilder.setChoices(currentView, choices) { choose(it) }
        ScenarioBriefingBuilder.setRevealed(currentView, revealed = false, hasChoices = choices.isNotEmpty())
        BriefingTypewriter.start(
            el = newestEl,
            fullText = plainText(newest.text),
            onProgress = { ScenarioBriefingBuilder.scrollTranscriptToEnd(currentView) },
        ) {
            ScenarioBriefingBuilder.scrollTranscriptToEnd(currentView)
            ScenarioBriefingBuilder.setRevealed(currentView, revealed = true, hasChoices = choices.isNotEmpty())
            if (choices.isNotEmpty()) {
                currentView.choicesBox
                    .querySelector("button")
                    ?.asDynamic()
                    ?.focus()
            }
        }
    }

    /** Whether this log still begins with exactly the turns already on screen. [DialogueTurn] and
     *  [BriefingParticipant] are data classes, so this is a value comparison and survives
     *  `buildTurns` rebuilding its list from the path each time. */
    private fun List<DialogueTurn>.startsWithTurns(prefix: List<DialogueTurn>): Boolean =
        prefix.indices.all { this[it] == prefix[it] }

    private fun close(runCallback: Boolean) {
        BriefingTypewriter.cancel()
        revealingLine = null
        renderedTurns = emptyList()
        val current = view
        view = null
        current?.root?.parentElement?.removeChild(current.root)
        briefing = null
        currentSource = null
        facts = null
        path.clear()

        val callback = finishCallback
        finishCallback = null
        previousFocus?.focus()
        previousFocus = null

        if (runCallback) callback?.invoke()
    }
}
