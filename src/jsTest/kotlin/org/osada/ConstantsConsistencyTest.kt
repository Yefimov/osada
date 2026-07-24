package org.osada

import org.osada.rules.GameRules
import org.osada.rules.distance
import org.osada.ui.attackAnimationByClass
import org.osada.ui.getAnimationSprite
import org.osada.ui.moveSoundByMoveMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests that Kotlin constants and enums match the values used in openpanzer-legacy-2.3.14.js.
 * These tests guard against accidental drift between the Kotlin port and the reference JS.
 */
class ConstantsConsistencyTest {
    @Test
    fun versionMatchesLegacy() {
        // Bumped for the eqp-united equipment merge (see Constants.kt) -- this pins the current
        // value rather than checking against openpanzer-legacy-2.3.14.js like the other tests
        // in this file, so it's expected to move whenever VERSION is intentionally bumped.
        assertEquals("3.3.0", VERSION)
    }

    @Test
    fun unitMaxExperienceMatchesLegacy() {
        assertEquals(500, UNIT_MAX_EXPERIENCE)
    }

    @Test
    fun keyUnitClassValuesMatchLegacyJs() {
        assertEquals(0, UnitClass.NONE.value)
        assertEquals(1, UnitClass.INFANTRY.value)
        assertEquals(2, UnitClass.TANK.value)
        assertEquals(8, UnitClass.ARTILLERY.value)
        assertEquals(10, UnitClass.FIGHTER.value)
        assertEquals(13, UnitClass.AIR_TRANSPORT.value)
        assertEquals(21, UnitClass.LIGHT_CRUISER.value)
    }

    @Test
    fun keyTerrainTypeValuesMatchLegacyJs() {
        assertEquals(0, TerrainType.CLEAR.value)
        assertEquals(1, TerrainType.CITY.value)
        assertEquals(3, TerrainType.FOREST.value)
        assertEquals(11, TerrainType.FORTIFICATION.value)
        assertEquals(15, TerrainType.IMPASSABLE_RIVER.value)
    }

    @Test
    fun keyPlayerTypeValuesMatchLegacyJs() {
        assertEquals(0, PlayerType.HUMAN_LOCAL.value)
        assertEquals(2, PlayerType.AI_LOCAL.value)
        assertEquals(4, PlayerType.AI_SCRIPTED.value)
    }

    @Test
    fun actionTypeValuesMatchLegacyJs() {
        assertEquals(0, ActionType.MOVE.value)
        assertEquals(1, ActionType.ATTACK.value)
        assertEquals(10, ActionType.END_TURN.value)
    }

    @Test
    fun movementTablesHaveExpectedDimensions() {
        // 13 movement methods since OSADA added RAIL(12) for armored trains (Constants.kt);
        // the legacy reference JS only covers the first 12 (see movementTablesMatchLegacyReferenceValues).
        assertEquals(13, movTableDry.size, "movTableDry should have 13 movement methods")
        assertEquals(18, movTableDry[0].size, "movTableDry rows should have 18 terrains")
        assertEquals(13, movTableFrozen.size)
        assertEquals(18, movTableFrozen[0].size)
        assertEquals(13, movTableMud.size)
        assertEquals(18, movTableMud[0].size)
    }

    @Test
    fun movementTablesMatchLegacyReferenceValues() {
        // A few reference cells from openpanzer-legacy-2.3.14.js movTableDry
        assertEquals(1, movTableDry[0][0]) // tracked, clear
        assertEquals(254, movTableDry[0][6]) // tracked, mountain
        assertEquals(255, movTableDry[0][9]) // tracked, ocean
        assertEquals(254, movTableDry[2][4]) // wheeled, bocage
        assertEquals(4, movTableDry[0][4]) // tracked, bocage
    }

    @Test
    fun currencyAndPrestigeConstantsMatchLegacy() {
        assertEquals(12, CURRENCY_MULTIPLIER)
        assertEquals(2000, SCENARIO_START_PRESTIGE)
        assertEquals(200, PROTOTYPE_MIN_COST)
    }

    @Test
    fun gameRulesDistanceIsHexDistance() {
        // distance between (0,0) and (1,0) should be 1
        assertEquals(1, GameRules.distance(0, 0, 1, 0))
        // same cell
        assertEquals(0, GameRules.distance(5, 5, 5, 5))
    }

    @Test
    fun leadersEnumHasExpectedValues() {
        assertEquals(1, LeaderType.MECHANIZED_VETERAN.value)
        assertEquals(26, LeaderType.OVERWHELMING_ATTACK.value)
        assertEquals(33, LeaderType.SUPERIOR_MANEUVER.value)
    }

    @Test
    fun soundMoveMappingMatchesLegacyLength() {
        assertEquals(12, moveSoundByMoveMethod.size)
    }

    @Test
    fun attackAnimationMappingMatchesLegacyLength() {
        assertEquals(22, attackAnimationByClass.size)
    }

    @Test
    fun knownAnimationsAreRegistered() {
        assertNotNull(getAnimationSprite("explosion"))
        assertNotNull(getAnimationSprite("gun"))
        assertNotNull(getAnimationSprite("smallgun"))
        assertNotNull(getAnimationSprite("infantry"))
        assertNotNull(getAnimationSprite("tank"))
        assertNotNull(getAnimationSprite("bomber"))
    }
}
