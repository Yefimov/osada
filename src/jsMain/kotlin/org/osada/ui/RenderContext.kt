package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameMap
import org.osada.model.ScreenPos
import org.osada.model.getUnitImagesList
import kotlin.js.json
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------
// Shared style objects (mirror the legacy JS objects)
// `internal` so every renderer collaborator in this package shares
// the same singletons — `drawHex` relies on identity comparison
// (`style === hexStyles["deploy"]`).
// ------------------------------------------------------------------

internal val hexStyles =
    json(
        "move" to
            json(
                "fillColor" to "rgba(120,100,100,0.5)",
                "lineColor" to "rgba(0,0,0,0.4)",
                "lineWidth" to 1,
                "lineJoin" to "miter",
            ),
        "movespotted" to
            json(
                "fillColor" to "rgba(128,128,128,0.5)",
                "lineColor" to "rgba(0,0,0,0.4)",
                "lineWidth" to 1,
                "lineJoin" to "miter",
            ),
        "attack" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(239, 0, 0,0.8)",
                "lineWidth" to 3,
                "lineJoin" to "miter",
            ),
        // AA threat overlay (DEFERRED.md §1.1, dashed style per §1.15): outline-only so it
        // composites over the move/attack fill already drawn on the same hex -- warns that spotted
        // enemy AA covers this cell without implying anything about hidden AA, which must never be
        // drawn (the whole point of the ambush). Dashed rather than solid because the warning is
        // conditional: mode-2 spotted AA only fires if the plane's move actually ENDS here, never on
        // a hex it merely flies over on the way to a farther, unmarked destination (§1.15) -- a
        // solid ring (like "attack"'s) would read as unconditional danger, which this isn't.
        "aathreat" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(255, 165, 0, 0.85)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
                "lineDash" to arrayOf(4, 3),
            ),
        // A hex the Barrage targeting mode is offering (OG 9.2, `rules/Barrage`): fire into what
        // you cannot see. Solid and red-brown rather than dashed, because unlike the AA ring this
        // is not a warning at all -- it is the set of legal orders, and the player opened the mode
        // on purpose.
        "barragetarget" to
            json(
                "fillColor" to "rgba(150, 60, 30, 0.28)",
                "lineColor" to "rgba(220, 110, 60, 0.95)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
            ),
        // The objective the player just clicked in the rail (`ObjectiveFocus`). Green, because it
        // is the only overlay in this table that is neither an order nor a threat -- it answers
        // "which one did I just ask about?" and nothing else, so it must not be confusable with
        // the red attack ring or the orange AA warning. Outline-only and thick, so it reads over
        // whatever fill, flag and unit sprite the hex already carries.
        "objectivefocus" to
            json(
                "fillColor" to "rgba(80, 190, 110, 0.22)",
                "lineColor" to "rgba(120, 230, 150, 0.95)",
                "lineWidth" to 3,
                "lineJoin" to "miter",
            ),
        // Reachable only by mounting the formation's own transport first (`rules/AutoMount`).
        // Outline-only, so it composites over the move fill the same hex already carries, and
        // DASHED for the reason "aathreat" is: the mark is conditional. Walking is what the player
        // asked for; these hexes cost a ride, and the formation arrives sitting in a truck.
        "transportmove" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(214, 176, 96, 0.9)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
                "lineDash" to arrayOf(5, 3),
            ),
        // Detected enemy minefield (OG 9.9, `rules/Minefields`). Solid, unlike "aathreat", because
        // the warning is UNCONDITIONAL: entering this hex costs the rest of the move, whatever the
        // route was going to do afterwards. It is drawn for DETECTED fields only, by construction of
        // `Minefields.isKnownThreat` -- an undetected field must never appear, exactly as hidden AA
        // never does (DEFERRED.md §1.1). Outline plus a light fill so it reads on a spotted and an
        // unspotted hex alike.
        "minefield" to
            json(
                "fillColor" to "rgba(180, 30, 30, 0.22)",
                "lineColor" to "rgba(220, 60, 60, 0.9)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
            ),
        // Engineering work in progress (OG 9.3). The map is a pre-rendered image, so a half-built
        // bridge cannot be painted into it -- this outline is the only way a site under construction
        // differs from the hex beside it, and `DEFERRED.md` 1.1's rule is that a mechanic the
        // player pays prestige for must have a visible cause. Dashed and dim, because unlike the
        // minefield ring it is not a warning: it marks the player's OWN unfinished job, and it
        // must not read as a threat or compete with the selection overlays drawn under it.
        "construction" to
            json(
                "fillColor" to "rgba(200, 170, 60, 0.16)",
                "lineColor" to "rgba(230, 200, 90, 0.85)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
                "lineDash" to arrayOf(5, 4),
            ),
        "current" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(255, 240, 0, 0.8)",
                "lineWidth" to 3,
                "lineJoin" to "round",
            ),
        "currentstroke" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(0, 0, 0, 0.9)",
                "lineWidth" to 2,
                "shadowOffsetX" to 1,
                "shadowOffsetY" to 2,
                "shadowColor" to "black",
                "lineJoin" to "round",
            ),
        "generic" to
            json(
                "fillColor" to null,
                "lineColor" to "rgba(39,44,47,0.9)",
                "lineWidth" to 0.4,
                "lineJoin" to "miter",
            ),
        "deploy" to
            json(
                "fillColor" to "rgba(128,128,128,0.8)",
                "lineColor" to "rgba(0,0,0,0.4)",
                "lineWidth" to 1,
                "lineJoin" to "miter",
            ),
        "ownunit" to
            json(
                "fillColor" to "rgba(30,144,255,0.3)",
                "lineColor" to "rgba(0,0,0,0.4)",
                "lineWidth" to 0,
                "lineJoin" to "miter",
            ),
        "airunit" to
            json(
                "fillColor" to "rgba(30,144,255,0.3)",
                "lineColor" to "rgba(50, 110, 240,0.6)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
            ),
        "combat" to
            json(
                "fillColor" to "rgba(255, 255, 255,0.15)",
                "lineColor" to "rgba(239,0,0,0.7)",
                "lineWidth" to 2,
                "lineJoin" to "miter",
            ),
        "fog" to
            json(
                "fillColor" to "rgba(20,24,28,0.45)",
                "lineColor" to null,
                "lineWidth" to 0,
                "lineJoin" to "miter",
            ),
    )

internal val unitStyles =
    json(
        "axisBox" to "#383838",
        "alliedBox" to "#808000",
        "axisBorder" to "rgba(211, 211, 211, 1)",
        "alliedBorder" to "rgba(127, 255, 0, 1)",
        "enemyBoxMarked" to "#FF0000",
        "playerText" to "white",
        "alliedPlayerText" to "#696969",
        "movedUnitText" to "#BDBDBD",
        "terrainText" to "#333333",
        "terrainTextStroke" to "#f8e064",
    )

internal val terrainEncoding =
    listOf(
        "A",
        "B",
        "C",
        "D",
        "D",
        "E",
        "F",
        "G",
        "H",
        "I",
        "J",
        "K",
        "L",
        "M",
        "N",
        "O",
        "Q",
    )

internal val directionToRadians =
    arrayOf(
        PI,
        5 * PI / 6,
        3 * PI / 4,
        2 * PI / 3,
        PI / 2,
        PI / 3,
        PI / 4,
        PI / 6,
        0.0,
        11 * PI / 6,
        7 * PI / 4,
        5 * PI / 3,
        3 * PI / 2,
        4 * PI / 3,
        5 * PI / 4,
        7 * PI / 6,
    )

/**
 * Mutable bookkeeping for one [RenderContext.cacheImages] call: how many image loads have been
 * registered/completed so far, and the callback to fire once every registered load has settled.
 */
private class ImageLoadState(
    private val callback: () -> Unit,
) {
    var loaded = 0
    var total = 0
    var registered = false

    fun checkDone() {
        loaded++
        if (registered && loaded >= total) callback()
    }
}

/** Starts loading [src] into an `Image`, wiring both load and error events to [ImageLoadState.checkDone]. */
private fun ImageLoadState.load(
    src: String,
    into: (dynamic) -> Unit,
) {
    total++
    val img = js("new Image()")
    img.onload = { checkDone() }
    img.onerror = { checkDone() }
    img.src = src
    into(img)
}

/**
 * An image put in the cache by an EARLIER cacheImages call may still be in flight.
 * On restore, UI.init warms the cache with an empty callback and setNewScenario's
 * second call then saw "everything cached", fired its callback immediately and
 * render()ed incomplete images — drawImage silently draws nothing, so a restored
 * save came up with an empty unit layer until something forced a redraw. Wait on
 * such images too; addEventListener so the first call's own onload still runs.
 */
private fun ImageLoadState.waitFor(img: dynamic) {
    if (img == null || img == undefined) return
    if (img.complete == true) return
    total++
    img.addEventListener("load", { checkDone() })
    img.addEventListener("error", { checkDone() })
}

private fun ImageLoadState.loadOrWait(
    current: dynamic,
    src: String,
    into: (dynamic) -> Unit,
) {
    if (current == null) load(src, into) else waitFor(current)
}

/**
 * The rendering substrate shared by all renderer collaborators.
 *
 * Owns the canvas elements/contexts, the cached images, the fixed hex geometry and the
 * low-level primitives ([cellToScreen], [screenToCell], [drawHex], [getBounds]) plus the
 * canvas lifecycle ([initCanvases], [cacheImages], [positionLayers], [setNewMap]).
 *
 * Extracted from the former `Render` god-class so the higher-level renderers
 * ([MapRenderer], [UnitRenderer], [OverlayRenderer], [CursorRenderer], [MapAnimator])
 * depend on this one state holder instead of being one monolith. Members are `internal`
 * because the collaborators live in the same package and share this single instance.
 */
internal class RenderContext(
    var map: GameMap?,
) {
    companion object {
        private const val CURSOR_BACKBUFFER_SIZE = 54
        private const val UNIT_BACKBUFFER_SIZE = 120

        // The terrain image is 65px shorter than the canvases, which get +65 for the last hex row.
        internal const val LAST_HEX_ROW_HEIGHT = 65.0
        private const val Z_INDEX_UNIT_BACKBUFFER = 3

        // Fallback only, for the moment before the HUD exists (start menu, first paint). The real
        // top-bar height is MEASURED — the renderer must not own a HUD constant, or the desktop
        // 30px bar and the taller mobile bar cannot both be right (MOB-AUDIT-008).
        private const val TOPBAR_FALLBACK_HEIGHT = 30.0

        internal fun topBarHeight(): Double {
            val measured = ViewportMetricsService.current.topBarHeight
            return if (measured > 0.0) measured else TOPBAR_FALLBACK_HEIGHT
        }
    }

    // Hex geometry constants (match legacy JS exactly)
    val hexTopWidth: Double = 30.0
    val hexSlantWidth: Double = 15.0
    val v: Double = 25.0
    val flagIconWidth: Double = 21.0
    val flagIconHeight: Double = 14.0
    val unitFontSize: Double = 8.0

    // Canvas elements
    var mapCanvas: dynamic = null
    var hexesCanvas: dynamic = null
    var cursorCanvas: dynamic = null
    var unitBackBuffer: dynamic = null
    var backBuffer: dynamic = null

    // Contexts
    var mapCtx: dynamic = null
    var hexesCtx: dynamic = null
    var cursorCtx: dynamic = null
    var unitBackCtx: dynamic = null
    var backBufferCtx: dynamic = null

    // Cached images
    var attackCursorImage: dynamic = null
    var flagImage: dynamic = null
    var fireImage: dynamic = null
    var noAmmoImage: dynamic = null
    var leaderAxisImage: dynamic = null
    var leaderAlliedImage: dynamic = null
    var bridgeImage: dynamic = null

    /** Shell craters drawn over cratered open ground (`rules/Craters`). A transparent 30x20 sprite
     *  sized for the hex top, generated by `scripts/make_crater_icon.py`. */
    var craterImage: dynamic = null
    var terrainImage: dynamic = null
    val unitImages: MutableMap<String, dynamic> = mutableMapOf()

    // State
    var hexGridEnabled: Boolean = false
    var mapWidth: Double = 0.0
    var mapHeight: Double = 0.0
    var ba: Double = -(hexTopWidth + hexSlantWidth)
    var ca: Double = -v
    val hexColumnStep: Double = hexTopWidth + hexSlantWidth
    val hexColumnEpsilon: Double = hexTopWidth / 100.0

    private val hexDrawer: HexDrawer by lazy { HexDrawer(this) }

    init {
        initCanvases()
    }

    private fun initCanvases() {
        // Map-zoom wrapper (Task: map zoom): CSS `zoom` is applied HERE, not on #game itself —
        // #game also hosts #gameToolTip and other overlays that must stay at normal HUD size
        // regardless of map zoom. Only the four map canvases live inside this wrapper, so only
        // the actual map art/grid/units/cursor scale. #game keeps scrolling normally around the
        // wrapper's (now zoom-inflated) rendered box — CSS `zoom` participates in real layout,
        // unlike `transform: scale`, so #game's native scrollbars stay truthful for free.
        var wrap = document.getElementById("game-zoom-wrap")
        if (wrap == null) {
            wrap = document.createElement("div")
            wrap.id = "game-zoom-wrap"
            wrap.asDynamic().style.position = "relative"
            document.getElementById("game")?.asDynamic()?.appendChild(wrap)
        }

        fun getOrCreate(
            id: String,
            parent: dynamic = wrap,
        ): dynamic {
            var el = document.getElementById(id)
            if (el == null) {
                el = document.createElement("canvas")
                el.id = id
                parent?.appendChild(el)
            }
            return el.asDynamic()
        }

        mapCanvas = getOrCreate("map")
        hexesCanvas = getOrCreate("hexes")
        cursorCanvas = getOrCreate("cursor")
        unitBackBuffer = getOrCreate("unitbackbuffer")
        backBuffer = document.createElement("canvas").asDynamic()
        backBuffer.id = "backbuffer"

        mapCtx = mapCanvas.getContext("2d")
        hexesCtx = hexesCanvas.getContext("2d")
        cursorCtx = cursorCanvas.getContext("2d")
        unitBackCtx = unitBackBuffer.getContext("2d")
        backBufferCtx = backBuffer.getContext("2d")

        backBuffer.width = CURSOR_BACKBUFFER_SIZE
        backBuffer.height = CURSOR_BACKBUFFER_SIZE
        unitBackBuffer.width = UNIT_BACKBUFFER_SIZE
        unitBackBuffer.height = UNIT_BACKBUFFER_SIZE
    }

    fun cacheImages(callback: () -> Unit) {
        // The callback must not fire until every load below has been REGISTERED: the old
        // "if (loaded >= total) callback()" mid-function checks fired with total==0 when the
        // terrain was already cached, before the unit-image loop had even run.
        val state = ImageLoadState(callback)

        loadFixedImages(state)
        loadTerrainImage(state)
        loadUnitImages(state)

        state.registered = true
        if (state.loaded >= state.total) callback()
    }

    @Suppress("ReturnCount")
    fun positionLayers() {
        val z = terrainImage ?: return
        val game = document.getElementById("game")?.asDynamic() ?: return
        val wrap = document.getElementById("game-zoom-wrap")?.asDynamic()

        // zw/zh: the canvases' own NATURAL (unzoomed) pixel size — background-size and the
        // canvases' own inline width/height (set once in cacheImages) must stay in this space,
        // since CSS `zoom` on the wrapper scales their RENDERED box automatically; pre-scaling
        // these values too would double-scale. scaledW/scaledH: the wrapper's RENDERED (post-
        // zoom) size — what #game's fit/scroll decisions must react to.
        val zw = (z.width as? Number)?.toDouble() ?: 0.0
        val zh = (z.height as? Number)?.toDouble() ?: 0.0
        val zoom = MapZoom.level
        val scaledW = zw * zoom
        val scaledH = zh * zoom
        var left = window.innerWidth / 2.0 - scaledW / 2.0
        if (left < 0) left = 0.0

        mapCanvas.style.zIndex = 0
        mapCanvas.style.position = "absolute"
        mapCanvas.style.left = "0px"
        mapCanvas.style.top = "0px"

        hexesCanvas.style.zIndex = 1
        hexesCanvas.style.position = "absolute"
        hexesCanvas.style.left = "0px"
        hexesCanvas.style.top = "0px"

        cursorCanvas.style.zIndex = 2
        cursorCanvas.style.position = "absolute"
        cursorCanvas.style.left = "0px"
        cursorCanvas.style.top = "0px"

        unitBackBuffer.style.zIndex = Z_INDEX_UNIT_BACKBUFFER
        unitBackBuffer.style.position = "absolute"
        unitBackBuffer.style.left = "0px"
        unitBackBuffer.style.top = "0px"
        unitBackBuffer.style.display = "none"

        mapCanvas.style.backgroundColor = "none"
        mapCanvas.style.backgroundImage = "url('${z.src}')"
        mapCanvas.style.backgroundSize = "${zw.toInt()}px ${zh.toInt()}px"
        mapCanvas.style.backgroundRepeat = "no-repeat"
        mapCanvas.style.backgroundAttachment = "scroll"

        // Wrapper is sized to the canvases' natural pixel box (matches their own inline
        // width/height); `zoom` inflates/shrinks its RENDERED box from there, same technique
        // strategic zoom already uses on #game itself (proven in this codebase).
        wrap?.style?.width = "${zw.toInt()}px"
        wrap?.style?.height = "${(zh + LAST_HEX_ROW_HEIGHT).toInt()}px"
        wrap?.style?.zoom = zoom.toString()

        if (MobileLayoutController.cssOwnsMapViewport) {
            positionForMobileShell(game, wrap, scaledW)
            return
        }

        wrap?.style?.marginLeft = "0px"
        val topBar = topBarHeight()
        val fitW = window.innerWidth >= scaledW
        val fitH = window.innerHeight >= scaledH + topBar
        val g = if (fitW) 0.0 else 10.0
        val k = if (fitH) 0.0 else 30.0

        game.style.width = if (fitW) "${(scaledW + k).toInt()}px" else "${window.innerWidth}px"
        game.style.height =
            if (fitH) {
                "${(scaledH + topBar + g).toInt()}px"
            } else {
                "${(window.innerHeight - topBar).toInt()}px"
            }
        game.style.position = "absolute"
        game.style.left = "${left.toInt()}px"
        game.style.top = "${topBar.toInt()}px"
        game.tabIndex = 1
        game.focus()
    }

    /**
     * Mobile/tablet shells: `#game`'s box belongs to CSS, which is the only place that can combine
     * safe-area insets, the measured top bar and the bottom dock in one rule. Writing inline
     * width/height/left/top here would silently beat that stylesheet, so the renderer restricts
     * itself to centring the map content inside whatever rectangle CSS granted it.
     *
     * `focus()` is also skipped: on mobile it can summon the software keyboard and scroll the page.
     */
    private fun positionForMobileShell(
        game: dynamic,
        wrap: dynamic,
        scaledW: Double,
    ) {
        game.style.width = ""
        game.style.height = ""
        game.style.left = ""
        game.style.top = ""
        game.style.position = ""
        val clientWidth = (game.clientWidth as? Number)?.toDouble() ?: 0.0
        val margin = ((clientWidth - scaledW) / 2.0).coerceAtLeast(0.0)
        wrap?.style?.marginLeft = "${margin.toInt()}px"
        game.tabIndex = 1
    }

    fun setNewMap(newMap: GameMap) {
        map = newMap
        hexGridEnabled = false
        terrainImage = null
        flagImage = null

        val list = newMap.getUnitImagesList()
        val valid = mutableSetOf<String>()
        val keys = js("Object.keys(list)")
        val keyCount = (keys.length as? Number)?.toInt() ?: 0
        for (i in 0 until keyCount) {
            val key = keys[i] as? String
            val src = if (key == null) null else list[key] as? String
            val eqid = key?.toIntOrNull()
            if (eqid != null && src != null) valid.add(UnitIconResolver.forCurrentScenario(eqid, src))
        }
        unitImages.keys.filter { it !in valid }.forEach { unitImages.remove(it) }
    }

    // ------------------------------------------------------------------
    // Coordinate conversion
    // ------------------------------------------------------------------

    fun cellToScreen(
        row: Int,
        col: Int,
        absolute: Boolean,
    ): ScreenPos {
        var y = if (col % 2 == 1) 2.0 * row * v + v + ca else 2.0 * row * v + ca
        var x = col * (hexTopWidth + hexSlantWidth) + hexSlantWidth + ba

        if (absolute) {
            val zoom = MapZoom.level
            x *= zoom
            y *= zoom
            // Result space is #game's own SCROLL CONTENT space (what scrollLeft/scrollTop and the
            // absolutely-positioned map tooltips inside #game are expressed in). The canvases sit
            // at the zoom wrapper's origin and the wrapper is #game's only in-flow child, so the
            // wrapper's measured offset — which already includes any centring margin — is the
            // entire conversion. Deliberately NOT derived from window size plus a hardcoded
            // top-bar height: those two agree only in the desktop layout (MOB-AUDIT-007/008).
            val game = document.getElementById("game")?.asDynamic()
            val wrap = document.getElementById("game-zoom-wrap")?.asDynamic()
            if (game != null && wrap != null) {
                x += doubleOf(wrap.offsetLeft) - doubleOf(game.clientLeft)
                y += doubleOf(wrap.offsetTop) - doubleOf(game.clientTop)
            }
        }
        return ScreenPos(x, y)
    }

    fun screenToCell(
        x: Int,
        y: Int,
    ): Cell {
        // x/y arrive ALREADY compensated for zoom (both continuous map zoom and strategic zoom)
        // by the caller — MapInputController.getClickPos uses canvas.getBoundingClientRect(),
        // which reflects the FULL cumulative CSS zoom of every ancestor automatically, and
        // divides once by that. Compensating again here would double it. (Previously this method
        // tried to compensate itself via a `uiSettings.mapZoom` flag that nothing ever set, so it
        // was silently a no-op — removed rather than left as dead, misleading code.)
        val sx = x.toDouble()
        val sy = y.toDouble()

        val colRaw = (sx - ba) / hexColumnStep + hexColumnEpsilon
        var col = kotlin.math.round(colRaw).toInt() - 1

        val rowRaw = (sy - ca * (1.0 - (col and 1).toDouble())) / v
        var row = kotlin.math.round(rowRaw / 2.0 - (rowRaw.toInt() and 1).toDouble()).toInt()

        val q = map ?: return Cell(0, 0)
        if (row < 0) row = 0
        if (row > q.rows - 1) row = q.rows - 1
        if (col < 0) col = 0
        if (col > q.cols - 1) col = q.cols - 1
        return Cell(row, col)
    }

    // ------------------------------------------------------------------
    // Shared hex-drawing primitive
    // ------------------------------------------------------------------

    fun drawHex(
        ctx: dynamic,
        x: Double,
        y: Double,
        style: dynamic,
        terrain: Int? = null,
    ) = hexDrawer.draw(ctx, x, y, style, terrain)

    // ------------------------------------------------------------------
    // Canvas accessors / bounds
    // ------------------------------------------------------------------

    data class Bounds(
        val srow: Int,
        val scol: Int,
        val erow: Int,
        val ecol: Int,
    )

    fun getBounds(
        row: Int,
        col: Int,
        radius: Int,
        maxR: Int,
        maxC: Int,
    ): Bounds {
        if (radius < 0) return Bounds(0, 0, maxR, maxC)
        return Bounds(
            max(0, row - radius),
            max(0, col - radius),
            min(maxR, row + radius),
            min(maxC, col + radius),
        )
    }
}

/** Reads a DOM numeric layout property that Kotlin only sees as `dynamic`, defaulting to 0. */
private fun doubleOf(value: dynamic): Double = (value as? Number)?.toDouble() ?: 0.0

private fun RenderContext.applyTerrainImageSize(img: dynamic) {
    val lastHexRowHeight = RenderContext.LAST_HEX_ROW_HEIGHT
    mapWidth = (img.width as? Number)?.toDouble() ?: 0.0
    mapHeight = (img.height as? Number)?.toDouble() ?: 0.0

    mapCanvas.width = mapWidth.toInt()
    mapCanvas.height = (mapHeight + lastHexRowHeight).toInt()
    hexesCanvas.width = mapWidth.toInt()
    hexesCanvas.height = (mapHeight + lastHexRowHeight).toInt()
    cursorCanvas.width = mapWidth.toInt()
    cursorCanvas.height = (mapHeight + lastHexRowHeight).toInt()

    mapCanvas.style.width = "${mapWidth.toInt()}px"
    mapCanvas.style.height = "${(mapHeight + lastHexRowHeight).toInt()}px"
    hexesCanvas.style.width = "${mapWidth.toInt()}px"
    hexesCanvas.style.height = "${(mapHeight + lastHexRowHeight).toInt()}px"
    cursorCanvas.style.width = "${mapWidth.toInt()}px"
    cursorCanvas.style.height = "${(mapHeight + lastHexRowHeight).toInt()}px"

    mapCtx.imageSmoothingEnabled = false
    hexesCtx.imageSmoothingEnabled = false
    cursorCtx.imageSmoothingEnabled = false
    unitBackCtx.imageSmoothingEnabled = false
}

/** [RenderContext.cacheImages] step: the small set of fixed UI images (cursor/flag/fire/leader/bridge). */
private fun RenderContext.loadFixedImages(state: ImageLoadState) {
    state.loadOrWait(attackCursorImage, "resources/ui/cursors/attack.png") { attackCursorImage = it }
    state.loadOrWait(flagImage, "resources/ui/flags/${Equipment.UNITED_NAME}/flags_med.png") { flagImage = it }
    state.loadOrWait(fireImage, "resources/ui/indicators/unit-fire.png") { fireImage = it }
    state.loadOrWait(noAmmoImage, "resources/ui/indicators/unit-fire-no-ammo.png") { noAmmoImage = it }
    state.loadOrWait(leaderAxisImage, "resources/ui/indicators/unit-leader-axis.png") { leaderAxisImage = it }
    state.loadOrWait(
        leaderAlliedImage,
        "resources/ui/indicators/unit-leader-allied.png",
    ) { leaderAlliedImage = it }
    state.loadOrWait(bridgeImage, "resources/units/images/bridg.png") { bridgeImage = it }
    state.loadOrWait(craterImage, "resources/ui/indicators/hex-craters.png") { craterImage = it }
}

/** [RenderContext.cacheImages] step: the current map's terrain background image. */
private fun RenderContext.loadTerrainImage(state: ImageLoadState) {
    // No map yet (first cacheImages call at startup) means there is no terrain to load —
    // skip instead of pointing an Image at src="" (which fired a spurious onerror and
    // logged "failed to load terrain image" on every fresh launch).
    val terrainSrc = map?.terrainImage ?: ""
    if (terrainImage == null && terrainSrc.isNotEmpty()) {
        state.total++
        val img = js("new Image()")
        img.onload = {
            applyTerrainImageSize(img)
            positionLayers()
            state.checkDone()
        }
        img.onerror = {
            console.error("Render: failed to load terrain image")
            state.checkDone()
        }
        img.src = terrainSrc
        terrainImage = img
    } else {
        state.waitFor(terrainImage)
    }
}

/** Last `map|count` pair logged by [loadUnitImages], so an unchanged sprite set stays quiet. */
private var lastUnitImageSignature: String? = null

/** [RenderContext.cacheImages] step: every distinct unit sprite image referenced by the current map. */
private fun RenderContext.loadUnitImages(state: ImageLoadState) {
    val list = map?.getUnitImagesList() ?: js("{}")
    val keys = js("Object.keys(list)")
    val keyCount = (keys.length as? Number)?.toInt() ?: 0
    // Only when the set actually changes. cacheImages runs on every render pass that may have
    // introduced a sprite, so an unchanged map logged the same line three or four times per turn.
    val signature = "${map?.name}|$keyCount"
    if (signature != lastUnitImageSignature) {
        lastUnitImageSignature = signature
        console.log("[osada] Render.cacheImages map=${map?.name} unitImagesToLoad=$keyCount")
    }
    for (i in 0 until keyCount) {
        val key = keys[i] as? String
        val baseSrc = if (key == null) null else list[key] as? String
        val eqid = key?.toIntOrNull()
        if (eqid == null || baseSrc == null) continue
        val src = UnitIconResolver.forCurrentScenario(eqid, baseSrc)
        state.loadOrWait(unitImages[src], src) { unitImages[src] = it }
    }
}
