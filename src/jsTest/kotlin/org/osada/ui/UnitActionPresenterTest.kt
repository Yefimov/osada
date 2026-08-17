package org.osada.ui

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.TerrainEx
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.getPlayer
import org.osada.model.resetEquipment
import org.osada.rules.SupplyRules
import org.osada.rules.UnitActionAvailability
import org.osada.rules.UnitActionContext
import org.osada.rules.UnitActionId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rendered action panel: it must state the exact active supply factors and must never repeat
 * PM's hard-coded `City: 100%; outside city: 77%` table, which is false for ATOMIC, BASEKORP and
 * COMWW2 (`docs/design/action-affordances-and-objectives.md` §4).
 */
class UnitActionPresenterTest {
    private val tankEqid = 810

    @AfterTest
    fun cleanup() {
        TerrainEx.resetForTest()
    }

    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        TerrainEx.resetForTest()
        Equipment.resetEquipment()
        Equipment.putEquipment(
            tankEqid,
            EquipmentData().apply {
                name = "Tank Brigade"
                uclass = UnitClass.TANK.value
                target = UnitType.HARD.value
                movmethod = MovMethod.TRACKED.value
                movpoints = 6
                ammo = 8
                fuel = 40
                cost = 30
            },
        )
    }

    @Test
    fun theSupplyPanelListsOnlyTheFactorsThatParticipated() {
        val map = map()
        val player = map.currentPlayer!!
        val unit = place(map, player)
        unit.ammo = 1
        unit.fuel = 4
        map.map!![1][1].terrain = TerrainType.CLEAR.value
        TerrainEx.setForTest(
            emptyMap(),
            supplyFactorMap = mapOf(TerrainType.CLEAR.value to 70),
            supplyModifierMap = mapOf("road" to 20, "rail" to 20),
        )

        val text = panelText(map, unit, player)

        assertTrue(text.contains("70% base supply"), text)
        assertFalse(text.contains("Road network"), "no road on this hex: $text")
        assertFalse(text.contains("Rail network"), "no rail on this hex: $text")
        assertFalse(text.contains("adjacent enem"), "no adjacent enemy: $text")
        assertTrue(text.contains("Effective supply: 70%"), text)
    }

    @Test
    fun theSupplyPanelNeverRepeatsThePanzerMarshalCityFieldTable() {
        val map = map()
        val player = map.currentPlayer!!
        val unit = place(map, player)
        unit.ammo = 1
        // In a city the only true percentage is 100: PM's off-city 77 and its x0.67/x0.33 enemy
        // table must not appear at all, in any hex, as a recited rule.
        map.map!![1][1].terrain = TerrainType.CITY.value

        val text = panelText(map, unit, player)

        assertTrue(text.contains("Effective supply: 100%"), text)
        assertFalse(text.contains("77"), text)
        assertFalse(text.contains("outside city"), text)
        assertFalse(text.contains("0.67"), text)
        assertFalse(text.contains("0.33"), text)
    }

    @Test
    fun theSupplyPanelQuotesTheGainTheCommandWouldDeliver() {
        val map = map()
        val player = map.currentPlayer!!
        val unit = place(map, player)
        unit.ammo = 1
        unit.fuel = 4

        val committed = SupplyRules.getResupplyValue(map, unit)
        val text = panelText(map, unit, player)

        assertTrue(text.contains("+${committed.ammo} ammunition"), text)
        assertTrue(text.contains("+${committed.fuel} fuel"), text)
    }

    @Test
    fun aBlockedActionRendersItsReasonAndAnUnavailableStatus() {
        val map = map()
        val player = map.currentPlayer!!
        val unit = place(map, player)

        val view = view(map, unit, player, UnitActionId.RESUPPLY)

        assertFalse(view.enabled)
        assertEquals("Unavailable", view.status)
        assertTrue(view.lines.any { it.kind == "bad" && it.text == "Already fully supplied." }, "${view.lines}")
    }

    @Test
    fun theEnemyPressureDivisorIsNamedAsItsOwnFactor() {
        val map = map()
        val player = map.currentPlayer!!
        val enemy = map.getPlayer(1)!!
        val unit = place(map, player)
        unit.ammo = 1
        place(map, enemy, row = 1, col = 2)

        val text = panelText(map, unit, player)

        assertTrue(text.contains("1 adjacent enemy: supply divided by 1.5"), text)
    }

    // ---- harness ------------------------------------------------------------------------------

    private fun map(): GameMap {
        val map =
            GameMap().apply {
                rows = 5
                cols = 5
                allocMap()
            }
        val friendly =
            Player().apply {
                id = 0
                side = 0
                prestige = 5_000
            }
        val enemy =
            Player().apply {
                id = 1
                side = 1
            }
        map.addPlayer(friendly)
        map.addPlayer(enemy)
        map.currentPlayer = friendly
        return map
    }

    private var nextUnitId = 1

    private fun place(
        map: GameMap,
        owner: Player,
        row: Int = 1,
        col: Int = 1,
    ): GameUnit {
        val unit =
            GameUnit(tankEqid).apply {
                id = nextUnitId++
                this.owner = owner.id
                player = owner
            }
        map.map!![row][col].setUnit(unit)
        map.addUnit(unit)
        return unit
    }

    private fun view(
        map: GameMap,
        unit: GameUnit,
        player: Player,
        action: UnitActionId,
    ): UnitActionPresenter.View {
        val availability =
            UnitActionAvailability.forAction(
                action,
                UnitActionContext(map = map, unit = unit, currentPlayer = player),
            )
        return UnitActionPresenter.view(availability, unit, map, asleep = false)
    }

    private fun panelText(
        map: GameMap,
        unit: GameUnit,
        player: Player,
    ): String = view(map, unit, player, UnitActionId.RESUPPLY).semanticText()
}
