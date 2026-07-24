package org.osada

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FixtureSanityTest {
    @Test
    fun fixtureParsesAndHasScenario() {
        assertTrue(BIZERTE_SAVE_JSON.startsWith("{\"scenario\":"))
        val parsed = JSON.parse<dynamic>(BIZERTE_SAVE_JSON)
        assertNotEquals(undefined, parsed.scenario)
        assertEquals("Bizerte", parsed.scenario.name as String)
    }
}
