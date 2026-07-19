package org.osada.ui.briefing

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

internal data class ScenarioBriefingView(
    val root: HTMLElement,
    val backdrop: HTMLElement,
    val title: HTMLElement,
    val progress: HTMLElement,
    val actLabel: HTMLElement,
    val locationLabel: HTMLElement,
    val dialogueStage: HTMLElement,
    val transcript: HTMLElement,
    val ordersStage: HTMLElement,
    val ordersContent: HTMLElement,
    val dialogueControls: HTMLElement,
    val orderControls: HTMLElement,
    val backButton: HTMLElement,
    val nextButton: HTMLElement,
    val skipButton: HTMLElement,
    val reviewButton: HTMLElement,
    val beginButton: HTMLElement,
)

/** DOM-only view builder. State transitions and branching live in the controller. */
internal object ScenarioBriefingBuilder {
    internal const val STYLESHEET_ID = "osada-briefing-stylesheet"

    fun create(
        onBack: () -> Unit,
        onNext: () -> Unit,
        onSkip: () -> Unit,
        onReview: () -> Unit,
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

        val header = child(shell, "header", "osada-briefing__header")
        val title = child(header, "h1", "osada-briefing__title")
        title.id = "osada-briefing-title"
        val progress = child(header, "div", "osada-briefing__progress")

        val dialogueStage = child(shell, "section", "osada-conversation")
        dialogueStage.setAttribute("aria-live", "polite")
        val transcript = child(dialogueStage, "div", "osada-conversation__transcript")

        val ordersStage = child(shell, "section", "osada-briefing__orders")
        val ordersPanel = child(ordersStage, "div", "osada-briefing__orders-panel")
        val ordersEyebrow = child(ordersPanel, "div", "osada-briefing__orders-eyebrow")
        ordersEyebrow.textContent = "FIELD COMMAND • OPERATIONAL SUMMARY"
        val ordersContent = child(ordersPanel, "div", "osada-briefing__orders-content")

        val footer = buildFooter(shell, onBack, onNext, onSkip, onReview, onBegin)

        val status = child(shell, "div", "osada-conversation__status")
        val actLabel = child(status, "div", "osada-conversation__act")
        val locationLabel = child(status, "div", "osada-conversation__location")

        document.body?.appendChild(root)

        return ScenarioBriefingView(
            root = root,
            backdrop = backdrop,
            title = title,
            progress = progress,
            actLabel = actLabel,
            locationLabel = locationLabel,
            dialogueStage = dialogueStage,
            transcript = transcript,
            ordersStage = ordersStage,
            ordersContent = ordersContent,
            dialogueControls = footer.dialogueControls,
            orderControls = footer.orderControls,
            backButton = footer.backButton,
            nextButton = footer.nextButton,
            skipButton = footer.skipButton,
            reviewButton = footer.reviewButton,
            beginButton = footer.beginButton,
        )
    }

    private data class BriefingFooter(
        val dialogueControls: HTMLElement,
        val orderControls: HTMLElement,
        val backButton: HTMLElement,
        val nextButton: HTMLElement,
        val skipButton: HTMLElement,
        val reviewButton: HTMLElement,
        val beginButton: HTMLElement,
    )

    /** Footer controls (dialogue back/next/skip + orders review/continue) split out of [create]
     *  purely to keep that function under the line-count limit — no behavior split intended. */
    private fun buildFooter(
        shell: HTMLElement,
        onBack: () -> Unit,
        onNext: () -> Unit,
        onSkip: () -> Unit,
        onReview: () -> Unit,
        onBegin: () -> Unit,
    ): BriefingFooter {
        val footer = child(shell, "footer", "osada-briefing__footer")
        val dialogueControls = child(footer, "div", "osada-briefing__controls osada-briefing__controls--dialogue")
        val skipButton =
            button(dialogueControls, "SKIP TO BRIEFING", "osada-briefing__button osada-briefing__button--quiet", onSkip)
        val backButton = button(dialogueControls, "BACK", "osada-briefing__button", onBack)
        val nextButton =
            button(dialogueControls, "NEXT", "osada-briefing__button osada-briefing__button--primary", onNext)

        val orderControls = child(footer, "div", "osada-briefing__controls osada-briefing__controls--orders")
        val reviewButton =
            button(
                orderControls,
                "REVIEW CONVERSATION",
                "osada-briefing__button osada-briefing__button--quiet",
                onReview,
            )
        val beginButton =
            button(orderControls, "CONTINUE", "osada-briefing__button osada-briefing__button--primary", onBegin)

        return BriefingFooter(
            dialogueControls = dialogueControls,
            orderControls = orderControls,
            backButton = backButton,
            nextButton = nextButton,
            skipButton = skipButton,
            reviewButton = reviewButton,
            beginButton = beginButton,
        )
    }

    fun renderDialogue(
        view: ScenarioBriefingView,
        turns: List<DialogueTurn>,
        player: BriefingParticipant,
        choices: List<BriefingChoice>,
        canGoBack: Boolean,
        hasNext: Boolean,
        onChoice: (String) -> Unit,
    ) {
        view.progress.textContent = if (choices.isEmpty()) "CONVERSATION" else "YOUR DECISION"
        clear(view.transcript)
        turns.forEach { turn -> addTurn(view.transcript, turn) }
        if (choices.isNotEmpty()) addChoiceTurn(view.transcript, player, choices, onChoice)

        view.backButton.asDynamic().disabled = !canGoBack
        view.nextButton.style.display = if (choices.isEmpty()) "inline-flex" else "none"
        view.nextButton.textContent = if (hasNext) "NEXT" else "VIEW BRIEFING"
        js("setTimeout")({ view.transcript.scrollTop = view.transcript.scrollHeight.toDouble() }, 0)
    }

    fun renderOrders(
        view: ScenarioBriefingView,
        orders: BriefingOrders,
        facts: ScenarioFacts?,
    ) {
        view.progress.textContent = "OPERATIONAL BRIEFING"
        clear(view.ordersContent)
        if (facts != null) {
            val meta = child(view.ordersContent, "div", "osada-briefing__meta")
            child(meta, "span", "osada-briefing__meta-date").textContent = facts.dateLabel
            child(meta, "span", "osada-briefing__meta-sides").textContent = facts.sidesLabel
        }
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
        val ordersText = facts?.ordersText.orEmpty().trim()
        val ordersClass =
            "osada-briefing__order-section osada-briefing__order-section--wide osada-briefing__orders-paper"
        val section = child(view.ordersContent, "section", ordersClass)
        child(section, "h2", "osada-briefing__order-heading").textContent = "ORDERS"
        child(section, "p", "osada-briefing__order-text").textContent =
            ordersText.ifBlank { "No further orders at this time." }
    }

    fun showStage(
        view: ScenarioBriefingView,
        stage: BriefingStage,
        hasDialogue: Boolean,
    ) {
        val dialogue = stage == BriefingStage.DIALOGUE
        view.dialogueStage.style.display = if (dialogue) "block" else "none"
        view.ordersStage.style.display = if (dialogue) "none" else "grid"
        view.dialogueControls.style.display = if (dialogue) "flex" else "none"
        view.orderControls.style.display = if (dialogue) "none" else "flex"
        view.reviewButton.style.display = if (hasDialogue) "inline-flex" else "none"
    }

    fun setBeginLabel(
        view: ScenarioBriefingView,
        label: String,
    ) {
        view.beginButton.textContent = label
    }

    private fun addTurn(
        parent: HTMLElement,
        turn: DialogueTurn,
    ) {
        val row =
            child(parent, "article", "osada-conversation__turn osada-conversation__turn--${turn.participant.side}")
        if (turn.isPlayerResponse) row.classList.add("osada-conversation__turn--player")
        addPortrait(row, turn.participant)
        val bubble = child(row, "div", "osada-conversation__bubble")
        if (turn.participant.role.isNotBlank()) {
            child(bubble, "div", "osada-conversation__role").textContent = turn.participant.role
        }
        child(bubble, "p", "osada-conversation__text").textContent = turn.text
    }

    private fun addChoiceTurn(
        parent: HTMLElement,
        player: BriefingParticipant,
        choices: List<BriefingChoice>,
        onChoice: (String) -> Unit,
    ) {
        val row =
            child(
                parent,
                "article",
                "osada-conversation__turn osada-conversation__turn--${player.side} osada-conversation__turn--choice",
            )
        addPortrait(row, player)
        val bubble = child(row, "div", "osada-conversation__bubble osada-conversation__bubble--choices")
        child(bubble, "div", "osada-conversation__role").textContent = "SELECT RESPONSE"
        val list = child(bubble, "div", "osada-conversation__choices")
        choices.forEachIndexed { index, choice ->
            val button = element("button", "osada-conversation__choice")
            button.asDynamic().type = "button"
            button.setAttribute("aria-label", "Response ${index + 1}: ${choice.text}")
            val number = child(button, "span", "osada-conversation__choice-number")
            number.textContent = "${index + 1}."
            child(button, "span", "osada-conversation__choice-text").textContent = choice.text
            button.addEventListener("click", { onChoice(choice.id) })
            list.appendChild(button)
        }
    }

    // Generic head-and-shoulders silhouette, drawn with `currentColor` so its shade follows the
    // `.osada-conversation__portrait-fallback` CSS rule (no downloaded asset, per spec).
    private const val SILHOUETTE_SVG =
        "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<circle cx=\"12\" cy=\"8\" r=\"4.5\" fill=\"currentColor\"/>" +
            "<path d=\"M4 20c0-4.4 3.6-7 8-7s8 2.6 8 7\" fill=\"currentColor\"/>" +
            "</svg>"

    private fun addPortrait(
        parent: HTMLElement,
        participant: BriefingParticipant,
    ) {
        val frame = child(parent, "div", "osada-conversation__portrait")
        val image = document.createElement("img").asDynamic()
        image.className = "osada-conversation__portrait-image"
        image.alt = ""
        frame.appendChild(image)
        val fallback = child(frame, "div", "osada-conversation__portrait-fallback")
        fallback.setAttribute("title", participant.speaker)
        fallback.innerHTML = SILHOUETTE_SVG

        if (participant.portrait.isNullOrBlank()) {
            image.style.display = "none"
            fallback.style.display = "grid"
        } else {
            image.style.display = "block"
            fallback.style.display = "none"
            image.onerror = { _: dynamic ->
                image.style.display = "none"
                fallback.style.display = "grid"
                null
            }
            image.src = participant.portrait
        }

        val nameplate = child(frame, "div", "osada-conversation__nameplate")
        nameplate.textContent = participant.speaker
    }

    private fun addTextSection(
        parent: HTMLElement,
        heading: String,
        text: String,
    ) {
        if (text.isBlank()) return
        val section = child(parent, "section", "osada-briefing__order-section")
        child(section, "h2", "osada-briefing__order-heading").textContent = heading
        child(section, "p", "osada-briefing__order-text").textContent = text
    }

    private fun addListSection(
        parent: HTMLElement,
        heading: String,
        items: List<String>,
        primary: Boolean,
    ) {
        if (items.isEmpty()) return
        val section = child(parent, "section", "osada-briefing__order-section osada-briefing__order-section--wide")
        child(section, "h2", "osada-briefing__order-heading").textContent = heading
        val list = child(section, "ol", "osada-briefing__objectives")
        items.forEachIndexed { index, item ->
            val row = child(list, "li", "osada-briefing__objective")
            val marker = child(row, "span", "osada-briefing__objective-marker")
            marker.textContent = if (primary) "${index + 1}" else "•"
            child(row, "span", "osada-briefing__objective-text").textContent = item
        }
    }
}
