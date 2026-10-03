package org.osada.multiplayer.coop

import org.osada.multiplayer.ui.CoopScenarioAdvice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoopScenarioAdviceTest {
    @Test fun adviceCountsOnlyChosenOpeningArmyAndReserve() {
        val source = """<map><hex><unit owner="0"/></hex><hex><unit owner="1"/></hex>
            <reserve><unit owner="0"/><unit owner="1"/></reserve>
            <reinforce turn="3"><at><unit owner="0"/></at></reinforce>
            <events><spawn><unit owner="0"/></spawn></events></map>"""
        assertEquals(CoopScenarioAdvice.Opening(1, 1), CoopScenarioAdvice.count(source, 0))
        assertEquals(CoopScenarioAdvice.Opening(1, 1), CoopScenarioAdvice.count(source, 1))
        assertEquals(CoopScenarioAdvice.Opening(0, 0), CoopScenarioAdvice.count(source, 2))
        assertNull(CoopScenarioAdvice.count("<map><broken>", 0))
    }
}
