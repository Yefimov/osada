package org.osada.ui

import kotlinx.browser.document
import kotlinx.browser.window
import org.osada.model.ExtendedCell
import org.osada.model.GameUnit
import org.osada.model.MovementResults
import org.osada.uiSettings
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimationLifecycleTest {
    private val chain = AnimationChain()
    private var animator: MapAnimator? = null
    private var previousQuickAnimation = false

    @BeforeTest
    fun setup() {
        previousQuickAnimation = uiSettings.quickAnimation
        uiSettings.quickAnimation = true
    }

    @AfterTest
    fun tearDown() {
        chain.clear()
        animator?.cancel()
        uiSettings.quickAnimation = previousQuickAnimation
    }

    @Test
    fun clearingPendingCombatCancelsItsCompletion(): Promise<Unit> {
        var completed = false
        chain.add(animation())
        chain.start(callback { completed = true })
        chain.clear()
        return after(600) { assertFalse(completed, "An abandoned combat must not check victory") }
    }

    @Test
    fun nextCombatQueuedByCompletionIsNotClearedByPreviousCombat(): Promise<Unit> {
        var completed = 0
        chain.add(animation())
        chain.start(
            callback {
                completed++
                chain.add(animation())
                chain.start(callback { completed++ })
            },
        )
        return after(1200) { assertEquals(2, completed) }
    }

    @Test
    fun combatStartedDuringABarrageStillCompletesAndReleasesTheTurn(): Promise<Unit> {
        // An interception or barrage plays without a callback; the next order starts before it ends.
        var waitUIAnimation = true
        chain.add(animation())
        chain.start(null)
        chain.add(animation())
        chain.start(callback { waitUIAnimation = false })
        return after(1600) { assertFalse(waitUIAnimation, "the combat's completion was lost") }
    }

    @Test
    fun overlappingRunsPlayInOrderAndEachCallsBackOnce(): Promise<Unit> {
        val order = mutableListOf<String>()
        chain.add(animation())
        chain.start(callback { order += "interception" })
        chain.add(animation())
        chain.start(callback { order += "combat" })
        return after(1600) { assertEquals(listOf("interception", "combat"), order) }
    }

    @Test
    fun teardownDropsQueuedRunsToo(): Promise<Unit> {
        var completed = 0
        chain.add(animation())
        chain.start(callback { completed++ })
        chain.add(animation())
        chain.start(callback { completed++ })
        chain.clear()
        return after(1600) { assertEquals(0, completed) }
    }

    @Test
    fun cancelledMovementReleasesUnitWithoutFinishingOrder(): Promise<Unit> {
        val context = RenderContext(null)
        val moves = MapAnimator(context, UnitRenderer(context))
        animator = moves
        val unit = GameUnit(0)
        val result =
            MovementResults().apply {
                passedCells.add(ExtendedCell(0, 0))
                passedCells.add(ExtendedCell(0, 1))
            }
        var completed = false
        moves.moveAnimation(
            jsObject {
                this.unit = unit
                this.moveResults = result
                this.cbfunc = callback { completed = true }
            },
        )
        assertTrue(unit.hasAnimation)
        moves.cancel()
        assertFalse(unit.hasAnimation)
        return after(100) {
            assertFalse(completed)
            assertEquals("none", context.unitBackBuffer.style.display as String)
        }
    }

    private fun animation(): Animation {
        val canvas = document.createElement("canvas").asDynamic()
        return Animation(canvas.getContext("2d"), 0, 0, Sprite(canvas, 1, 0, Sound.dummy))
    }

    private fun callback(action: () -> Unit): dynamic {
        val fn: (dynamic) -> Unit = { action() }
        return jsObject { cbfunc = fn }
    }

    private fun after(
        delay: Int,
        assertion: () -> Unit,
    ): Promise<Unit> =
        Promise { resolve, reject ->
            window.setTimeout({
                try {
                    assertion()
                    resolve(Unit)
                } catch (error: Throwable) {
                    reject(error)
                }
            }, delay)
        }
}
