package org.osada.ai

import org.osada.model.GameMap
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.allocMap
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Basic tests for the AI handler instantiation and empty-map behavior.
 */
class AITest {
    @Test
    fun aiCanBeInstantiatedWithEmptyMap() {
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
                prestige = 0
            }
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
                addPlayer(player)
            }
        val ai = AI(player, map)
        assertNotNull(ai)
    }

    @Test
    fun aiReturnsNullActionsWhenNoUnits() {
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
                prestige = 0
            }
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
                addPlayer(player)
            }
        val ai = AI(player, map)
        ai.buildActions()
        assertNull(ai.getAction())
    }

    @Test
    fun scriptedAiCanBeInstantiatedWithEmptyMap() {
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
                prestige = 0
            }
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                turn = 5
                allocMap()
                addPlayer(player)
            }
        val scripted = AIScripted(player, map)
        assertNotNull(scripted)
        // The scripted AI only emits actions for the tutorial's first few turns;
        // on an empty non-tutorial map it should produce no actions.
        assertNull(scripted.getAction())
    }

    @Test
    fun aiBuildActionsClearsPreviousActions() {
        val player =
            Player().apply {
                id = 0
                side = 0
                country = 0
                prestige = 0
            }
        val map =
            GameMap().apply {
                rows = 4
                cols = 4
                allocMap()
                addPlayer(player)
            }
        val ai = AI(player, map)
        ai.buildActions()
        assertNull(ai.getAction())
        // Calling buildActions again should remain stable.
        ai.buildActions()
        assertNull(ai.getAction())
    }
}
