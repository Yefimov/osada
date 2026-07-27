package org.osada.ui

import kotlinx.browser.window
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * Touch/mouse gesture and user-agent/environment helpers ported from the legacy UI. Split from
 * [DOM] purely to keep that file within the project's function-count limits.
 */

fun hasTouch(): Boolean = js("('ontouchstart' in window)") as? Boolean ?: false

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

fun hasBrokenScroll(): Boolean {
    val ua = window.navigator.userAgent
    return (
        ua.contains(Regex("android 2", RegexOption.IGNORE_CASE)) &&
            ua.contains(Regex("applewebkit", RegexOption.IGNORE_CASE))
    ) ||
        (
            ua.contains(Regex("android 4", RegexOption.IGNORE_CASE)) &&
                ua.contains(Regex("chrome", RegexOption.IGNORE_CASE)) &&
                ua.contains(Regex("applewebkit", RegexOption.IGNORE_CASE))
        )
}

fun hasBrokenClearRect(): Boolean {
    val ua = window.navigator.userAgent
    return ua.contains(Regex("android 4", RegexOption.IGNORE_CASE)) &&
        !ua.contains(Regex("chrome", RegexOption.IGNORE_CASE))
}

fun isChromeApp(): Boolean = js("window.chrome && chrome.app && chrome.app.runtime") as? Boolean ?: false

// Small dead zone so a touch-scroll gesture doesn't fight page scroll on a near-zero drag.
private const val TOUCH_SCROLL_THRESHOLD_PX = 5

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
        val blocksVerticalScroll =
            (
                element.scrollTop < element.scrollHeight - element.offsetHeight &&
                    newScrollTop < scrollTop - TOUCH_SCROLL_THRESHOLD_PX
            ) ||
                (element.scrollTop > 0 && newScrollTop > scrollTop + TOUCH_SCROLL_THRESHOLD_PX)
        if (blocksVerticalScroll) event.preventDefault()
        val blocksHorizontalScroll =
            (
                element.scrollLeft < element.scrollWidth - element.offsetWidth &&
                    newScrollLeft < scrollLeft - TOUCH_SCROLL_THRESHOLD_PX
            ) ||
                (element.scrollLeft > 0 && newScrollLeft > scrollLeft + TOUCH_SCROLL_THRESHOLD_PX)
        if (blocksHorizontalScroll) event.preventDefault()
        element.scrollTop = newScrollTop
        element.scrollLeft = newScrollLeft
    })
}

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

private const val MOUSE_WHICH_RIGHT_BUTTON = 3

fun MouseEvent.rclick(): Boolean =
    if (this.asDynamic().which.toInt() == MOUSE_WHICH_RIGHT_BUTTON) true else this.button.toInt() == 2

fun jsObject(init: dynamic.() -> Unit): dynamic {
    val obj = js("({})")
    init(obj)
    return obj
}
