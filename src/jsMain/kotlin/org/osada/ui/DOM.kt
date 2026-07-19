package org.osada.ui

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Core DOM element helpers ported from the legacy UI: lookup, creation, insertion and removal.
 * Position/visibility helpers live in [DOMLayout], toggle/select helpers in [DOMToggle], and
 * touch/mouse/misc helpers in [DOMEvents] — split purely to keep this file within the project's
 * function-count limits.
 */

@JsName("$")
fun byId(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

@JsName("$$")
fun query(selector: String): Element? = document.querySelector(selector)

fun Element?.query(selector: String): Element? = this?.querySelector(selector)

fun addTag(
    parent: dynamic,
    tag: String,
): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    val parentNode: Element? =
        when (parent) {
            is String -> byId(parent)
            is Element -> parent
            else -> parent as? Element
        }
    parentNode?.appendChild(element)
    return element
}

fun insertTag(
    parent: dynamic,
    tag: String,
    before: dynamic,
): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    val parentNode: Element? =
        when (parent) {
            is String -> byId(parent)
            is Element -> parent
            else -> parent as? Element
        }
    val beforeNode: Element? =
        when (before) {
            is String -> byId(before)
            is Element -> before
            else -> before as? Element
        }
    if (parentNode != null && beforeNode != null) {
        parentNode.insertBefore(element, beforeNode)
    }
    return element
}

fun delTag(element: dynamic) {
    val node: Element? =
        when (element) {
            is String -> byId(element)
            is Element -> element
            else -> element as? Element
        }
    node?.parentNode?.removeChild(node)
}

fun clearTag(element: dynamic) {
    val node: Element? =
        when (element) {
            is String -> byId(element)
            is Element -> element
            else -> element as? Element
        }
    node?.let {
        while (it.hasChildNodes()) {
            it.removeChild(it.lastChild!!)
        }
    }
}

fun clearStyle(
    element: dynamic,
    property: String? = null,
) {
    val node: HTMLElement? =
        when (element) {
            is String -> byId(element)
            is HTMLElement -> element
            else -> element as? HTMLElement
        }
    node?.let {
        if (property == null) {
            it.removeAttribute("style")
        } else {
            it.style.removeProperty(property)
        }
    }
}

fun defined(value: dynamic): Boolean = value != undefined && value != null
