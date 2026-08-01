package org.osada.ui.briefing

import kotlinx.browser.document
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
    // The staff-table photo the main menu uses, so an operation with no authored art of its own
    // opens on the same desk the player just left. (The old default pointed into the legacy
    // `animatedBackground` slideshow's stills, which that rotating-photo treatment retired.)
    private const val DEFAULT_BACKGROUND = "resources/staff_table_background.png"

    internal data class DialogueStep(
        val lineId: String,
        var selectedChoice: BriefingChoice? = null,
    )

    internal var briefing: ScenarioBriefing? = null
    internal var facts: ScenarioFacts? = null
    private var lastBriefing: ScenarioBriefing? = null
    private var lastFacts: ScenarioFacts? = null
    internal var view: ScenarioBriefingView? = null

    /** Text element of the turn currently typewriter-revealing, so an advance input can complete
     *  it in place (each turn owns its own element now that the log keeps them all). */
    internal var revealingLine: HTMLElement? = null
    internal var stage: BriefingStage = BriefingStage.ORDERS
    internal val path = mutableListOf<DialogueStep>()
    private var finishCallback: (() -> Unit)? = null
    private var previousFocus: HTMLElement? = null

    fun show(
        scenarioFacts: ScenarioFacts,
        rawData: dynamic,
        onFinished: () -> Unit,
    ) {
        val parsed = CampaignDialogueFilter.apply(BriefingParser.parse(scenarioFacts.title, rawData))
        lastBriefing = parsed
        lastFacts = scenarioFacts
        showParsed(parsed, scenarioFacts, "BEGIN OPERATION", onFinished)
    }

    /** Cache the briefing for the reopen button WITHOUT showing it — used by the retry
     *  fast-path, which skips the ceremony but must keep the briefing reachable in battle. */
    fun prime(
        scenarioFacts: ScenarioFacts,
        rawData: dynamic,
    ) {
        lastBriefing = CampaignDialogueFilter.apply(BriefingParser.parse(scenarioFacts.title, rawData))
        lastFacts = scenarioFacts
    }

    fun reopenLast(onClosed: () -> Unit): Boolean {
        val parsed = lastBriefing
        val reopenFacts = lastFacts
        if (parsed == null || reopenFacts == null) return false
        showParsed(parsed, reopenFacts, "RETURN TO BATTLE", onClosed)
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
        lastBriefing = null
        lastFacts = null
    }

    private fun showParsed(
        parsed: ScenarioBriefing,
        scenarioFacts: ScenarioFacts,
        beginLabel: String,
        onFinished: () -> Unit,
    ) {
        close(runCallback = false)

        briefing = parsed
        facts = scenarioFacts
        finishCallback = onFinished
        previousFocus = document.activeElement as? HTMLElement
        stage = if (parsed.dialogue.isNotEmpty()) BriefingStage.DIALOGUE else BriefingStage.ORDERS
        path.clear()
        parsed.dialogue.firstOrNull()?.let { path += DialogueStep(it.id) }

        val created =
            ScenarioBriefingBuilder.create(
                onAdvance = { advanceOrComplete() },
                onSkip = { skipToNextChoiceOrOrders() },
                onBegin = { close(runCallback = true) },
            )
        view = created
        created.title.textContent = parsed.title
        created.subtitle.textContent = "${parsed.actLabel} · ${parsed.locationLabel}"
        created.headerDate.textContent = scenarioFacts.dateLabel
        created.beginButton.textContent = beginLabel
        val background =
            parsed.background
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_BACKGROUND
        created.backdrop.style.backgroundImage = "url(\"$background\")"
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
    private var renderedTurns: List<DialogueTurn> = emptyList()

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
        facts = null
        path.clear()

        val callback = finishCallback
        finishCallback = null
        previousFocus?.focus()
        previousFocus = null

        if (runCallback) callback?.invoke()
    }
}
