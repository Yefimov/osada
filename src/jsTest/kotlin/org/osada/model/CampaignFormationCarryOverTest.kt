package org.osada.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampaignFormationCarryOverTest {
    @Test
    fun playerOwnedFormationPersistsByDefault() {
        val player = Player().apply { id = 0 }
        val unit = GameUnit(0).apply { owner = 0 }

        assertTrue(unit.isCampaignPersistentFor(player))
    }

    @Test
    fun explicitTemporaryAndDestroyedUnitsDoNotPersist() {
        val player = Player().apply { id = 0 }
        val temporary = GameUnit(0).apply {
            owner = 0
            isTemporaryBorrowed = true
        }
        val destroyed = GameUnit(0).apply {
            owner = 0
            this.destroyed = true
        }
        val noDossier = GameUnit(0).apply {
            owner = 0
            nodossier = true
        }

        assertFalse(temporary.isCampaignPersistentFor(player))
        assertFalse(destroyed.isCampaignPersistentFor(player))
        assertFalse(noDossier.isCampaignPersistentFor(player))
    }

    @Test
    fun enemyFormationNeverJoinsThePlayersReserve() {
        val player = Player().apply { id = 0 }
        val enemy = GameUnit(0).apply { owner = 1 }

        assertFalse(enemy.isCampaignPersistentFor(player))
    }
}
