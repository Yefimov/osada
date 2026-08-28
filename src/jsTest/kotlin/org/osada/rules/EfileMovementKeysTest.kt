package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.rebuildSpottingForSightBlocker
import org.osada.model.recomputeSpotting
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Cut LOS` read on the line of SIGHT rather than only the line of fire
 * (`docs/og-fidelity-plan.md` §Z.4).
 *
 * This class also covered `reinf_move` and `allow_pontoon_ex` on 2026-08-28. Both were reverted
 * the same day (§AB): they are per-efile divergences in MOVEMENT, and equipment is merged into one
 * `eqp-united` database, so a unit moves the same distance whatever efile it came from.
 *
 * The one inference left here — `Allow LOF` exempting a record from blocking sight — is asserted
 * explicitly so a later correction fails loudly rather than drifting.
 */
class EfileMovementKeysTest : OgRulesTestHarness() {
    @BeforeTest
    fun setup() {
        installTestWorld()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    // ---- Cut LOS blocks sight -----------------------------------------------------------------

    @Test
    fun aCutLosUnitBlocksSightThroughItsHex() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val blocker = place(map, infantryEqid, 2, 3, 1)
        blocker.unitData(true).attr2 = ATTR2_CUT_LOS

        assertTrue(ExtendedLos.isSightBlocker(blocker))
        assertFalse(
            ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4),
            "the ability is named for the line of SIGHT and OG puts it there",
        )
    }

    @Test
    fun allowLofExemptsARecordFromBlockingSightToo() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val blocker = place(map, infantryEqid, 2, 3, 1)
        blocker.unitData(true).attr2 = ATTR2_CUT_LOS or ATTR2_ALLOW_LOF

        // INFERENCE, asserted so a correction is loud: a record carrying both bits is saying it
        // does not obstruct, and one ability must not contradict the other on the same unit.
        assertFalse(ExtendedLos.isSightBlocker(blocker))
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4))
    }

    @Test
    fun nothingBlocksSightWhileTheKeyIsOff() {
        ruleset(RuleKey.EXTENDED_LOS to 0)
        val map = world()
        GameHolder.instance = holderFor(map)
        val blocker = place(map, infantryEqid, 2, 3, 1)
        blocker.unitData(true).attr2 = ATTR2_CUT_LOS

        assertFalse(ExtendedLos.isSightBlocker(blocker), "no shipped scenario meets this by default")
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4))
    }

    @Test
    fun theFogIsRebuiltRatherThanStrandedWhenABlockerLeaves() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val scout = place(map, infantryEqid, 2, 2, 0)
        val blocker = place(map, infantryEqid, 2, 3, 1)
        blocker.unitData(true).attr2 = ATTR2_CUT_LOS
        map.recomputeSpotting()
        val masked = map.map!![2][4]
        assertFalse(masked.isSpotted(0), "the blocker masks the hex behind it")

        // The blocker walks away. This is the case the old narrowing was built to avoid: the
        // counters cannot express it, so the whole fog is re-derived instead.
        map.map!![2][3].delUnit(blocker)
        map.map!![5][5].setUnit(blocker)
        map.rebuildSpottingForSightBlocker(blocker)

        assertTrue(masked.isSpotted(0), "and the sight line comes back once it has gone")
        assertEquals(2, scout.getPos()?.row, "the scout has not moved")
    }

    @Test
    fun aPlainUnitNeverTriggersTheRebuild() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        val ordinary = place(map, infantryEqid, 2, 3, 1)

        assertFalse(ExtendedLos.isSightBlocker(ordinary))
        assertTrue(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4))
    }

    @Test
    fun terrainStillCutsSightOnItsOwn() {
        ruleset(RuleKey.EXTENDED_LOS to 1)
        val map = world()
        GameHolder.instance = holderFor(map)
        map.map!![2][3].terrain = TerrainType.MOUNTAIN.value

        // Bullet 1 is untouched by any of this.
        assertFalse(ExtendedLos.hasLineOfSight(map.map, 2, 2, 2, 4))
    }
}
