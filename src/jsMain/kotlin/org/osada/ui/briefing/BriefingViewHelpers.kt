package org.osada.ui.briefing

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/** Low-level DOM helpers shared across the briefing view builder. Split out of
 *  [ScenarioBriefingBuilder] purely to keep that type under the detekt TooManyFunctions
 *  limit — no behavior split intended. */
internal fun ScenarioBriefingBuilder.ensureStylesheet() {
    if (document.getElementById(STYLESHEET_ID) != null) return
    val link = document.createElement("link").asDynamic()
    link.id = STYLESHEET_ID
    link.rel = "stylesheet"
    link.href = "css/osada-briefing.css"
    document.head?.appendChild(link)
}

internal fun button(
    parent: HTMLElement,
    label: String,
    className: String,
    onClick: () -> Unit,
): HTMLElement {
    val button = element("button", className)
    button.asDynamic().type = "button"
    button.textContent = label
    button.addEventListener("click", { onClick() })
    parent.appendChild(button)
    return button
}

internal fun child(
    parent: HTMLElement,
    tag: String,
    className: String,
): HTMLElement {
    val node = element(tag, className)
    parent.appendChild(node)
    return node
}

internal fun element(
    tag: String,
    className: String,
): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    node.className = className
    return node
}

internal fun clear(element: HTMLElement) {
    while (element.firstChild != null) element.removeChild(element.firstChild!!)
}
