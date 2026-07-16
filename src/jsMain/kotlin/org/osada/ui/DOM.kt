package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.uiSettings
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLMetaElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * Small DOM helpers ported from the legacy UI.
 */

@JsName("$")
fun byId(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

@JsName("$$")
fun query(selector: String): Element? = document.querySelector(selector)

fun Element?.query(selector: String): Element? = this?.querySelector(selector)

fun addTag(parent: dynamic, tag: String): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    val parentNode: Element? = when (parent) {
        is String -> byId(parent)
        is Element -> parent
        else -> parent as? Element
    }
    parentNode?.appendChild(element)
    return element
}

fun insertTag(parent: dynamic, tag: String, before: dynamic): HTMLElement {
    val element = document.createElement(tag) as HTMLElement
    val parentNode: Element? = when (parent) {
        is String -> byId(parent)
        is Element -> parent
        else -> parent as? Element
    }
    val beforeNode: Element? = when (before) {
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
    val node: Element? = when (element) {
        is String -> byId(element)
        is Element -> element
        else -> element as? Element
    }
    node?.parentNode?.removeChild(node)
}

fun clearTag(element: dynamic) {
    val node: Element? = when (element) {
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

fun clearStyle(element: dynamic, property: String? = null) {
    val node: HTMLElement? = when (element) {
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
        val sheet = sheets[i] ?: continue
        val rules = try {
            sheet.cssRules
        } catch (_: Throwable) {
            null
        } ?: continue
        for (j in 0 until rules.length) {
            val rule = rules[j] ?: continue
            val style = try {
                rule.style
            } catch (_: Throwable) {
                null
            }
            if (style != null) {
                val subRules = try {
                    rule.styleSheet?.cssRules
                } catch (_: Throwable) {
                    null
                }
                if (subRules != null) {
                    for (k in 0 until subRules.length) {
                        val subRule = subRules[k] ?: continue
                        if (subRule.selectorText == selector) {
                            return subRule.style
                        }
                    }
                }
            }
            if (rule.selectorText == selector) {
                return rule.style
            }
        }
    }
    return null
}

fun defined(value: dynamic): Boolean =
    value != undefined && value != null

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

fun toggleButton(element: dynamic, selected: Boolean): Boolean {
    val node: HTMLElement = when (element) {
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
    val node: HTMLElement = when (element) {
        is String -> byId(element)
        is HTMLElement -> element
        else -> element as? HTMLElement
    } ?: return false
    val html = node.innerHTML
    node.innerHTML = if (html == html.uppercase()) html.lowercase() else html.uppercase()
    return true
}

fun addSelectOption(select: dynamic, text: String, value: dynamic, selected: Boolean = false): HTMLElement {
    val option = document.createElement("option") as HTMLElement
    option.asDynamic().value = value
    option.textContent = text
    option.asDynamic().selected = selected
    val sel: Element? = when (select) {
        is String -> byId(select)
        is Element -> select
        else -> select as? Element
    }
    sel?.appendChild(option)
    return option
}

fun setSelectOption(select: HTMLSelectElement, text: String): Boolean {
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

fun hasTouch(): Boolean = js("('ontouchstart' in window)") as? Boolean ?: false

fun hasBrokenScroll(): Boolean {
    val ua = window.navigator.userAgent
    return (ua.contains(Regex("android 2", RegexOption.IGNORE_CASE)) && ua.contains(Regex("applewebkit", RegexOption.IGNORE_CASE)))
            || (ua.contains(Regex("android 4", RegexOption.IGNORE_CASE)) && ua.contains(Regex("chrome", RegexOption.IGNORE_CASE)) && ua.contains(Regex("applewebkit", RegexOption.IGNORE_CASE)))
}

fun hasBrokenClearRect(): Boolean {
    val ua = window.navigator.userAgent
    return ua.contains(Regex("android 4", RegexOption.IGNORE_CASE)) && !ua.contains(Regex("chrome", RegexOption.IGNORE_CASE))
}

fun toggleRightClick(enabled: Boolean) {
    window.oncontextmenu = { enabled }
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

fun touchScroll(elementId: String) {
    val element = byId(elementId) ?: return
    var startY = 0.0
    var startX = 0.0
    var scrollTop = 0.0
    var scrollLeft = 0.0
    element.addEventListener("touchstart", { event: Event ->
        val touch = event.asDynamic().touches[0] ?: return@addEventListener
        startY = element.scrollTop + (touch.pageY as Double)
        startX = element.scrollLeft + (touch.pageX as Double)
        scrollTop = element.scrollTop
        scrollLeft = element.scrollLeft
    })
    element.addEventListener("touchmove", { event: Event ->
        val touch = event.asDynamic().touches[0] ?: return@addEventListener
        val pageY = touch.pageY as Double
        val pageX = touch.pageX as Double
        val newScrollTop = startY - pageY
        val newScrollLeft = startX - pageX
        if ((element.scrollTop < element.scrollHeight - element.offsetHeight && newScrollTop < scrollTop - 5)
            || (element.scrollTop > 0 && newScrollTop > scrollTop + 5)
        ) {
            event.preventDefault()
        }
        if ((element.scrollLeft < element.scrollWidth - element.offsetWidth && newScrollLeft < scrollLeft - 5)
            || (element.scrollLeft > 0 && newScrollLeft > scrollLeft + 5)
        ) {
            event.preventDefault()
        }
        element.scrollTop = newScrollTop
        element.scrollLeft = newScrollLeft
    })
}

fun bounceText(x: Double, y: Double, text: String, green: Boolean = false) {
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

fun MouseEvent.rclick(): Boolean {
    return if (this.asDynamic().which.toInt() == 3) true else this.button.toInt() == 2
}

fun jsObject(init: dynamic.() -> Unit): dynamic {
    val obj = js("({})")
    init(obj)
    return obj
}

fun isChromeApp(): Boolean {
    return js("window.chrome && chrome.app && chrome.app.runtime") as? Boolean ?: false
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

fun toggleButtonWithImage(element: dynamic, selected: Boolean): Boolean {
    val node: HTMLElement = when (element) {
        is String -> byId(element)
        is HTMLElement -> element
        else -> element as? HTMLElement
    } ?: return false
    val image = node.firstChild as? HTMLImageElement ?: node as? HTMLImageElement
    if (selected) hoverin(image) else hoverout(image)
    return true
}

fun toggleCheckboxWithImage(element: dynamic): Boolean {
    val node: HTMLElement = when (element) {
        is String -> byId(element)
        is HTMLElement -> element
        else -> element as? HTMLElement
    } ?: return false
    val image = node as? HTMLImageElement ?: return false
    val src = image.src
    val dir = src.substring(0, src.lastIndexOf("/") + 1)
    val name = src.substring(src.lastIndexOf("/") + 1)
    val checkedIndex = name.lastIndexOf("-checked")
    val baseName = if (checkedIndex != -1) name.substring(0, checkedIndex) else name.substring(0, name.lastIndexOf(".")) + "-checked"
    image.src = "$dir$baseName.png"
    return true
}

fun changeViewPort() {
    val gameState = js("new GameState(null)")
    gameState.restoreSettings()

    var scale = 1.0
    var maxScale = 1.0
    var prefix = "width=device-width, "
    val pixelRatio = window.devicePixelRatio

    val metas = document.getElementsByTagName("meta").asDynamic()
    var viewport: HTMLMetaElement? = null
    for (i in 0 until document.getElementsByTagName("meta").length) {
        val meta = metas[i] as? HTMLMetaElement
        if (meta?.name == "viewport") {
            viewport = meta
            break
        }
    }
    if (viewport == null) {
        val head = document.getElementsByTagName("head").asDynamic()[0] as? Element
        viewport = document.createElement("meta") as HTMLMetaElement
        viewport.id = "viewport"
        viewport.name = "viewport"
        head?.appendChild(viewport)
    }

    val ua = window.navigator.userAgent
    if (ua.contains(Regex("(iPhone|iPod)", RegexOption.IGNORE_CASE))) {
        prefix = ""
        if (pixelRatio == 1.0) {
            @Suppress("UNUSED_VARIABLE")
            val ratio = 2.0
        }
    }

    var d = scale
    var l = maxScale
    var k = pixelRatio
    if (k > 1 && k < 2) k = 2.0
    if (uiSettings.useRetina) {
        l = 1 / k
        d = 1 / k
    }
    viewport.content = "${prefix}initial-scale=$d,maximum-scale=$l, user-scalable=1"
}
