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

    fun open(
        titleText: String,
        bodyHtml: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        close()
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
        cancelButton.textContent = I18n.t("common.cancel.label")
        cancelButton.setAttribute("tabindex", "0")

        val confirmButton = addTag(buttonRow, "button")
        confirmButton.className = "osadaConfirmBoxConfirm"
        confirmButton.textContent = confirmLabel
        confirmButton.setAttribute("tabindex", "0")

        cancelButton.onclick = { _ -> close() }
        confirmButton.onclick = { _ ->
            close()
            onConfirm()
        }

        val handler =
            EventListener { e: Event ->
                if ((e.asDynamic().key as? String) == "Escape") {
                    e.preventDefault()
                    close()
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

    fun close() {
        keydownHandler?.let { document.removeEventListener("keydown", it) }
        keydownHandler = null
        byId(DIALOG_ID)?.let {
            clearTag(it)
            delTag(it)
        }
        previouslyFocused?.focus()
        previouslyFocused = null
    }
}
