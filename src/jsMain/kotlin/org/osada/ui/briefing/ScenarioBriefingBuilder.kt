package org.osada.ui.briefing

import kotlinx.browser.document
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
    val portraitImage: HTMLElement,
    val portraitFallback: HTMLElement,
    val speakerName: HTMLElement,
    val lineText: HTMLElement,
    val choicesBox: HTMLElement,
    val hint: HTMLElement,
    val skipButton: HTMLElement,
)

/** DOM-only view builder for the lower-third campaign dialogue panel and the full-panel
 *  operational briefing (ORDERS). State transitions, branching and the typewriter reveal live
 *  in the controller; split across sibling files (same package) to stay within the project's
 *  function-count limits. */
internal object ScenarioBriefingBuilder {
    internal const val STYLESHEET_ID = "osada-briefing-stylesheet"

    // Generic head-and-shoulders silhouette, drawn with `currentColor` so its shade follows the
    // `.osada-dialogue__portrait-fallback` CSS rule (no downloaded asset, per spec).
    private const val SILHOUETTE_SVG =
        "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
            "<circle cx=\"12\" cy=\"8\" r=\"4.5\" fill=\"currentColor\"/>" +
            "<path d=\"M4 20c0-4.4 3.6-7 8-7s8 2.6 8 7\" fill=\"currentColor\"/>" +
            "</svg>"

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
            portraitImage = dialogue.portraitImage,
            portraitFallback = dialogue.portraitFallback,
            speakerName = dialogue.speakerName,
            lineText = dialogue.lineText,
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
            button(footer, "BEGIN OPERATION", "osada-briefing__button osada-briefing__button--primary", onBegin)
        return OrdersRefs(stage, eyebrow, content, beginButton)
    }

    private data class DialogueRefs(
        val stage: HTMLElement,
        val portraitImage: HTMLElement,
        val portraitFallback: HTMLElement,
        val speakerName: HTMLElement,
        val lineText: HTMLElement,
        val choicesBox: HTMLElement,
        val hint: HTMLElement,
        val skipButton: HTMLElement,
    )

    /** Lower-third dialogue panel: portrait left, speaker/line/choices right, SKIP muted at the
     *  panel's top-right corner. Clicking anywhere on the panel advances/completes the reveal;
     *  choice buttons and SKIP stop propagation so they don't also trigger that advance. */
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
        skipButton.textContent = "SKIP TO BRIEFING"
        skipButton.title = "Skip the conversation and go straight to the operational briefing"
        skipButton.addEventListener("click", { e ->
            e.stopPropagation()
            onSkip()
        })
        panel.appendChild(skipButton)

        val portrait = child(panel, "div", "osada-dialogue__portrait")
        val portraitImage = document.createElement("img") as HTMLElement
        portraitImage.className = "osada-dialogue__portrait-image"
        portraitImage.asDynamic().alt = ""
        portrait.appendChild(portraitImage)
        val portraitFallback = child(portrait, "div", "osada-dialogue__portrait-fallback")
        portraitFallback.innerHTML = SILHOUETTE_SVG

        val content = child(panel, "div", "osada-dialogue__content")
        val speakerName = child(content, "div", "osada-dialogue__speaker")
        val lineText = child(content, "p", "osada-dialogue__line")
        val choicesBox = child(content, "div", "osada-dialogue__choices")
        val hint = child(content, "div", "osada-dialogue__hint")
        hint.textContent = "CONTINUE ▸"

        return DialogueRefs(stage, portraitImage, portraitFallback, speakerName, lineText, choicesBox, hint, skipButton)
    }

    fun setSpeaker(
        view: ScenarioBriefingView,
        participant: BriefingParticipant,
    ) {
        view.speakerName.textContent = participant.speaker
        view.portraitFallback.setAttribute("title", participant.speaker)
        if (participant.portrait.isNullOrBlank()) {
            showPortraitFallback(view)
        } else {
            val image = view.portraitImage.asDynamic()
            image.style.display = "block"
            view.portraitFallback.style.display = "none"
            image.onerror = { _: dynamic ->
                showPortraitFallback(view)
                null
            }
            image.src = participant.portrait
        }
    }

    private fun showPortraitFallback(view: ScenarioBriefingView) {
        view.portraitImage.style.display = "none"
        view.portraitFallback.style.display = "grid"
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
        view.choicesBox.style.display = if (revealed && hasChoices) "grid" else "none"
        view.hint.style.display = if (revealed && !hasChoices) "block" else "none"
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
    ) {
        val dialogue = stage == BriefingStage.DIALOGUE
        view.dialogueStage.style.display = if (dialogue) "flex" else "none"
        view.ordersStage.style.display = if (dialogue) "none" else "grid"
    }
}
