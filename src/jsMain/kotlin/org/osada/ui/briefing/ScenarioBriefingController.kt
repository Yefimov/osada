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
    private const val DEFAULT_BACKGROUND =
        "resources/ui/dialogs/startmenu/images/startmenu-1.jpg"

    internal data class DialogueStep(
        val lineId: String,
        var selectedChoice: BriefingChoice? = null,
    )

    internal var briefing: ScenarioBriefing? = null
    internal var facts: ScenarioFacts? = null
    private var lastBriefing: ScenarioBriefing? = null
    private var lastFacts: ScenarioFacts? = null
    internal var view: ScenarioBriefingView? = null
    internal var stage: BriefingStage = BriefingStage.ORDERS
    internal val path = mutableListOf<DialogueStep>()
    private var finishCallback: (() -> Unit)? = null
    private var previousFocus: HTMLElement? = null

    fun show(
        scenarioFacts: ScenarioFacts,
        rawData: dynamic,
        onFinished: () -> Unit,
    ) {
        val parsed = BriefingParser.parse(scenarioFacts.title, rawData)
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
        lastBriefing = BriefingParser.parse(scenarioFacts.title, rawData)
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

    /** Esc on the ORDERS stage acts like the primary button: begin the operation. */
    internal fun finishBriefing() = close(runCallback = true)

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
                onSkip = { showOrders() },
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
        }
    }

    private fun renderDialogueStage(currentView: ScenarioBriefingView) {
        val current = currentLine()
        if (current == null) {
            showOrders()
            return
        }
        ScenarioBriefingBuilder.setSpeaker(currentView, current.participant())
        val pendingChoice = path.lastOrNull()?.selectedChoice
        val choices = if (pendingChoice == null) current.choices else emptyList()
        ScenarioBriefingBuilder.setChoices(currentView, choices) { choose(it) }
        ScenarioBriefingBuilder.setRevealed(currentView, revealed = false, hasChoices = choices.isNotEmpty())
        BriefingTypewriter.start(currentView.lineText, current.text) {
            ScenarioBriefingBuilder.setRevealed(currentView, revealed = true, hasChoices = choices.isNotEmpty())
            if (choices.isNotEmpty()) {
                currentView.choicesBox
                    .querySelector("button")
                    ?.asDynamic()
                    ?.focus()
            }
        }
    }

    private fun close(runCallback: Boolean) {
        BriefingTypewriter.cancel()
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
