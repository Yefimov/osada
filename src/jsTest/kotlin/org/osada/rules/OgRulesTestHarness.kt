package org.osada.rules

import org.osada.Game
import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.EfileConfig
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import org.osada.scenario.Scenario
import kotlin.js.JSON

/** OG's `Dismount`, attr bit 11. */
internal const val ATTR_DISMOUNT = 2048

/** OG's `Recon Skill` (attr bit 10) and `Overrun toggle` (attrEx bit 3). */
internal const val ATTR_RECON_SKILL = 1024
internal const val ATTR_EX_OVERRUN = 8

/** A crossing bridged in two directions only, so a restored FULL mask is detectable. */
internal const val PARTIAL_ROAD_MASK = 17

/** OG's `Cut LOS`, `Allow LOF` and `No ZOC` — attr2 bits 4, 5 and 6. */
internal const val ATTR2_CUT_LOS = 16
internal const val ATTR2_ALLOW_LOF = 32
internal const val ATTR2_NO_ZOC = 64

/**
 * The 8x8 clear map, four synthetic equipment records and two opposed players that
 * [OgOptionalRulesTest] and [OgEngineeringRulesTest] both build on.
 *
 * Shared through a base class rather than duplicated because the two classes are one audit split
 * in half: `Build and Repair` (OG 9.3) grew past detekt's class-size budget on 2026-08-26 and moved
 * out, and a second copy of this fixture would let the two halves drift into testing subtly
 * different worlds. The `@BeforeTest`/`@AfterTest` hooks stay in the subclasses and call
 * [installTestWorld]/[clearTestWorld] explicitly, rather than being inherited — one line each, and
 * it does not depend on how the Kotlin/JS test adapter treats an annotation on a base-class method.
 */
abstract class OgRulesTestHarness {
    protected val sapperEqid = 940
    protected val gunEqid = 941
    protected val infantryEqid = 942
    protected val truckEqid = 943

    protected val friendly =
        Player().apply {
            id = 0
            side = 0
        }

    protected val hostile =
        Player().apply {
            id = 1
            side = 1
        }

    protected fun installTestWorld() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        // The efile's own `equip.cfg` decides some of what these rules do (`blow_any_terrain`), and
        // a leaked key would make one test's efile another test's world.
        EfileConfig.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            sapperEqid,
            EquipmentData().apply {
                name = "Pioneer Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 6
                // OG's `Can Blow` (attr bit 1) and `Build/Repair` (attr2 bit 0).
                attr = 2
                attr2 = 1
            },
        )
        Equipment.putEquipment(
            gunEqid,
            EquipmentData().apply {
                name = "Heavy Battery"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
                gunrange = 3
                ammo = 8
                softatk = 12
                hardatk = 8
                grounddef = 3
                // OG's `Counter Battery`, SpecialEx 61.1 / attrEx bit 9.
                attrEx = 512
            },
        )
        Equipment.putEquipment(
            infantryEqid,
            EquipmentData().apply {
                name = "Rifle Division"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 8
                softatk = 6
                grounddef = 5
                spotrange = 4
            },
        )
        Equipment.putEquipment(
            truckEqid,
            EquipmentData().apply {
                name = "Truck"
                uclass = UnitClass.GROUND_TRANSPORT.value
                movmethod = MovMethod.WHEELED.value
                movpoints = 8
                grounddef = 2
            },
        )
    }

    protected fun clearTestWorld() {
        TerrainEx.resetForTest()
        ActiveRuleset.resetForTest()
        EfileConfig.resetForTest()
        GameHolder.instance = null
    }

    protected fun ruleset(vararg overrides: Pair<RuleKey, Int>) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "custom-1",
                name = "Test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + overrides.toMap(),
            ),
        )
    }

    protected fun world(prestige: Int = 0): GameMap =
        GameMap().apply {
            rows = 8
            cols = 8
            allocMap()
            friendly.prestige = prestige
            addPlayer(friendly)
            addPlayer(hostile)
            currentPlayer = friendly
            for (r in 0 until rows) {
                for (c in 0 until cols) map!![r][c].terrain = TerrainType.CLEAR.value
            }
        }

    protected fun place(
        map: GameMap,
        eqid: Int,
        row: Int,
        col: Int,
        side: Int,
    ): GameUnit {
        val owner = if (side == 0) friendly else hostile
        val unit =
            GameUnit(eqid).apply {
                id = side * 1000 + row * 100 + col
                this.owner = owner.id
                player = owner
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    /** The player who begins the engineering jobs in these tests. `internal`, not
     *  `protected`: [FacilityOwner] is internal, and a protected member may not expose it. */
    internal fun builderOwner() = FacilityOwner(friendly.id, friendly.country)

    /** A second player on the BUILDER'S OWN SIDE — the hot-seat case no shipped scenario has, and
     *  the one a side-keyed countdown could not tell from the builder. */
    internal fun allyOwner() = FacilityOwner(friendly.id + 2, friendly.country + 5)

    /** The opponent, for the turn ends that must leave the other side's work alone. */
    internal fun hostileOwner() = FacilityOwner(hostile.id, hostile.country)

    /** A save is text, so a round trip has to go through one: this is the hop that would catch a
     *  field the serializer emits as something `JSON.stringify` cannot carry. */
    protected fun reparse(payload: dynamic): dynamic = JSON.parse<dynamic>(JSON.stringify(payload))

    /** `ExtendedLos` and `Engineering` read the live grid through [GameHolder], the way
     *  `UnitConcealment` does, so the tests that exercise them have to publish one. */
    protected fun holderFor(map: GameMap): Game =
        Game().apply {
            scenario = Scenario(null).apply { this.map = map }
        }
}
