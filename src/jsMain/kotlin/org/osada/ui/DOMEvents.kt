package org.osada.ui

import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/*
 * Touch/mouse gesture and user-agent/environment helpers ported from the legacy UI. Split from
 * `DOM.kt` purely to keep that file within the project's function-count limits.
 */

/**
 * Makes a non-`<button>` element behave like one: `role`/`tabindex` for assistive technology AND
 * the Enter/Space handler that role promises (DEFERRED.md §4.14).
 *
 * **Use this instead of setting the two attributes by hand.** Setting them without a key handler is
 * worse than setting neither — the element takes focus and announces itself as a button, then does
 * nothing when activated. That is exactly what happened in all three `--z-msg` dialogs, because the
 * ARIA attributes and the handler were separable; here they are not.
 *
 * [ariaLabel] is only needed when the element's own text is not the label (a glyph-only control).
 */
fun HTMLElement.asButton(
    ariaLabel: String? = null,
    onActivate: () -> Unit,
) {
    setAttribute("role", "button")
    setAttribute("tabindex", "0")
    ariaLabel?.let { setAttribute("aria-label", it) }
    onclick = { _: MouseEvent -> onActivate() }
    onkeydown = { event ->
        val key = event.asDynamic().key as? String
        if (key == "Enter" || key == " ") {
            event.preventDefault()
            onActivate()
        }
    }
}

fun isChromeApp(): Boolean = js("window.chrome && chrome.app && chrome.app.runtime") as? Boolean ?: false

fun bounceText(
    x: Double,
    y: Double,
    text: String,
    green: Boolean = false,
) {
    val container = addTag("game", "div")
    container.style.position = "absolute"
    container.style.top = "${y}px"
    container.style.left = "${x}px"
    container.style.zIndex = "200"
    if (uiSettings.uiScale != 1.0) {
        val transform = "scale(${uiSettings.uiScale},${uiSettings.uiScale})"
        container.style.transform = transform
        container.style.transformOrigin = "50% 50%"
    }
    val inner = addTag(container, "div")
    inner.className = if (green) "textBounceGreen" else "textBounceRed"
    inner.innerHTML = text
    inner.addEventListener("animationend", { delTag(container) })
    inner.addEventListener("webkitAnimationEnd", { delTag(container) })
}

fun jsObject(init: dynamic.() -> Unit): dynamic {
    val obj = js("({})")
    init(obj)
    return obj
}
