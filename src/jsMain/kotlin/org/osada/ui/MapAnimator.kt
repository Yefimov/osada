package org.osada.ui

import kotlinx.browser.window
import org.osada.model.GameUnit
import org.osada.model.MovementResults
import org.osada.rules.GameRules
import org.osada.rules.getDirection
import org.osada.uiSettings
import kotlin.math.abs

/**
 * Drives map animations: queued fire/explosion sprites on the cursor layer and the
 * unit-move slide using the off-screen unit back-buffer. Extracted from the former
 * `Render` god-class; owns the [AnimationChain] and renders moving sprites via
 * [UnitRenderer], using geometry/canvases from the shared [RenderContext].
 */
internal class MapAnimator(
    private val rc: RenderContext,
    private val unitRenderer: UnitRenderer,
) {
    companion object {
        // A single hex-to-hex move is animated over this many interpolation sub-steps.
        private const val MOVE_SUBSTEPS = 5
        private const val MOVE_SUBSTEPS_DOUBLE = 5.0
    }

    private val animationChain = AnimationChain()

    fun runAnimation(callback: dynamic) {
        animationChain.start(callback)
    }

    /**
     * Queues one canvas animation at [row]/[col].
     *
     * [unit] is optional and exists for one reason: OG picks the attack and destruction sound per
     * EQUIPMENT RECORD, so when the caller knows whose animation this is, the unit's own sound can
     * replace the animation's class sound (`ui/OgSoundLibrary`). Passing null -- and the shipped
     * state where no OG audio is present -- keeps exactly the class sound this always played.
     */
    fun addAnimation(
        row: Int,
        col: Int,
        type: String,
        direction: Int,
        unit: GameUnit? = null,
    ): Boolean {
        val factory = getAnimationSprite(type) ?: return false
        val sprite = factory()
        val pos = rc.cellToScreen(row, col, false)
        val x = (pos.x - sprite.width / 2.0 + rc.hexTopWidth / 2.0).toInt()
        val y = (pos.y - ((sprite.image.height as? Number)?.toDouble() ?: 0.0) / 2.0 + rc.v).toInt()
        val anim =
            Animation(
                rc.cursorCtx,
                x,
                y,
                sprite,
                directionToRadians[direction],
                soundOverride =
                    if (type == "explosion") {
                        OgSoundLibrary.deathSprite(unit)
                    } else {
                        OgSoundLibrary.attackSprite(unit)
                    },
            )
        animationChain.add(anim)
        return true
    }

    fun moveAnimation(params: dynamic) {
        val unit = params.unit as? GameUnit
        val moveResults = params.moveResults as? MovementResults
        if (unit == null || moveResults == null) return
        val callback = params.cbfunc

        val path = moveResults.passedCells
        if (path.isEmpty()) {
            invokeCbFunc(callback, params)
            return
        }

        val delay = if (uiSettings.quickAnimation == true) 5 else 30
        var step = 0
        var subStep = 0
        var pos = rc.cellToScreen(path[0].row, path[0].col, false)
        var dx = 0.0
        var dy = 0.0

        unit.hasAnimation = true

        var timer = 0
        timer =
            window.setInterval({
                if (step >= path.size - 1) {
                    window.clearInterval(timer)
                    unit.hasAnimation = false
                    rc.unitBackBuffer.style.display = "none"
                    invokeCbFunc(callback, params)
                    return@setInterval
                }

                if (subStep == 0) {
                    val cur = path[step]
                    val nxt = path[step + 1]
                    pos = rc.cellToScreen(cur.row, cur.col, false)
                    val nextPos = rc.cellToScreen(nxt.row, nxt.col, false)
                    dx = (nextPos.x - pos.x) / MOVE_SUBSTEPS_DOUBLE
                    dy = (nextPos.y - pos.y) / MOVE_SUBSTEPS_DOUBLE
                    val dir = GameRules.getDirection(cur.row, cur.col, nxt.row, nxt.col) ?: unit.facing
                    if (abs(dir - unit.facing) > 1) {
                        unit.facing = dir
                    }
                }

                renderUnitBackBuffer(unit)
                pos.x += dx
                pos.y += dy
                positionUnitBackBuffer(pos)

                subStep++
                if (subStep >= MOVE_SUBSTEPS) {
                    subStep = 0
                    step++
                }
            }, delay)
    }

    /** Clears and redraws the moving unit into the off-screen unit back-buffer, revealing it. */
    private fun renderUnitBackBuffer(unit: GameUnit) {
        val ubw = (rc.unitBackBuffer.width as? Number)?.toDouble() ?: 120.0
        val ubh = (rc.unitBackBuffer.height as? Number)?.toDouble() ?: 120.0
        rc.unitBackCtx.clearRect(0.0, 0.0, ubw, ubh)
        unitRenderer.drawUnit(rc.unitBackCtx, -rc.ba, -rc.ca, unit, false)
        if (rc.unitBackBuffer.style.display == "none") {
            rc.unitBackBuffer.style.display = "inline"
        }
    }

    /** Positions the unit back-buffer at [pos], using a 3D transform or top/left per settings. */
    private fun positionUnitBackBuffer(pos: dynamic) {
        if (uiSettings.use3D == true) {
            val tx = "translate3d(${pos.x + rc.ba}px, ${pos.y + rc.ca}px, 0)"
            rc.unitBackBuffer.style.transition = "linear"
            rc.unitBackBuffer.style.transform = tx
        } else {
            rc.unitBackBuffer.style.top = "${pos.y + rc.ca}px"
            rc.unitBackBuffer.style.left = "${pos.x + rc.ba}px"
        }
    }
}

/**
 * Safely calls `callback.cbfunc(params)` when both [callback] and its
 * `cbfunc` property exist.
 *
 * Why this exists: `callback?.cbfunc?.let { it(params) }` fails to
 * compile under Kotlin/JS with `Unresolved reference 'it'` — `dynamic`
 * does not propagate through chained safe-calls (`?.`) combined with
 * `.let { }` the way a statically-typed nullable would. Routing the
 * call through a plain function parameter sidesteps the inference
 * problem entirely, since `dynamic` parameters are always resolvable.
 */
fun invokeCbFunc(
    callback: dynamic,
    params: dynamic,
) {
    if (callback == null || callback == undefined) return
    val fn = callback.cbfunc
    if (fn == null || fn == undefined) return
    fn(params)
}
