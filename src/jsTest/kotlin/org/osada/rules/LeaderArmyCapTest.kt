package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.model.OgLeaderAward
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OG's *"If there are already 2 leaders of any type ... type is skipped"*, per army. */
class LeaderArmyCapTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() = installTestWorld()

    @AfterTest
    fun teardown() = clearTestWorld()

    @Test
    fun aThirdLeaderOfTheSameTypeIsNotRolledInOneArmy() {
        val map = world()
        GameHolder.instance = holderFor(map)
        place(map, infantryEqid, 1, 1, 0).leader = LeaderType.AGGRESSIVE_ATTACK.value
        val candidate = place(map, infantryEqid, 3, 3, 0)
        assertTrue(LeaderType.AGGRESSIVE_ATTACK in OgLeaderAward.pool(candidate), "one is not yet the cap")

        place(map, infantryEqid, 5, 5, 0).leader = LeaderType.AGGRESSIVE_ATTACK.value
        assertFalse(LeaderType.AGGRESSIVE_ATTACK in OgLeaderAward.pool(candidate), "two is the cap")

        val enemy = place(map, infantryEqid, 7, 7, 1)
        assertTrue(LeaderType.AGGRESSIVE_ATTACK in OgLeaderAward.pool(enemy), "the cap is per army")
    }
}
