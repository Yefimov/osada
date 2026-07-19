package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Position, visibility and stylesheet helpers ported from the legacy UI. Split from [DOM]
 * purely to keep that file within the project's function-count limits.
 */

fun getPosition(id: String): dynamic {
    val element = byId(id)
    val visible = isVisible(id)
    if (!visible) makeVisible(id)
    val rect = element?.getBoundingClientRect()
    if (!visible) makeHidden(id)
    return rect
}

fun getCoordinates(element: Element): dynamic {
    val rect = element.getBoundingClientRect()
    val bodyRect = document.body?.getBoundingClientRect()
    return jsObject {
        this.x = (rect.left - (bodyRect?.left ?: 0.0) + window.scrollX)
        this.y = (rect.top - (bodyRect?.top ?: 0.0) + window.scrollY)
    }
}

fun getStyleSheet(selector: String): dynamic {
    val sheets = document.styleSheets.asDynamic()
    for (i in 0 until document.styleSheets.length) {
        val rules = rulesOf(sheets[i]) ?: continue
        val match = findRuleInList(rules, selector)
        if (match != null) return match
    }
    return null
}

private fun rulesOf(sheet: dynamic): dynamic =
    if (sheet == null) {
        null
    } else {
        try {
            sheet.cssRules
        } catch (_: Throwable) {
            null
        }
    }

private fun findRuleInList(
    rules: dynamic,
    selector: String,
): dynamic {
    for (j in 0 until rules.length) {
        val rule = rules[j] ?: continue
        val match = findMatchInRule(rule, selector)
        if (match != null) return match
    }
    return null
}

private fun findMatchInRule(
    rule: dynamic,
    selector: String,
): dynamic {
    val style =
        try {
            rule.style
        } catch (_: Throwable) {
            null
        }
    if (style != null) {
        val subMatch = findMatchInSubRules(rule, selector)
        if (subMatch != null) return subMatch
    }
    return if (rule.selectorText == selector) rule.style else null
}

private fun findMatchInSubRules(
    rule: dynamic,
    selector: String,
): dynamic {
    val subRules =
        try {
            rule.styleSheet?.cssRules
        } catch (_: Throwable) {
            null
        }
    val length = if (subRules == null) 0 else subRules.length as Int
    for (k in 0 until length) {
        val subRule = subRules[k]
        if (subRule != null && subRule.selectorText == selector) return subRule.style
    }
    return null
}

fun makeVisible(id: String) {
    byId(id)?.style?.display = "inline"
    byId("game")?.focus()
}

fun makeHidden(id: String) {
    byId(id)?.style?.display = "none"
    byId("game")?.focus()
}

fun isVisible(id: String): Boolean {
    val display = byId(id)?.style?.display ?: ""
    return display != "" && display != "none"
}

fun addStyleSheet(name: String): Boolean {
    val link = document.createElement("link") as HTMLElement
    link.setAttribute("rel", "stylesheet")
    link.setAttribute("type", "text/css")
    link.setAttribute("href", "css/$name")
    val head = document.getElementsByTagName("head").asDynamic()[0] as? Element
    head?.appendChild(link)
    return true
}
