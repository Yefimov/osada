package org.osada.rules

import org.osada.LeaderType
import org.osada.model.EfileConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `upgrade_ldr`, read 2026-08-28 (`docs/og-fidelity-plan.md` §AA.7).
 *
 * Inert on every shipped efile — `eqp-lxf` writes the documented default — so this is the only
 * place its two non-default modes run at all.
 *
 * This class also covered `elite_cost` (GCE's 133%), reverted the same day (§AB): a per-efile
 * divergence in COST, and equipment is merged into one `eqp-united` database, so a strength point
 * costs the same whatever efile the unit came from.
 */
class EfileEconomyKeysTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    // ---- upgrade_ldr --------------------------------------------------------------------------

    @Test
    fun theDefaultKeepsTheCommander() {
        EfileConfig.setForTest(intKeyMap = emptyMap())
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)
        unit.leader = LeaderType.BRIDGING.value
        unit.experience = 300

        LeaderOnUpgrade.afterUpgrade(unit)

        // `eqp-lxf` writes 0 explicitly, which is this: OSADA's existing behaviour.
        assertEquals(LeaderType.BRIDGING.value, unit.leader)
        assertEquals(300, unit.experience)
    }

    @Test
    fun modeOneRerollsAndChargesABar() {
        EfileConfig.setForTest(intKeyMap = mapOf("upgrade_ldr" to 1))
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)
        unit.leader = LeaderType.BRIDGING.value
        unit.experience = 300

        LeaderOnUpgrade.afterUpgrade(unit)

        assertEquals(200, unit.experience, "'losing 1 bar'")
    }

    @Test
    fun modeTwoRemovesTheCommander() {
        EfileConfig.setForTest(intKeyMap = mapOf("upgrade_ldr" to 2))
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)
        unit.leader = LeaderType.BRIDGING.value
        unit.experience = 500

        LeaderOnUpgrade.afterUpgrade(unit)

        assertEquals(-1, unit.leader)
        assertNotEquals(500, unit.experience, "'reducing unit's exp ... to be able to get a new leader'")
    }

    @Test
    fun aFormationWithNoCommanderIsUntouchedByAnyMode() {
        EfileConfig.setForTest(intKeyMap = mapOf("upgrade_ldr" to 2))
        val map = world()
        val unit = place(map, infantryEqid, 2, 2, 0)
        unit.leader = -1
        unit.experience = 450

        LeaderOnUpgrade.afterUpgrade(unit)

        assertEquals(450, unit.experience)
    }
}
