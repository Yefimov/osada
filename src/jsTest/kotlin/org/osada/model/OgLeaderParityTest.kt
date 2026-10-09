package org.osada.model

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.rules.GameRandomSource
import org.osada.rules.MovementRules
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 2026-10-09 OG parity pass on legacy leaders: naval class signatures, Skilled
 *  Reconnaissance's +2 and OG's per-unit / army award skips ([OgLeaderAward]). */
class OgLeaderParityTest {
    private val subEqid = 911
    private val carrierEqid = 912
    private val lightCruiserEqid = 913
    private val battleshipEqid = 914
    private val infantryEqid = 915
    private val mountainEqid = 916
    private val bunkerEqid = 917

    private var holderBefore: org.osada.Game? = null

    @BeforeTest
    fun setup() {
        EfileConfig.setForTest()
        GameRandomSource.resetForTest()
        Equipment.resetEquipment()
        holderBefore = GameHolder.instance
        GameHolder.instance = null

        fun put(
            eqid: Int,
            cls: UnitClass,
            init: EquipmentData.() -> Unit = {},
        ) = Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = cls.value
                movpoints = 3
                spotrange = 2
                init()
            },
        )
        put(subEqid, UnitClass.SUBMARINE)
        put(carrierEqid, UnitClass.CARRIER)
        put(lightCruiserEqid, UnitClass.LIGHT_CRUISER)
        put(battleshipEqid, UnitClass.BATTLESHIP)
        put(infantryEqid, UnitClass.INFANTRY)
        put(mountainEqid, UnitClass.INFANTRY) { movmethod = MovMethod.ALL_TERRAIN_LEG.value }
        put(bunkerEqid, UnitClass.FORTIFICATION) { movpoints = 0 }
    }

    @AfterTest
    fun teardown() {
        GameHolder.instance = holderBefore
        EfileConfig.resetForTest()
    }

    private fun led(
        eqid: Int,
        individual: LeaderType = LeaderType.AGGRESSIVE_ATTACK,
    ) = GameUnit(eqid).apply { leader = individual.value }

    @Test
    fun eachWarshipCarriesItsOwnOgSignatureAndTheTraitItStandsFor() {
        assertTrue(Leaders.unitHasLeader(led(subEqid), LeaderType.EXPERT_SEA_WOLF))
        assertTrue(Leaders.unitHasLeader(led(subEqid), LeaderType.SUPERIOR_MANEUVER), "Sea Wolf ignores ZOC")
        assertTrue(Leaders.unitHasLeader(led(carrierEqid), LeaderType.RESILIENCE), "Sea Base is resilience")
        assertTrue(
            Leaders.unitHasLeader(led(lightCruiserEqid), LeaderType.BATTLEFIELD_INTELLIGENCE),
            "Screening Vessel cannot be surprised",
        )
        assertTrue(Leaders.unitHasLeader(led(battleshipEqid), LeaderType.MARKSMAN), "Blockade Vessel keeps +1 range")
        assertFalse(Leaders.unitHasLeader(led(subEqid), LeaderType.MARKSMAN), "a submarine is no longer a Marksman")
        assertFalse(
            Leaders.unitHasLeader(GameUnit(subEqid), LeaderType.SUPERIOR_MANEUVER),
            "no leader, no signature",
        )
    }

    @Test
    fun aCarrierNeverRollsTheResilienceItAlreadyHas() {
        assertFalse(LeaderType.RESILIENCE in OgLeaderPools.randomPool(UnitClass.CARRIER.value))
    }

    @Test
    fun skilledReconnaissanceAddsTwoHexesOfSpotting() {
        val plain = GameUnit(infantryEqid)
        val scout = led(infantryEqid, LeaderType.SKILLED_RECONNAISSANCE)
        assertEquals(MovementRules.getUnitSpotRange(plain) + 2, MovementRules.getUnitSpotRange(scout))
    }

    @Test
    fun ogsUnitSkipsApply() {
        val mountain = GameUnit(mountainEqid)
        assertTrue(OgLeaderAward.skippedFor(mountain, LeaderType.ALPINE_TRAINING), "mountain troops")
        assertFalse(OgLeaderAward.skippedFor(GameUnit(infantryEqid), LeaderType.ALPINE_TRAINING))
        val bunker = GameUnit(bunkerEqid)
        listOf(LeaderType.LIBERATOR, LeaderType.INFILTRATION_TACTICS, LeaderType.BATTLEFIELD_INTELLIGENCE)
            .forEach { assertTrue(OgLeaderAward.skippedFor(bunker, it), "an immobile bunker skips $it") }
        assertFalse(LeaderType.ALPINE_TRAINING in OgLeaderAward.pool(mountain))
    }
}
