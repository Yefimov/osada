package org.osada.ui

import org.osada.model.Equipment
import org.osada.unitClassNames

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

    fun messageDynamic(
        title: String,
        body: String,
    ) {
        val mainBody = byId("mainbody") ?: return
        val box = addTag(mainBody, "div")
        box.className = "uiMessageBox"
        box.id = "uiMessageBoxDynamic"
        box.style.zIndex = "98"
        val titleEl = addTag(box, "div")
        titleEl.className = "uiMessageBoxTitle"
        val bodyEl = addTag(box, "div")
        bodyEl.className = "uiMessageBoxBody"
        val okButton = addTag(box, "div")
        okButton.className = "smallButton uiMessageBoxButton"
        titleEl.innerHTML = title
        bodyEl.innerHTML = body
        okButton.innerHTML = "1"
        makeVisible("uiMessageBoxDynamic")
        okButton.onclick = { _: org.w3c.dom.events.MouseEvent ->
            clearTag(box)
            delTag(box)
        }
    }

    fun showPrototypeAwardMessage(eqid: Int) {
        var body =
            "<br>Due to your brilliant tactical performance on previous battle High Command awarded " +
                "you a prototype core unit available for deployment."
        val eq = Equipment.getEquipment(eqid)
        if (eq != null) {
            body +=
                "<div class='uImageAnimation' style='margin-left: 120px;background-image: url(${eq.icon})'></div>" +
                "<b>${eq.name} ${unitClassNames[eq.uclass]}</b>"
        }
        messageDynamic("You have been awarded a prototype unit", body)
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
            statusBarExtension.innerHTML = "&#9203; Computer turn in progress&hellip;"
        } else {
            statusBarExtension.className = "statusbar-extension-animation-reverse"
            s.top = "-20px"
            statusBarExtension.innerHTML = "<span style='color: #33ccff'> Finished computer turn ! </span>"
        }
    }
}
