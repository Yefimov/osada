package org.osada.ui

import kotlinx.browser.document
import org.osada.i18n.I18n
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener

/**
 * Generic anchored confirmation card shared by every destructive/replacing action in the P0
 * workstream: sell/disband ([SellDisbandConfirmDialog]) and replacing an existing campaign run
 * ([StartMenuCampaignScreen]). One instance on screen at a time, per
 * `docs/design/action-affordances-and-objectives.md` section 5: default focus on Cancel, Escape
 * cancels, no Delete-key shortcut, Enter only confirms through native button activation while the
 * destructive button itself has focus.
 */
internal object ConfirmCard {
    private const val DIALOG_ID = "osadaConfirmCard"
    private var keydownHandler: EventListener? = null
    private var previouslyFocused: HTMLElement? = null

    /** The open card's dismissing answer, run by its Cancel button and by every Escape path. */
    private var cancelAction: (() -> Unit)? = null

    fun open(
        titleText: String,
        bodyHtml: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) = openChoice(titleText, bodyHtml, confirmLabel, I18n.t("common.cancel.label"), {}, onConfirm)

    /** [open] with the dismissing button renamed and answered: for a question whose "no" is itself
     *  an action (a defeat that is accepted rather than retried), not merely a cancel. Escape runs
     *  [onCancel] too. */
    fun openChoice(
        titleText: String,
        bodyHtml: String,
        confirmLabel: String,
        cancelLabel: String,
        onCancel: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        close()
        MessageDialogs.suspendDynamicMessages("decision-confirm")
        org.osada.ui.music.MusicLibraryPresenter
            .yieldToDecision()
        val mainBody = byId("mainbody") ?: return
        previouslyFocused = document.activeElement as? HTMLElement

        val box = addTag(mainBody, "div")
        box.id = DIALOG_ID
        box.className = "uiMessageBox osadaConfirmBox"
        box.setAttribute("role", "alertdialog")
        box.setAttribute("aria-modal", "true")

        val titleEl = addTag(box, "div")
        titleEl.className = "uiMessageBoxTitle"
        titleEl.textContent = titleText

        val bodyEl = addTag(box, "div")
        bodyEl.className = "uiMessageBoxBody"
        bodyEl.innerHTML = bodyHtml

        val buttonRow = addTag(box, "div")
        buttonRow.className = "osadaConfirmBoxButtons"

        val cancelButton = addTag(buttonRow, "button")
        cancelButton.className = "osadaConfirmBoxCancel"
        cancelButton.textContent = cancelLabel
        cancelButton.setAttribute("tabindex", "0")

        val confirmButton = addTag(buttonRow, "button")
        confirmButton.className = "osadaConfirmBoxConfirm"
        confirmButton.textContent = confirmLabel
        confirmButton.setAttribute("tabindex", "0")

        cancelAction = onCancel
        cancelButton.onclick = { _ -> cancel() }
        confirmButton.onclick = { _ ->
            close()
            onConfirm()
        }

        val handler =
            EventListener { e: Event ->
                if ((e.asDynamic().key as? String) == "Escape") {
                    e.preventDefault()
                    cancel()
                }
            }
        keydownHandler = handler
        document.addEventListener("keydown", handler)

        makeVisible(DIALOG_ID)
        cancelButton.focus()
    }

    /** Whether a confirmation is on screen — read by the keyboard router, which must suspend
     *  gameplay commands while the player owes this dialog an answer. */
    fun isOpen(): Boolean = byId(DIALOG_ID) != null

    /**
     * Dismisses the card WITH its answer: closes it, then runs the cancel action once. Escape must
     * come through here. The gameplay router (`CommandRouter`) is registered first and used to call
     * [close], which removed this card's own Escape listener mid-dispatch, so the browser skipped
     * it: the defeat question's "accept" never ran and `awaitingDefeatChoice` froze the turn loop
     * (found in review, 2026-10-14). A no-op when no card is open.
     */
    fun cancel() {
        if (!isOpen()) return
        val action = cancelAction
        close()
        action?.invoke()
    }

    /** Removes the card WITHOUT answering it. For a card whose battle is being torn down
     *  (`Game.cleanup`), where neither answer may run against whatever is loaded next. */
    fun close() {
        cancelAction = null
        keydownHandler?.let { document.removeEventListener("keydown", it) }
        keydownHandler = null
        byId(DIALOG_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        previouslyFocused?.focus()
        previouslyFocused = null
        MessageDialogs.resumeDynamicMessages("decision-confirm")
    }
}
