package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Toggle-button, checkbox, hover-image and select-option helpers ported from the legacy UI.
 * Split from [DOM] purely to keep that file within the project's function-count limits.
 */

fun toggleButton(
    element: dynamic,
    selected: Boolean,
): Boolean {
    val node: HTMLElement =
        when (element) {
            is String -> byId(element)
            is HTMLElement -> element
            else -> element as? HTMLElement
        } ?: return false
    val html = node.innerHTML
    if (selected) {
        node.setAttribute("selected", "on")
        if (node.asDynamic().hasSelectedGlyph == true) {
            node.innerHTML = html.uppercase()
        }
    } else {
        node.removeAttribute("selected")
        if (node.asDynamic().hasSelectedGlyph == true) {
            node.innerHTML = html.lowercase()
        }
    }
    return true
}

fun toggleCheckbox(element: dynamic): Boolean {
    val node: HTMLElement =
        when (element) {
            is String -> byId(element)
            is HTMLElement -> element
            else -> element as? HTMLElement
        } ?: return false
    val html = node.innerHTML
    node.innerHTML = if (html == html.uppercase()) html.lowercase() else html.uppercase()
    return true
}

fun addSelectOption(
    select: dynamic,
    text: String,
    value: dynamic,
    selected: Boolean = false,
): HTMLElement {
    val option = document.createElement("option") as HTMLElement
    option.asDynamic().value = value
    option.textContent = text
    option.asDynamic().selected = selected
    val sel: Element? =
        when (select) {
            is String -> byId(select)
            is Element -> select
            else -> select as? Element
        }
    sel?.appendChild(option)
    return option
}

fun setSelectOption(
    select: HTMLSelectElement,
    text: String,
): Boolean {
    val options = select.options.asDynamic()
    for (i in 0 until select.options.length) {
        val option = options[i] as? HTMLOptionElement
        option?.selected = false
        if (option?.text?.trim() == text.trim()) {
            option.selected = true
            return true
        }
    }
    return false
}

fun toggleRightClick(enabled: Boolean) {
    window.oncontextmenu = { enabled }
}

fun hoverin(element: HTMLImageElement?) {
    element ?: return
    val src = element.src
    val dir = src.substring(0, src.lastIndexOf("/") + 1)
    element.src = "$dir${element.id}-over.png"
}

fun hoverout(element: HTMLImageElement?) {
    element ?: return
    val src = element.src
    val dir = src.substring(0, src.lastIndexOf("/") + 1)
    element.src = "$dir${element.id}.png"
}

fun toggleButtonWithImage(
    element: dynamic,
    selected: Boolean,
): Boolean {
    val node: HTMLElement =
        when (element) {
            is String -> byId(element)
            is HTMLElement -> element
            else -> element as? HTMLElement
        } ?: return false
    val image = node.firstChild as? HTMLImageElement ?: node as? HTMLImageElement
    if (selected) hoverin(image) else hoverout(image)
    return true
}

fun toggleCheckboxWithImage(element: dynamic): Boolean {
    val node: HTMLElement? =
        when (element) {
            is String -> byId(element)
            is HTMLElement -> element
            else -> element as? HTMLElement
        }
    val image = node as? HTMLImageElement ?: return false
    val src = image.src
    val dir = src.substring(0, src.lastIndexOf("/") + 1)
    val name = src.substring(src.lastIndexOf("/") + 1)
    val checkedIndex = name.lastIndexOf("-checked")
    val baseName =
        if (checkedIndex != -1) {
            name.substring(0, checkedIndex)
        } else {
            name.substring(0, name.lastIndexOf(".")) + "-checked"
        }
    image.src = "$dir$baseName.png"
    return true
}
