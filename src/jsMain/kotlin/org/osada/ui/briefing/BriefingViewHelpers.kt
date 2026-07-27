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

/** Imported OG prose is HTML: 6,945 `<br>` across the shipped campaign data, because the legacy
 *  `#ui-message` popup sets it with `innerHTML`. The briefing sets prose with `textContent` — the
 *  right call, it must not run authored markup — so those tags used to render as visible "<br><br>"
 *  and the whole text collapsed into one paragraph. Turn the breaks into real newlines instead;
 *  `.osada-briefing__order-text` is already `white-space: pre-line`, so they paragraph correctly.
 *  Any other tag is stripped rather than shown: none exist in the data today, and a literal
 *  "<i>" on staff paper is a worse failure than a lost emphasis. */
internal fun plainText(raw: String): String =
    raw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

// Generic head-and-shoulders silhouette, drawn with `currentColor` so its shade follows the
// `.osada-dialogue__portrait-fallback` CSS rule (no downloaded asset, per spec).
private const val SILHOUETTE_SVG =
    "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">" +
        "<circle cx=\"12\" cy=\"8\" r=\"4.5\" fill=\"currentColor\"/>" +
        "<path d=\"M4 20c0-4.4 3.6-7 8-7s8 2.6 8 7\" fill=\"currentColor\"/>" +
        "</svg>"

/** Speaker portrait for one conversation turn: the authored image when present and loadable,
 *  otherwise a neutral silhouette. Top-level (not a [ScenarioBriefingBuilder] member) purely to
 *  keep that object's function count in bounds. */
internal fun addPortrait(
    row: HTMLElement,
    participant: BriefingParticipant,
) {
    val portrait = child(row, "div", "osada-dialogue__portrait")
    val image = document.createElement("img").asDynamic()
    image.className = "osada-dialogue__portrait-image"
    image.alt = ""
    portrait.appendChild(image)
    val fallback = child(portrait, "div", "osada-dialogue__portrait-fallback")
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
}

internal fun addTextSection(
    parent: HTMLElement,
    heading: String,
    text: String,
) {
    val body = plainText(text)
    if (body.isBlank()) return
    val section = child(parent, "section", "osada-briefing__order-section")
    child(section, "h2", "osada-briefing__order-heading").textContent = heading
    child(section, "p", "osada-briefing__order-text").textContent = body
}

internal fun addListSection(
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
        child(row, "span", "osada-briefing__objective-text").textContent = plainText(item)
    }
}
