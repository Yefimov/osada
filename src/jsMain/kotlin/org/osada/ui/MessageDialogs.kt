package org.osada.ui

import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.unitClassNames
import org.w3c.dom.HTMLElement

/**
 * Modal and transient message popups: the main OK dialog, dynamic message boxes, the
 * prototype-award announcement and the AI-turn status banner. Extracted from the former
 * `UIBuilder` god-object.
 */
internal object MessageDialogs {
    fun message(
        title: String,
        body: String,
        narrative: Boolean = false,
        callback: (() -> Unit)? = null,
    ) {
        // A message with nothing to say must never paint an empty popup over the UI. When a
        // callback is pending we still have to run the flow, just without the box.
        if (title.isBlank() && body.isBlank()) {
            makeHidden("ui-message")
            if (callback != null) {
                callback()
            } else {
                js("if (typeof game !== 'undefined') game.uiMessageClicked = true")
            }
            return
        }
        // Narrative content (briefings, campaign outcome texts) reads on staff paper per the
        // design language; system messages (defeat, errors, awards) keep the metal body. The
        // box is shared, so the class must be set/cleared on EVERY call, not just narrative ones.
        byId("ui-message")?.classList?.toggle("uiMessageBox--narrative", narrative)
        val titleEl = byId("title")
        val messageEl = byId("message")
        val uiOkBut = byId("uiokbut")
        uiOkBut?.apply {
            this.title = I18n.t("message.continue.help")
            setAttribute("data-label", I18n.t("common.continue.label"))
        }
        titleEl?.innerHTML = title
        messageEl?.innerHTML = body
        makeVisible("ui-message")
        messageEl?.scrollTop = 0.0
        js("if (typeof game !== 'undefined') game.uiMessageClicked = false")
        uiOkBut?.onclick =
            if (callback != null) {
                { _: org.w3c.dom.events.MouseEvent ->
                    makeHidden("ui-message")
                    callback()
                }
            } else {
                { _: org.w3c.dom.events.MouseEvent ->
                    makeHidden("ui-message")
                    js("if (typeof game !== 'undefined') game.uiMessageClicked = true")
                }
            }
    }

    /**
     * Dialogs waiting their turn, oldest first. One [messageDynamic] box is on screen at a time and
     * dismissing it opens the next.
     *
     * **This used to be unqueued, and it lost messages for entire scenarios.** Every box was created
     * with the same id `uiMessageBoxDynamic`, and `makeVisible(id)` resolves through `byId`, which
     * returns the FIRST match — so when a single combat produced two hero events (an emergence and,
     * on the next blow, that commander's casualty) the second box was appended already hidden and
     * nothing ever showed it. It then sat in `#mainbody` indefinitely, still matching the id, until
     * some later `makeVisible("uiMessageBoxDynamic")` happened to pick it up. That is precisely the
     * two symptoms reported on 2026-07-31: a commander lost on turn 1 announced on turn **4**, and a
     * hero message about a Frigate from the PREVIOUS scenario appearing on Willhelmshafen turn 1 —
     * the stale box outlived the scenario transition, because nothing tears these down.
     */
    private data class DynamicMessage(
        val title: String,
        val body: String,
        /** Optional presentation hook for messages with a distinct responsive layout. */
        val dialogClass: String,
        /** Runs once this box is actually in the DOM and visible. Anything that decorates the box's
         *  own markup — the hero portrait painted into `#heroEmergencePortrait` — has to happen
         *  here rather than at the call site, which may now be several dismissals early. */
        val onShown: (() -> Unit)?,
    )

    private val pendingDynamicMessages = mutableListOf<DynamicMessage>()
    private var dynamicMessageShowing = false

    /** The box currently on screen, so [suspendDynamicMessages] can put it back in the queue
     *  rather than throw the player's unread announcement away. */
    private var onScreenDynamicMessage: DynamicMessage? = null
    private var dynamicMessagesSuspended = false

    fun messageDynamic(
        title: String,
        body: String,
        dialogClass: String = "",
        onShown: (() -> Unit)? = null,
    ) {
        pendingDynamicMessages += DynamicMessage(title, body, dialogClass, onShown)
        showNextDynamicMessage()
    }

    /** Drops every queued and on-screen dynamic message. Called on scenario teardown: an
     *  announcement about the battle just finished must not surface in the next one. */
    fun clearDynamicMessages() {
        pendingDynamicMessages.clear()
        dynamicMessageShowing = false
        onScreenDynamicMessage = null
        dynamicMessagesSuspended = false
        removeDynamicBox()
    }

    /**
     * Holds the queue back while the pause/main menu owns the screen.
     *
     * These boxes sit on `--z-msg` (1000) and `#startmenu` on 300, so a hero-emergence
     * announcement (or any other queued popup) that was still up when the player opened the menu
     * floated OVER the menu and stayed there — the symptom reported on 2026-09-05. The visible box
     * goes back to the HEAD of the queue rather than being dismissed: the announcement is not lost,
     * it is shown again by [resumeDynamicMessages] when the player returns to the battle.
     *
     * `ui-message` needs no equivalent — its opener dismisses it through its own OK path, which is
     * what runs the callback it may be holding. These boxes carry no such flow.
     */
    fun suspendDynamicMessages() {
        if (dynamicMessagesSuspended) return
        dynamicMessagesSuspended = true
        onScreenDynamicMessage?.let { pendingDynamicMessages.add(0, it) }
        onScreenDynamicMessage = null
        dynamicMessageShowing = false
        removeDynamicBox()
    }

    /** Whether a queued box is on screen right now — the Escape router's z-order test. */
    fun isDynamicMessageOpen(): Boolean = dynamicMessageShowing && byId("uiMessageBoxDynamic") != null

    /** Dismisses the on-screen box through its own OK button, so the queue advances exactly as
     *  it does on a click. */
    fun dismissDynamicMessage() {
        byId("uiMessageBoxDynamic")?.let { box ->
            (box.querySelector(".uiMessageBoxButton") as? HTMLElement)?.click()
        }
    }

    /** Reopens whatever [suspendDynamicMessages] put back. A no-op if nothing was suspended. */
    fun resumeDynamicMessages() {
        if (!dynamicMessagesSuspended) return
        dynamicMessagesSuspended = false
        showNextDynamicMessage()
    }

    private fun removeDynamicBox() {
        byId("uiMessageBoxDynamic")?.let { box ->
            clearTag(box)
            delTag(box)
        }
    }

    private fun showNextDynamicMessage() {
        if (dynamicMessagesSuspended || dynamicMessageShowing || pendingDynamicMessages.isEmpty()) return
        val mainBody = byId("mainbody") ?: return
        val message = pendingDynamicMessages.removeAt(0)
        dynamicMessageShowing = true
        onScreenDynamicMessage = message
        val box = addTag(mainBody, "div")
        box.className = listOf("uiMessageBox", message.dialogClass).filter { it.isNotBlank() }.joinToString(" ")
        box.id = "uiMessageBoxDynamic"
        box.style.zIndex = "98"
        val titleEl = addTag(box, "div")
        titleEl.className = "uiMessageBoxTitle"
        val bodyEl = addTag(box, "div")
        bodyEl.className = "uiMessageBoxBody"
        val okButton = addTag(box, "div")
        okButton.className = "smallButton uiMessageBoxButton"
        okButton.title = I18n.t("message.continue.help")
        okButton.setAttribute("data-label", I18n.t("common.continue.label"))
        titleEl.innerHTML = message.title
        bodyEl.innerHTML = message.body
        okButton.innerHTML = "1"
        makeVisible("uiMessageBoxDynamic")
        message.onShown?.invoke()
        okButton.onclick = { _: org.w3c.dom.events.MouseEvent ->
            clearTag(box)
            delTag(box)
            dynamicMessageShowing = false
            onScreenDynamicMessage = null
            showNextDynamicMessage()
        }
    }

    fun showPrototypeAwardMessage(eqid: Int) {
        var body = "<br>${I18n.t("message.prototype.body")}"
        val eq = Equipment.getEquipment(eqid)
        if (eq != null) {
            body +=
                "<div class='uImageAnimation' style='margin-left: 120px;background-image: " +
                "url(${UnitIconResolver.forCurrentScenario(eqid, eq.icon)})'></div>" +
                "<b>${eq.name} ${unitClassNames[eq.uclass]}</b>"
        }
        messageDynamic(I18n.t("message.prototype.title"), body)
    }

    fun showAIStatus(active: Boolean) {
        val statusBarExtension = byId("statusbar-extension") ?: return
        makeVisible("statusbar-extension")
        val s = statusBarExtension.asDynamic().style
        if (active) {
            // Prominent banner so the player clearly sees the AI is thinking (was easy to miss).
            statusBarExtension.className = "statusbar-extension-animation"
            s.width = "420px"
            s.marginLeft = "-210px"
            s.height = "40px"
            s.lineHeight = "40px"
            s.fontSize = "22px"
            s.fontWeight = "bold"
            s.color = "#fff"
            s.background = "rgba(170,20,20,0.9)"
            s.borderRadius = "0 0 8px 8px"
            statusBarExtension.innerHTML = "&#9203; ${I18n.t("hud.ai_turn.in_progress")}"
        } else {
            statusBarExtension.className = "statusbar-extension-animation-reverse"
            s.top = "-20px"
            statusBarExtension.innerHTML =
                "<span style='color: #33ccff'>${I18n.t("hud.ai_turn.finished")}</span>"
        }
    }
}
