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
        private const val ICONSET_JUNGLE = 3

        // The terrain image is 65px shorter than the canvases, which get +65 for the last hex row.
        internal const val LAST_HEX_ROW_HEIGHT = 65.0
        private const val Z_INDEX_UNIT_BACKBUFFER = 3

        // Top-bar height the map area starts below (matches StrategicZoomController.topbarHeight / #game's
        // own `top`/#statusbar's CSS height).
        private const val TOPBAR_HEIGHT = 30.0
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

    /** Thematic tint of the terrain background for the scenario's OG iconset (snow/desert/jungle).
     *  A cheap, reversible CSS filter — an approximation until real OG tilesets are rendered. */
    fun setIconsetTint(iconset: Int) {
        val mc = mapCanvas ?: return
        mc.style.filter =
            when (iconset) {
                1 -> "saturate(0.55) brightness(1.18)" // Snow — washed-out, brighter
                2 -> "sepia(0.45) saturate(1.3) brightness(1.05)" // Desert — sandy
                ICONSET_JUNGLE -> "saturate(1.45) brightness(0.95) hue-rotate(-8deg)" // Jungle — lush green
                else -> "none"
            }
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
        console.log(
            "[osada] Render.positionLayers mapSize=${zw.toInt()}x${zh.toInt()} zoom=$zoom " +
                "gameWidth=${game.style.width} gameHeight=${game.style.height} left=${left.toInt()}",
        )

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

        val fitW = window.innerWidth >= scaledW
        val fitH = window.innerHeight >= scaledH + 30.0
        val g = if (fitW) 0.0 else 10.0
        val k = if (fitH) 0.0 else 30.0

        game.style.width = if (fitW) "${(scaledW + k).toInt()}px" else "${window.innerWidth}px"
        game.style.height =
            if (fitH) {
                "${(scaledH + TOPBAR_HEIGHT + g).toInt()}px"
            } else {
                "${(window.innerHeight - TOPBAR_HEIGHT).toInt()}px"
            }
        game.style.position = "absolute"
        game.style.left = "${left.toInt()}px"
        game.style.top = "30px"
        game.tabIndex = 1
        game.focus()
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
            if (src != null) valid.add(src)
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
            val game = document.getElementById("game")?.asDynamic()
            if (game != null) {
                // mapWidth*zoom matches positionLayers' own centering calc (same formula,
                // scaledW there) — #game is centered based on its RENDERED (post-zoom) size.
                val left = window.innerWidth / 2.0 - mapWidth * zoom / 2.0
                x += left - (game.clientLeft as? Number)?.toDouble()!! - (game.offsetLeft as? Number)?.toDouble()!!
                y +=
                    TOPBAR_HEIGHT - (game.clientTop as? Number)?.toDouble()!! -
                    (game.offsetTop as? Number)?.toDouble()!!
            }
            x *= zoom
            y *= zoom
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
}

/** [RenderContext.cacheImages] step: the current map's terrain background image. */
private fun RenderContext.loadTerrainImage(state: ImageLoadState) {
    // No map yet (first cacheImages call at startup) means there is no terrain to load —
    // skip instead of pointing an Image at src="" (which fired a spurious onerror and
    // logged "failed to load terrain image" on every fresh launch).
    val terrainSrc = (map?.terrainImage as? String) ?: ""
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

/** [RenderContext.cacheImages] step: every distinct unit sprite image referenced by the current map. */
private fun RenderContext.loadUnitImages(state: ImageLoadState) {
    val list = map?.getUnitImagesList() ?: js("{}")
    val keys = js("Object.keys(list)")
    val keyCount = (keys.length as? Number)?.toInt() ?: 0
    console.log("[osada] Render.cacheImages map=${map?.name} unitImagesToLoad=$keyCount")
    for (i in 0 until keyCount) {
        val key = keys[i] as? String
        val src = if (key == null) null else list[key] as? String
        if (src == null) continue
        state.loadOrWait(unitImages[src], src) { unitImages[src] = it }
    }
}
