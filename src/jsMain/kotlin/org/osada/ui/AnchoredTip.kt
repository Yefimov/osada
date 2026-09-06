package org.osada.ui

import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * The Weather panel's visual language, reused as a general anchored explanation panel
 * (`docs/design/action-affordances-and-objectives.md` §4): title, status line, short explanation,
 * then green effect / red blocking / neutral rule lines.
 *
 * One shared singleton node per id. Positioning keeps the panel inside the visual viewport, and
 * flips it above the anchor when there is no room below.
 */
internal object AnchoredTip {
    /** Matches `.osada-wtip`'s declared width in `osada-theme.css`. */
    private const val PANEL_WIDTH = 340.0
    private const val VIEWPORT_MARGIN = 6.0
    private const val ANCHOR_GAP = 6.0

    fun show(
        id: String,
        anchor: HTMLElement,
        html: String,
    ) {
        val tip = ensureNode(id)
        tip.innerHTML = html
        tip.style.display = "block"
        position(tip, anchor)
    }

    fun hide(id: String) {
        byId(id)?.style?.display = "none"
    }

    /** Builds the panel body. [lines] are `(kind, text)` with kind in `good` / `bad` / `dim`.
     *  [keyCap], when present, rides in the title row rather than being repeated in prose
     *  (`docs/design/keyboard-shortcuts-and-help.md` §7). */
    fun html(
        title: String,
        status: String,
        statusOk: Boolean,
        description: String,
        lines: List<Pair<String, String>>,
        keyCap: String? = null,
    ): String {
        val statusClass = if (statusOk) "osada-atip__status--ok" else "osada-atip__status--no"
        val body = StringBuilder()
        body.append("<div class=\"osada-wtip__title\">").append(escape(title))
        if (!keyCap.isNullOrBlank()) {
            body.append("<span class=\"osada-atip__cap\">").append(escape(keyCap)).append("</span>")
        }
        body.append("</div>")
        body
            .append("<div class=\"osada-atip__status ")
            .append(statusClass)
            .append("\">")
            .append(escape(status))
            .append("</div>")
        if (description.isNotBlank()) {
            body.append("<div class=\"osada-wtip__story\">").append(escape(description)).append("</div>")
        }
        lines.forEach { (kind, text) ->
            body
                .append("<div class=\"osada-wtip__line osada-wtip__line--")
                .append(kind)
                .append("\">")
                .append(escape(text))
                .append("</div>")
        }
        return body.toString()
    }

    /**
     * A plain explanation panel: an optional heading over one run of prose. This is what a native
     * `title=` becomes when it has to work on a touch screen ([TapTip]), so it stays deliberately
     * plainer than [html] — a `title` carries no status and no rule lines, and inventing either
     * here would put words in the attribute's mouth.
     */
    fun helpHtml(
        title: String,
        body: String,
    ): String {
        val heading =
            if (title.isBlank()) {
                ""
            } else {
                "<div class=\"osada-wtip__title\">${escape(title)}</div>"
            }
        return heading + "<div class=\"osada-wtip__story\">${escape(body)}</div>"
    }

    private fun ensureNode(id: String): HTMLElement =
        byId(id) ?: run {
            val node = addTag("mainbody", "div")
            node.id = id
            node.className = "osada-wtip osada-atip"
            node
        }

    private fun position(
        tip: HTMLElement,
        anchor: HTMLElement,
    ) {
        val rect = anchor.asDynamic().getBoundingClientRect()
        val anchorLeft = (rect.left as? Number)?.toDouble() ?: 0.0
        val anchorTop = (rect.top as? Number)?.toDouble() ?: 0.0
        val anchorBottom = (rect.bottom as? Number)?.toDouble() ?: 0.0
        val viewportWidth = window.innerWidth.toDouble()
        val viewportHeight = window.innerHeight.toDouble()
        val left =
            anchorLeft
                .coerceAtMost(viewportWidth - PANEL_WIDTH - VIEWPORT_MARGIN)
                .coerceAtLeast(VIEWPORT_MARGIN)
        tip.style.left = "${left.toInt()}px"
        // Measure after the content is in place: the panel's height depends on how many lines the
        // action produced, and an action strip normally sits at the bottom of the screen.
        val height = (tip.asDynamic().offsetHeight as? Number)?.toDouble() ?: 0.0
        val below = anchorBottom + ANCHOR_GAP
        val overflowsBelow = below + height + VIEWPORT_MARGIN > viewportHeight
        val fitsAbove = anchorTop - height - ANCHOR_GAP >= VIEWPORT_MARGIN
        val top =
            if (overflowsBelow && fitsAbove) {
                anchorTop - height - ANCHOR_GAP
            } else {
                below.coerceAtMost((viewportHeight - height - VIEWPORT_MARGIN).coerceAtLeast(VIEWPORT_MARGIN))
            }
        tip.style.top = "${top.toInt()}px"
    }

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
