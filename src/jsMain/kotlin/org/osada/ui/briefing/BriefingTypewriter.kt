package org.osada.ui.briefing

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Per-character reveal for the current dialogue line. [start] begins the animation;
 * [complete] instantly fills in whatever is left (used when the player advances mid-reveal,
 * so the FIRST advance input completes the line and only a SECOND one moves on); [cancel]
 * stops a running reveal outright (used on close/advance/stage-switch to avoid leaked timers).
 */
internal object BriefingTypewriter {
    private const val MS_PER_CHAR = 16

    private var intervalId: Int? = null
    private var pendingText: String = ""
    private var pendingOnDone: (() -> Unit)? = null

    fun start(
        el: HTMLElement,
        fullText: String,
        onDone: () -> Unit,
    ) {
        cancel()
        if (fullText.isEmpty() || reducedMotion()) {
            el.textContent = fullText
            onDone()
            return
        }
        el.textContent = ""
        pendingText = fullText
        pendingOnDone = onDone
        var shown = 0
        intervalId =
            window.setInterval({
                shown++
                el.textContent = fullText.substring(0, shown)
                if (shown >= fullText.length) finishReveal()
            }, MS_PER_CHAR)
    }

    /** Fills the element with the full text immediately; if a reveal was in progress, its
     *  onDone callback still fires (so choices/etc. appear as normal). */
    fun complete(el: HTMLElement) {
        if (intervalId == null) return
        el.textContent = pendingText
        finishReveal()
    }

    fun isRevealing(): Boolean = intervalId != null

    fun cancel() {
        intervalId?.let { window.clearInterval(it) }
        intervalId = null
        pendingOnDone = null
    }

    private fun finishReveal() {
        window.clearInterval(intervalId ?: return)
        intervalId = null
        val done = pendingOnDone
        pendingOnDone = null
        done?.invoke()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun reducedMotion(): Boolean =
        try {
            window.matchMedia("(prefers-reduced-motion: reduce)").matches
        } catch (e: Throwable) {
            false
        }
}
