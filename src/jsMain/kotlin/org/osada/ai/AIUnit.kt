package org.osada.ai

import org.osada.model.Cell
import org.osada.model.GameUnit

/** Per-turn planning state for one AI-controlled unit. */
internal class AIUnit(val unit: GameUnit) {
    var didMove: Boolean = false
    var didAttack: Boolean = false
    var didResupplyReinforce: Boolean = false
    var noMove: Boolean = false
    var noAttack: Boolean = false
    var noResupply: Boolean = false
    var noReinforce: Boolean = false
    var newPosition: Cell? = null
}
