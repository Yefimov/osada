package org.osada.ui.briefing

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement

internal data class ScenarioBriefingView(
    val root: HTMLElement,
    val backdrop: HTMLElement,
    val title: HTMLElement,
    val subtitle: HTMLElement,
    val headerDate: HTMLElement,
    val ordersStage: HTMLElement,
    val ordersEyebrow: HTMLElement,
    val ordersContent: HTMLElement,
    val beginButton: HTMLElement,
    val dialogueStage: HTMLElement,
    val transcript: HTMLElement,
    val controls: HTMLElement,
    val choicesBox: HTMLElement,
    val hint: HTMLElement,
    val skipButton: HTMLElement,
)

/** DOM-only view builder for the campaign conversation screen and the operational briefing
 *  (ORDERS). The conversation is a SCROLLABLE LOG holding every line spoken so far (Warhammer
 *  Armageddon-style), not a one-line visual-novel box: earlier turns stay on screen and can be
 *  scrolled back through, while only the newest line typewriter-reveals. State transitions,
 *  branching and the reveal itself live in the controller; split across sibling files (same
 *  package) to stay within the project's function-count limits. */
internal object ScenarioBriefingBuilder {
    internal const val STYLESHEET_ID = "osada-briefing-stylesheet"

    fun create(
        onAdvance: () -> Unit,
        onSkip: () -> Unit,
        onBegin: () -> Unit,
    ): ScenarioBriefingView {
        ensureStylesheet()

        val root = element("div", "osada-briefing")
        root.id = "osada-scenario-briefing"
        root.setAttribute("role", "dialog")
        root.setAttribute("aria-modal", "true")
        root.setAttribute("aria-labelledby", "osada-briefing-title")
        root.tabIndex = -1

        val backdrop = child(root, "div", "osada-briefing__backdrop")
        val shade = child(root, "div", "osada-briefing__shade")
        val shell = child(shade, "section", "osada-briefing__shell")

        val header = buildHeader(shell)
        val orders = buildOrdersStage(shell, onBegin)
        val dialogue = buildDialogueStage(shell, onAdvance, onSkip)

        document.body?.appendChild(root)

        return ScenarioBriefingView(
            root = root,
            backdrop = backdrop,
            title = header.title,
            subtitle = header.subtitle,
            headerDate = header.date,
            ordersStage = orders.stage,
            ordersEyebrow = orders.eyebrow,
            ordersContent = orders.content,
            beginButton = orders.beginButton,
            dialogueStage = dialogue.stage,
            transcript = dialogue.transcript,
            controls = dialogue.controls,
            choicesBox = dialogue.choicesBox,
            hint = dialogue.hint,
            skipButton = dialogue.skipButton,
        )
    }

    private data class HeaderRefs(
        val title: HTMLElement,
        val subtitle: HTMLElement,
        val date: HTMLElement,
    )

    private fun buildHeader(shell: HTMLElement): HeaderRefs {
        val header = child(shell, "header", "osada-briefing__header")
        val titleBlock = child(header, "div", "osada-briefing__title-block")
        val title = child(titleBlock, "h1", "osada-briefing__title")
        title.id = "osada-briefing-title"
        val subtitle = child(titleBlock, "div", "osada-briefing__subtitle")
        val date = child(header, "div", "osada-briefing__date")
        return HeaderRefs(title, subtitle, date)
    }

    private data class OrdersRefs(
        val stage: HTMLElement,
        val eyebrow: HTMLElement,
        val content: HTMLElement,
        val beginButton: HTMLElement,
    )

    private fun buildOrdersStage(
        shell: HTMLElement,
        onBegin: () -> Unit,
    ): OrdersRefs {
        val stage = child(shell, "section", "osada-briefing__orders")
        val panel = child(stage, "div", "osada-briefing__orders-panel")
        val eyebrow = child(panel, "div", "osada-briefing__orders-eyebrow")
        val content = child(panel, "div", "osada-briefing__orders-content")
        val footer = child(panel, "footer", "osada-briefing__footer")
        val beginButton =
            button(
                footer,
                I18n.t("briefing.begin.label"),
                "osada-briefing__button osada-briefing__button--primary",
                onBegin,
            )
        return OrdersRefs(stage, eyebrow, content, beginButton)
    }

    private data class DialogueRefs(
        val stage: HTMLElement,
        val transcript: HTMLElement,
        val controls: HTMLElement,
        val choicesBox: HTMLElement,
        val hint: HTMLElement,
        val skipButton: HTMLElement,
    )

    /** Conversation panel: a scrollable transcript filling the panel, with the choice buttons /
     *  continue hint pinned below it and SKIP muted at the panel's top-right corner. Clicking
     *  anywhere on the panel advances/completes the reveal; choice buttons and SKIP stop
     *  propagation so they don't also trigger that advance, and so does the transcript's own
     *  scrollbar area (dragging it must not skip lines). */
    private fun buildDialogueStage(
        shell: HTMLElement,
        onAdvance: () -> Unit,
        onSkip: () -> Unit,
    ): DialogueRefs {
        val stage = child(shell, "section", "osada-dialogue-stage")
        val panel = child(stage, "div", "osada-dialogue")
        panel.addEventListener("click", { onAdvance() })

        val skipButton = element("button", "osada-dialogue__skip")
        skipButton.asDynamic().type = "button"
        skipButton.textContent = I18n.t("briefing.skip.label")
        skipButton.title = I18n.t("briefing.skip.help")
        skipButton.addEventListener("click", { e ->
            e.stopPropagation()
            onSkip()
        })
        panel.appendChild(skipButton)

        val transcript = child(panel, "div", "osada-dialogue__transcript")
        transcript.setAttribute("aria-live", "polite")
        transcript.tabIndex = 0
        // Reading back through the log must not double as "advance": a click that ends a text
        // selection or a scrollbar drag inside the transcript is a read gesture, not a next-line
        // gesture. A plain click on it (no selection) still advances, so the panel stays a
        // click-anywhere surface.
        transcript.addEventListener("click", { e ->
            val selection = js("window.getSelection()")
            val selecting = selection != null && selection.toString().isNotEmpty()
            if (selecting) e.stopPropagation()
        })

        val controls = child(panel, "div", "osada-dialogue__controls")
        val choicesBox = child(controls, "div", "osada-dialogue__choices")
        val hint = child(controls, "div", "osada-dialogue__hint")
        hint.textContent = I18n.t("briefing.continue.label")

        return DialogueRefs(stage, transcript, controls, choicesBox, hint, skipButton)
    }

    fun clearTranscript(view: ScenarioBriefingView) = clear(view.transcript)

    /** Appends one spoken turn to the conversation log and returns the element holding its text,
     *  so the controller can typewriter-reveal the newest one (earlier turns are already
     *  complete and render instantly on re-render). */
    fun appendTurn(
        view: ScenarioBriefingView,
        turn: DialogueTurn,
    ): HTMLElement {
        val participant = turn.participant
        val row = child(view.transcript, "article", "osada-dialogue__turn")
        // `side` has been parsed and carried on every line since the schema was written but was
        // never rendered. It now picks which edge the speaker's card sits against, so a two-hand
        // argument reads as two hands without any campaign JSON changing.
        if (participant.side == "right") row.classList.add("osada-dialogue__turn--right")
        if (turn.isPlayerResponse) row.classList.add("osada-dialogue__turn--player")

        addPortrait(row, participant)
        val body = child(row, "div", "osada-dialogue__body")
        val speaker = child(body, "div", "osada-dialogue__speaker")
        speaker.textContent = participant.speaker
        if (participant.role.isNotBlank()) {
            child(body, "div", "osada-dialogue__role").textContent = participant.role
        }
        return child(body, "p", "osada-dialogue__text")
    }

    /** Keeps the newest line in view as it types / as turns are appended. */
    fun scrollTranscriptToEnd(view: ScenarioBriefingView) {
        view.transcript.scrollTop = view.transcript.scrollHeight.toDouble()
    }

    /** Renders (but does not reveal) the choice buttons for the current line; hidden via CSS
     *  until the controller marks the reveal complete. Empty [choices] renders no buttons at
     *  all -- a line with no real branch gets no buttons, never a fake continue pair. */
    fun setChoices(
        view: ScenarioBriefingView,
        choices: List<BriefingChoice>,
        onChoice: (String) -> Unit,
    ) {
        clear(view.choicesBox)
        choices.forEachIndexed { index, choice ->
            val choiceButton = element("button", "osada-dialogue__choice")
            choiceButton.asDynamic().type = "button"
            choiceButton.setAttribute("aria-label", "Response ${index + 1}: ${choice.text}")
            val number = child(choiceButton, "span", "osada-dialogue__choice-number")
            number.textContent = "${index + 1}"
            child(choiceButton, "span", "osada-dialogue__choice-text").textContent = choice.text
            // What this branch means, so the player is not deciding blind. Authored `hint` when
            // present, otherwise the immediate mechanics; narrative consequences are never shown.
            val preview = BriefingChoicePreview.of(choice)
            if (preview.isNotBlank()) {
                child(choiceButton, "span", "osada-dialogue__choice-hint").textContent = preview
                choiceButton.setAttribute("title", preview)
            }
            choiceButton.addEventListener("click", { e ->
                e.stopPropagation()
                onChoice(choice.id)
            })
            view.choicesBox.appendChild(choiceButton)
        }
    }

    /** Choices and the "press to continue" hint only ever show once the current line has
     *  finished revealing -- never both, never while text is still typing. */
    fun setRevealed(
        view: ScenarioBriefingView,
        revealed: Boolean,
        hasChoices: Boolean,
    ) {
        val deciding = revealed && hasChoices
        view.choicesBox.style.display = if (deciding) "grid" else "none"
        view.hint.style.display = if (revealed && !hasChoices) "block" else "none"
        // The controls strip only draws its solid frame while it actually holds a decision; a
        // bare "CONTINUE ▸" prompt should not put a slab over the backdrop art.
        view.controls.classList.toggle("osada-dialogue__controls--deciding", deciding)
    }

    fun renderOrders(
        view: ScenarioBriefingView,
        orders: BriefingOrders,
        facts: ScenarioFacts?,
    ) {
        view.ordersEyebrow.textContent = facts?.sidesLabel?.uppercase()?.takeIf { it.isNotBlank() }
            ?: "OPERATIONAL SUMMARY"
        clear(view.ordersContent)
        addTextSection(view.ordersContent, "SITUATION", orders.situation)
        addTextSection(view.ordersContent, "MISSION", orders.mission)
        addListSection(view.ordersContent, "PRIMARY OBJECTIVES", orders.primaryObjectives, primary = true)
        addListSection(view.ordersContent, "SECONDARY OBJECTIVES", orders.secondaryObjectives, primary = false)
        addTextSection(view.ordersContent, "ENEMY INTELLIGENCE", orders.enemyIntelligence)
        addTextSection(view.ordersContent, "AVAILABLE SUPPORT", orders.availableSupport)
        addTextSection(view.ordersContent, "ADDITIONAL NOTES", orders.notes)

        // ORDERS is ALWAYS last and always present: it is the legacy scenario-start message's
        // text, single-sourced from scenario.getDescription() via ScenarioFacts — a visually
        // distinct paper block, never a copy stored in briefing/campaign data.
        val ordersText = plainText(facts?.ordersText.orEmpty())
        val ordersClass =
            "osada-briefing__order-section osada-briefing__order-section--wide osada-briefing__orders-paper"
        val section = child(view.ordersContent, "section", ordersClass)
        child(section, "h2", "osada-briefing__order-heading").textContent = I18n.t("briefing.orders.title")
        child(section, "p", "osada-briefing__order-text").textContent =
            ordersText.ifBlank { "No further orders at this time." }
    }

    fun showStage(
        view: ScenarioBriefingView,
        stage: BriefingStage,
    ) {
        val dialogue = stage == BriefingStage.DIALOGUE
        view.dialogueStage.style.display = if (dialogue) "flex" else "none"
        view.ordersStage.style.display = if (dialogue) "none" else "grid"
        // The conversation lifts the backdrop wash so the scenario art is actually visible behind
        // the speaker cards. ORDERS keeps the heavier main-menu wash: there the art is only a
        // background for an opaque document panel, and matching the menu is the point.
        view.root.classList.toggle("osada-briefing--dialogue", dialogue)
    }
}
