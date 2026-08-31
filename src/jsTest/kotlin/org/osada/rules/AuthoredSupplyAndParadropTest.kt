package org.osada.rules

import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.scenario.Scenario
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two more of OG's `@1009` "what a placed unit starts with" options, wired 2026-08-31:
 * **`opt_no_paradrop_ocean`** (19 deployed scenarios) here, and the authored fuel/ammo of
 * `opt_use_fuel` / `opt_use_ammo` (94 and 98) in `ScenarioUnitParser`.
 *
 * The paradrop half is the interesting one, because the option exists precisely because the drop is
 * otherwise legal — and it was legal in OSADA too: `EmbarkRules.getDisembarkPositions` filters
 * candidate hexes with the TRANSPORT's movement table, and an aircraft's makes ocean passable.
 */
class AuthoredSupplyAndParadropTest : OgRulesTestHarness() {
    private val transportEqid = 941_001
    private val paratroopEqid = 941_002

    @BeforeTest
    fun setup() {
        installTestWorld()
        ruleset()
        Equipment.putEquipment(
            transportEqid,
            EquipmentData().apply {
                name = "Ju 52"
                uclass = UnitClass.AIR_TRANSPORT.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
            },
        )
        Equipment.putEquipment(
            paratroopEqid,
            EquipmentData().apply {
                name = "Fallschirmjager"
                uclass = UnitClass.INFANTRY.value
                movmethod = MovMethod.LEG.value
                movpoints = 3
            },
        )
    }

    @AfterTest
    fun teardown() = clearTestWorld()

    private fun scenarioWith(
        map: GameMap,
        configure: Scenario.() -> Unit,
    ): Scenario {
        val scenario = Scenario(null).apply { this.map = map }.apply(configure)
        GameHolder.instance = GameHolder.instance ?: org.osada.Game()
        GameHolder.instance?.scenario = scenario
        return scenario
    }

    /**
     * An 8x8 sea with one transport in the middle, so every neighbouring hex is ocean.
     *
     * The transport is returned rather than read back off the hex: an aircraft occupies
     * `Hex.airunit`, not `Hex.unit`.
     */
    private fun seaWithTransport(): Pair<GameMap, GameUnit> {
        val map = world()
        for (r in 0 until map.rows) {
            for (c in 0 until map.cols) map.map!![r][c].terrain = TerrainType.OCEAN.value
        }
        map.map!![3][3].terrain = TerrainType.CLEAR.value
        return map to place(map, transportEqid, 3, 3, 0)
    }

    /** Default: the drop is legal, which is why OG needed an option to forbid it. */
    @Test
    fun withoutTheOptionAParatroopDropIntoTheSeaIsStillOffered() {
        val (map, transport) = seaWithTransport()
        scenarioWith(map) { }

        assertTrue(
            EmbarkRules.getDisembarkPositions(map, transport).isNotEmpty(),
            "an aircraft's movement table makes ocean passable, so the sea is on offer",
        )
    }

    /** *"avoid paratroop drops on ocean"* — with the option authored, nowhere at sea is offered. */
    @Test
    fun theOptionRemovesEveryOceanHexFromTheDropList() {
        val (map, transport) = seaWithTransport()
        scenarioWith(map) { paradropOnOceanAllowed = false }

        assertTrue(
            EmbarkRules.getDisembarkPositions(map, transport).isEmpty(),
            "every neighbour of (3,3) is ocean, and none of them may take the drop",
        )
    }

    /** Land beside the transport is unaffected — the option removes ocean, not the mechanic. */
    @Test
    fun landHexesAreStillOfferedWithTheOptionOn() {
        val (map, transport) = seaWithTransport()
        map.map!![3][4].terrain = TerrainType.CLEAR.value
        scenarioWith(map) { paradropOnOceanAllowed = false }

        val offered = EmbarkRules.getDisembarkPositions(map, transport)
        assertTrue(offered.any { it.row == 3 && it.col == 4 }, "the one clear hex is still a landing zone")
        assertFalse(offered.any { it.row == 3 && it.col == 2 }, "and its ocean neighbour is not")
    }

    /** An absent attribute means permitted, which is what the 105 unreadable-source scenarios get. */
    @Test
    fun anUnauthoredScenarioKeepsTheDrop() {
        val (map, transport) = seaWithTransport()
        scenarioWith(map) { paradropOnOceanAllowed = null }

        assertTrue(EmbarkRules.getDisembarkPositions(map, transport).isNotEmpty())
    }
}
