package org.osada.ui

import kotlinx.browser.document
import org.osada.PlayerType
import org.osada.model.GameMap
import org.osada.uiSettings
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import kotlin.math.roundToInt

/**
 * The "Turn Report" window (`#combatLog`): a proper window frame (header + summary stat tiles +
 * a scrollable, grouped event feed) instead of the legacy single flowing block of prose. Two
 * display modes share this same content, controlled by `fromStatusBar`:
 *  - true  (sidebar toggle / Esc): the FULL report, height budgeted up to the unit-info card.
 *  - false (automatic at turn start, [StatusBarController.showStatusExtension]): a short teaser
 *    capped at ~220px with an "expand to full report" banner — the same content, just clipped.
 *
 * The header/summary tiles live in [CombatLogHeader]; the event feed's shared row/group
 * plumbing in [CombatLogFeed]; the Combat group (by far the busiest) in [CombatLogCombatGroup];
 * the remaining groups in [CombatLogGroups].
 */
object UICombatLog {
    private const val FALLBACK_HEIGHT_VIEWPORT_FRACTION = 0.6
    private const val EXPAND_BUTTON_MARGIN_PX = 8
    private const val TEASER_HEIGHT_CAP_PX = 220

    init {
        val global: dynamic = js("window.UICombatLog = window.UICombatLog || {}")
        global.toggleCombatLog = { toggleCombatLog() }
    }

    private var outsideClickListener: ((Event) -> Unit)? = null

    fun toggleCombatLog(
        forceShow: Boolean = false,
        fromStatusBar: Boolean = false,
    ) {
        val game = gameRef()
        val currentPlayer = game?.scenario?.map?.currentPlayer ?: return
        if (currentPlayer.type != PlayerType.HUMAN_LOCAL && currentPlayer.type != PlayerType.AI_SCRIPTED) return
        if (forceShow || !isVisible("combatLog")) {
            showCombatLog(fromStatusBar)
            toggleButton("combatLogButton", true)
            attachOutsideClickToClose()
        } else {
            makeHidden("combatLog")
            toggleButton("combatLogButton", false)
            detachOutsideClickToClose()
        }
        UIBuilder.closeDossier()
    }

    /** Click-outside-to-close: previously the ONLY reliable way to close this window was the
     *  sidebar toggle button (Esc also worked, via MainMenuButtonHandler.handleGlobalEscape, but wasn't
     *  discoverable) — the "g"/"@" header buttons looked like close controls but weren't, or
     *  closed it as an unrelated side effect of opening something else. "mousedown" (not "click")
     *  is used so the SAME click that opens the log can never immediately re-close it: that click
     *  event's mousedown phase already fired before this listener gets attached. */
    private fun attachOutsideClickToClose() {
        detachOutsideClickToClose()
        val listener: (Event) -> Unit = { event ->
            val target = event.target
            val insideLog = byId("combatLog")?.asDynamic()?.contains(target) == true
            val onToggleButton = byId("combatLogButton")?.asDynamic()?.contains(target) == true
            if (!insideLog && !onToggleButton) toggleCombatLog()
        }
        outsideClickListener = listener
        document.addEventListener("mousedown", listener)
    }

    private fun detachOutsideClickToClose() {
        outsideClickListener?.let { document.removeEventListener("mousedown", it) }
        outsideClickListener = null
    }

    /** Hard-close for non-toggle code paths (end turn, new scenario) that used to hide the log
     *  by calling `makeHidden("combatLog")` directly. Those must ALSO clear the button's selected
     *  state and detach the outside-click listener — otherwise a stray listener stays armed and
     *  fires toggleCombatLog() (reopening the now-invisible log) on the next unrelated click. */
    fun forceClose() {
        if (!isVisible("combatLog")) return
        makeHidden("combatLog")
        toggleButton("combatLogButton", false)
        detachOutsideClickToClose()
    }

    fun showCombatLog(fromStatusBar: Boolean) {
        val logContainer = byId("combatLog")
        val map = gameRef()?.scenario?.map?.map
        val gameMap = gameRef()?.scenario?.map as? GameMap
        if (logContainer == null || map == null || gameMap == null) return
        clearTag(logContainer)
        logContainer.className = "osada-tr"

        val header = CombatLogHeader.buildHeader(logContainer)
        val summary = CombatLogHeader.buildSummaryTiles(logContainer, gameMap)
        val feed = addTag(logContainer, "div")
        feed.className = "osada-tr-feed"
        // Teaser-mode marker: attachGroup's header click reads it (group headers expand the
        // teaser to the full report instead of collapsing — a collapse inside a 220px clipped
        // strip isn't a meaningful interaction anyway; user request).
        if (fromStatusBar) {
            logContainer.classList.remove("osada-tr--teaser")
        } else {
            logContainer.classList.add("osada-tr--teaser")
        }
        val expandButton = if (!fromStatusBar) buildExtendButton(feed) else null

        val groups =
            listOf(
                CombatLogCombatGroup.buildCombatGroup(map),
                CombatLogGroups.buildSurrenderGroup(),
                CombatLogGroups.buildObjectiveGroup(map),
                CombatLogGroups.buildResupplyGroup(),
                CombatLogGroups.buildReinforceGroup(),
                CombatLogGroups.buildLeadersGroup(),
            )
        if (groups.none { it.count > 0 }) {
            val empty = addTag(feed, "div")
            empty.className = "osada-tr-empty"
            empty.textContent = "All quiet this turn — no combat, objectives, or reinforcements to report."
        } else {
            groups.forEach { if (it.count > 0) CombatLogFeed.attachGroup(feed, it) }
        }

        // NOT makeVisible(): that sets inline display:inline, which — being an inline style —
        // would always beat the stylesheet's display:flex (the same trap #equipment/#unit-info
        // hit earlier). isVisible()/makeHidden() only care that the inline value is non-empty and
        // non-"none", so setting "flex" directly here keeps isVisible("combatLog") working.
        logContainer.style.display = "flex"
        byId("game")?.focus()
        applyLogHeight(logContainer, header, summary, feed, expandButton, fromStatusBar)
    }

    private fun applyLogHeight(
        logContainer: HTMLElement,
        header: HTMLElement,
        summary: HTMLElement,
        feed: HTMLElement,
        expandButton: HTMLElement?,
        fromStatusBar: Boolean,
    ) {
        val unitInfoPos = getPosition("unit-info")
        var height = ((unitInfoPos.top as Double - 30.0) / uiSettings.uiScale).roundToInt()
        // Degenerate guard: with #unit-info hidden (display:none — e.g. nothing selected, or the
        // Inspect pin off), getPosition reads 0 → height goes NEGATIVE → style.height = "-30px"
        // is invalid CSS the browser silently ignores, so the window kept whatever inline height
        // it had before — after a full-report open, that's the BIG height, making the next teaser
        // "open big". Fall back to a sane viewport fraction instead of a garbage input.
        if (height <= 0) height = (windowInnerHeight() * FALLBACK_HEIGHT_VIEWPORT_FRACTION).roundToInt()
        // Natural content height: header/summary are fixed, but the feed is its own scroll
        // container, so its SCROLLHEIGHT (full content) is what tells us whether everything
        // already fits without scrolling — clientHeight would just report back whatever we're
        // about to set.
        val naturalHeight = header.offsetHeight + summary.offsetHeight + feed.scrollHeight
        if (height > naturalHeight) height = naturalHeight
        if (fromStatusBar) {
            logContainer.style.height = "${height}px"
        } else {
            // Quiet turns: if the whole report (minus the expand button itself, incl. its 8px
            // margin) already fits inside the teaser cap, there is nothing to expand — a
            // "Show Full Turn Report" button whose click visibly changes nothing reads as the
            // button being broken (user report). Drop it and show the complete report directly.
            if (expandButton != null &&
                naturalHeight - expandButton.offsetHeight - EXPAND_BUTTON_MARGIN_PX <= TEASER_HEIGHT_CAP_PX
            ) {
                delTag(expandButton)
                val fitsHeight = header.offsetHeight + summary.offsetHeight + feed.scrollHeight
                if (height > fitsHeight) height = fitsHeight
            }
            if (height > TEASER_HEIGHT_CAP_PX) height = TEASER_HEIGHT_CAP_PX
            logContainer.style.height = "${height}px"
            feed.scrollTop = 0.0
        }
    }

    private fun buildExtendButton(container: HTMLElement): HTMLElement {
        val button = addTag(container, "div")
        button.className = "osada-tr-expand"
        button.title = "Open the complete Turn Report with every event group and scrollable details."
        button.innerHTML = "Show Full Turn Report<span class=\"osada-ico osada-ico--map osada-tr-expand__ico\"></span>"
        button.onclick = { _: MouseEvent -> showCombatLog(true) }
        return button
    }

    private fun windowInnerHeight(): Int = js("window.innerHeight") as Int
}
