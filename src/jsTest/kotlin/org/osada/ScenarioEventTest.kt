package org.osada

import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.getUnits
import org.osada.model.resetEquipment
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioEventParser
import org.osada.scenario.eventById
import org.osada.scenario.firedEventIds
import org.w3c.dom.parsing.DOMParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Authored scenario events: parsing, trigger evaluation, once-only firing and save/restore.
 *
 * These are the guarantees the Kiel prisoner sequence rests on. The regression each test pins is
 * named in its own assertion message; the ones worth stating up front are that an unarmed spawned
 * unit must not satisfy the trigger that spawned it (otherwise the alarm fires forever), and that
 * `fired` must survive a save round-trip (otherwise reloading re-spawns the prisoners).
 */
class ScenarioEventTest {
    private companion object {
        const val RIFLES = 1
        const val UNARMED = 2
        const val REBEL_ID = 0
        const val REBEL_SIDE = 1
        const val LOYALIST_ID = 1
        const val LOYALIST_SIDE = 0
        const val ANCHOR_ROW = 5
        const val ANCHOR_COL = 5
        const val MAP_SIZE = 12
    }

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(RIFLES, infantry(softAttack = 8))
        Equipment.putEquipment(UNARMED, infantry(softAttack = 0))
    }

    private fun infantry(softAttack: Int) =
        EquipmentData().apply {
            gunrange = 1
            cost = 10
            initiative = 5
            spotrange = 2
            hardatk = 0
            softatk = softAttack
            uclass = UnitClass.INFANTRY.value
            movmethod = MovMethod.LEG.value
            movpoints = 3
            grounddef = 6
            target = UnitType.SOFT.value
            closedef = 10
            ammo = if (softAttack > 0) 10 else 0
        }

    /** A game with a loaded, event-carrying scenario and no UI — every UI call site is null-safe. */
    private fun gameWith(eventsXml: String): Game {
        val game = Game()
        val scenario = Scenario(null)
        scenario.map.rows = MAP_SIZE
        scenario.map.cols = MAP_SIZE
        scenario.map.allocMap()
        scenario.map.addPlayer(
            Player().apply {
                id = REBEL_ID
                side = REBEL_SIDE
            },
        )
        scenario.map.addPlayer(
            Player().apply {
                id = LOYALIST_ID
                side = LOYALIST_SIDE
            },
        )
        scenario.setMoveTable()
        scenario.isLoaded = true
        ScenarioEventParser.parse(scenario, parseXml(eventsXml))
        game.scenario = scenario
        game.gameStarted = true
        return game
    }

    private fun parseXml(events: String) =
        DOMParser().parseFromString(
            """<?xml version="1.0" encoding="UTF-8"?><map rows="$MAP_SIZE" cols="$MAP_SIZE">$events</map>""",
            "application/xml",
        )

    private fun place(
        game: Game,
        eqid: Int,
        owner: Int,
        row: Int,
        col: Int,
    ): GameUnit {
        val unit = GameUnit(eqid)
        unit.owner = owner
        game.scenario!!
            .map.map!![row][col]
            .setUnit(unit)
        game.scenario!!.map.addUnit(unit)
        return unit
    }

    private fun alarmXml(radius: Int = 3) =
        """
        <events>
          <event id="alarm" trigger="proximity" row="$ANCHOR_ROW" col="$ANCHOR_COL" radius="$radius"
                 side="$REBEL_SIDE" combat="1" message="Alarm at the compound!">
            <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL">
              <unit id="$UNARMED" owner="$REBEL_ID" str="9" temporaryBorrowed="true" nodossier="true"/>
            </spawn>
          </event>
        </events>
        """.trimIndent()

    // -------------------------------------------------------------------- parsing

    @Test
    fun eventsParseWithTriggerGateAndSpawns() {
        val game =
            gameWith(
                """
                <events>
                  <event id="breakout" trigger="start" row="4" col="7" allFlags="sailors_liberated"
                         message="Some of them are out.">
                    <spawn row="4" col="7"><unit id="$UNARMED" owner="$REBEL_ID" str="4"/></spawn>
                  </event>
                  <event id="rescue" trigger="proximity" row="4" col="7" radius="1" side="$REBEL_SIDE"
                         combat="1" requiresUnitsFrom="breakout" removeFrom="breakout">
                    <spawn row="4" col="7"><unit id="$RIFLES" owner="$REBEL_ID" exp="60" str="5"/></spawn>
                  </event>
                </events>
                """.trimIndent(),
            )
        val events = game.scenario!!.events
        assertEquals(2, events.size)
        val breakout = assertNotNull(game.scenario!!.eventById("breakout"))
        assertEquals(listOf("sailors_liberated"), breakout.gate.allFlags)
        assertEquals("Some of them are out.", breakout.message)
        assertEquals(
            4,
            breakout.spawns
                .single()
                .unit.strength,
        )
        val rescue = assertNotNull(game.scenario!!.eventById("rescue"))
        assertEquals(listOf("breakout"), rescue.gate.requiresUnitsFrom)
        assertEquals(listOf("breakout"), rescue.removeFrom)
        assertEquals(1, rescue.trigger.radius)
    }

    @Test
    fun scenarioWithoutEventsParsesToNothing() {
        assertTrue(gameWith("").scenario!!.events.isEmpty(), "every pre-existing scenario must be untouched")
    }

    // ------------------------------------------------------------------ proximity

    @Test
    fun proximityEventWaitsUntilAQualifyingUnitIsInRange() {
        val game = gameWith(alarmXml())
        val marcher = place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL + 6)

        game.evaluateScenarioEvents()
        assertTrue(game.scenario!!.firedEventIds().isEmpty(), "six hexes away is not an approach")
        assertNull(
            game.scenario!!
                .map.map!![ANCHOR_ROW][ANCHOR_COL]
                .unit,
            "nothing may exist to be shot at yet",
        )

        game.scenario!!
            .map.map!![ANCHOR_ROW][ANCHOR_COL + 6]
            .delUnit(marcher)
        game.scenario!!
            .map.map!![ANCHOR_ROW][ANCHOR_COL - 2]
            .setUnit(marcher)
        game.evaluateScenarioEvents()

        assertEquals(setOf("alarm"), game.scenario!!.firedEventIds())
        assertNotNull(
            game.scenario!!
                .map.map!![ANCHOR_ROW][ANCHOR_COL]
                .unit,
            "the detainees are now on the map",
        )
    }

    @Test
    fun enemyUnitsInRangeDoNotTripAPlayerSideTrigger() {
        val game = gameWith(alarmXml())
        place(game, RIFLES, LOYALIST_ID, ANCHOR_ROW, ANCHOR_COL + 1)
        game.evaluateScenarioEvents()
        assertTrue(
            game.scenario!!.firedEventIds().isEmpty(),
            "the garrison standing on its own compound is not the revolution approaching it",
        )
    }

    @Test
    fun unarmedUnitsDoNotTripACombatOnlyTrigger() {
        val game = gameWith(alarmXml())
        place(game, UNARMED, REBEL_ID, ANCHOR_ROW, ANCHOR_COL + 1)
        game.evaluateScenarioEvents()
        assertTrue(
            game.scenario!!.firedEventIds().isEmpty(),
            "combat='1' is what stops the spawned detainees from satisfying their own trigger",
        )
    }

    @Test
    fun spawnedDetaineesDoNotRetriggerTheirOwnEvent() {
        val game = gameWith(alarmXml())
        val marcher = place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL - 2)
        game.evaluateScenarioEvents()
        val spawned = assertNotNull(game.scenario!!.eventById("alarm")).spawnedUnitIds.toList()
        assertEquals(1, spawned.size)

        // Repeated evaluation is the normal case: every move and every turn hand-off calls in.
        repeat(3) { game.evaluateScenarioEvents() }
        assertEquals(
            1,
            assertNotNull(game.scenario!!.eventById("alarm")).spawnedUnitIds.size,
            "an event fires once; re-evaluating must not stack duplicate spawns",
        )
        assertEquals(
            2,
            game.scenario!!
                .map
                .getUnits()
                .count { !it.destroyed },
        )
        assertFalse(marcher.destroyed)
    }

    // ---------------------------------------------------------------------- gates

    @Test
    fun requiresUnitsFromAndRemoveFromConvertTheSurvivors() {
        val game =
            gameWith(
                """
                <events>
                  <event id="alarm" trigger="start" row="$ANCHOR_ROW" col="$ANCHOR_COL">
                    <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL">
                      <unit id="$UNARMED" owner="$REBEL_ID" str="9" nodossier="true"/>
                    </spawn>
                  </event>
                  <event id="rescue" trigger="proximity" row="$ANCHOR_ROW" col="$ANCHOR_COL" radius="1"
                         side="$REBEL_SIDE" combat="1" requiresUnitsFrom="alarm" removeFrom="alarm"
                         message="The gates are open.">
                    <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL">
                      <unit id="$RIFLES" owner="$REBEL_ID" exp="60" str="5" temporaryBorrowed="true"/>
                    </spawn>
                  </event>
                </events>
                """.trimIndent(),
            )

        // Nobody nearby: the detainees appear, the rescue does not happen.
        game.evaluateScenarioEvents()
        assertEquals(setOf("alarm"), game.scenario!!.firedEventIds())

        place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL + 1)
        game.evaluateScenarioEvents()

        assertEquals(setOf("alarm", "rescue"), game.scenario!!.firedEventIds())
        val survivors =
            game.scenario!!
                .map
                .getUnits()
                .filter { !it.destroyed && it.eqid == RIFLES }
        assertEquals(2, survivors.size, "the rescuing column plus the detachment the detainees became")
        assertTrue(
            game.scenario!!
                .map
                .getUnits()
                .none { !it.destroyed && it.eqid == UNARMED },
            "the detainees are converted, not left standing next to the unit they became",
        )
        val freed = assertNotNull(survivors.firstOrNull { it.isTemporaryBorrowed })
        assertEquals(5, freed.strength)
        assertEquals(60, freed.experience)
    }

    @Test
    fun rescueDoesNothingOnceTheDetaineesAreDead() {
        val game =
            gameWith(
                """
                <events>
                  <event id="alarm" trigger="start" row="$ANCHOR_ROW" col="$ANCHOR_COL">
                    <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL">
                      <unit id="$UNARMED" owner="$REBEL_ID" str="9" nodossier="true"/>
                    </spawn>
                  </event>
                  <event id="rescue" trigger="proximity" row="$ANCHOR_ROW" col="$ANCHOR_COL" radius="1"
                         side="$REBEL_SIDE" combat="1" requiresUnitsFrom="alarm" removeFrom="alarm">
                    <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL">
                      <unit id="$RIFLES" owner="$REBEL_ID" str="5"/>
                    </spawn>
                  </event>
                </events>
                """.trimIndent(),
            )
        game.evaluateScenarioEvents()
        game.scenario!!
            .map
            .getUnits()
            .filter { it.eqid == UNARMED }
            .forEach { it.destroyed = true }

        place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL + 1)
        game.evaluateScenarioEvents()

        assertEquals(
            setOf("alarm"),
            game.scenario!!.firedEventIds(),
            "arriving after they are dead must not hand out the reward for saving them",
        )
    }

    @Test
    fun campaignFlagsSelectWhichBranchFires() {
        val xml =
            """
            <events>
              <event id="breakout" trigger="start" row="$ANCHOR_ROW" col="$ANCHOR_COL" allFlags="sailors_liberated">
                <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL"><unit id="$UNARMED" owner="$REBEL_ID" str="4"/></spawn>
              </event>
              <event id="alarm" trigger="start" row="$ANCHOR_ROW" col="$ANCHOR_COL" noneFlags="sailors_liberated">
                <spawn row="$ANCHOR_ROW" col="$ANCHOR_COL"><unit id="$UNARMED" owner="$REBEL_ID" str="9"/></spawn>
              </event>
            </events>
            """.trimIndent()

        org.osada.campaign.CampaignNarrative
            .reset()
        val untouched = gameWith(xml)
        untouched.evaluateScenarioEvents()
        assertEquals(
            setOf("alarm"),
            untouched.scenario!!.firedEventIds(),
            "no flag set (which is also every standalone launch) takes the default branch",
        )

        org.osada.campaign.CampaignNarrative.state
            .setFlag("sailors_liberated")
        val chosen = gameWith(xml)
        chosen.evaluateScenarioEvents()
        assertEquals(setOf("breakout"), chosen.scenario!!.firedEventIds())
        org.osada.campaign.CampaignNarrative
            .reset()
    }

    // ------------------------------------------------------------- save / restore

    @Test
    fun firedStateAndPendingDefinitionsSurviveASaveRoundTrip() {
        val game = gameWith(alarmXml())
        place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL - 2)
        game.evaluateScenarioEvents()
        val spawnedId = assertNotNull(game.scenario!!.eventById("alarm")).spawnedUnitIds.single()

        val restored = Scenario(null)
        restoreScenarioEvents(restored, JSON.parse(JSON.stringify(serializeScenarioEvents(game.scenario!!.events))))

        val event = assertNotNull(restored.eventById("alarm"), "definitions travel with the save, not just progress")
        assertTrue(event.fired)
        assertEquals(listOf(spawnedId), event.spawnedUnitIds)
        assertEquals(ANCHOR_ROW, event.trigger.row)
        assertEquals(REBEL_SIDE, event.trigger.side)
        assertTrue(event.trigger.combatOnly)
        assertEquals(
            UNARMED,
            event.spawns
                .single()
                .unit.eqid,
        )
    }

    @Test
    fun aRestoredFiredEventNeverSpawnsAgain() {
        val game = gameWith(alarmXml())
        place(game, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL - 2)
        game.evaluateScenarioEvents()

        val reloaded = gameWith(alarmXml())
        restoreScenarioEvents(
            reloaded.scenario!!,
            JSON.parse(JSON.stringify(serializeScenarioEvents(game.scenario!!.events))),
        )
        place(reloaded, RIFLES, REBEL_ID, ANCHOR_ROW, ANCHOR_COL - 1)
        reloaded.evaluateScenarioEvents()

        assertTrue(
            reloaded.scenario!!
                .map
                .getUnits()
                .none { it.eqid == UNARMED },
            "reloading inside the battle must not re-run an event that already happened",
        )
    }

    @Test
    fun savesWrittenBeforeEventsExistedRestoreToNone() {
        val scenario = Scenario(null)
        restoreScenarioEvents(scenario, null)
        assertTrue(scenario.events.isEmpty())
        restoreScenarioEvents(scenario, JSON.parse("""{"not":"an array"}"""))
        assertTrue(scenario.events.isEmpty(), "a corrupt block must degrade to no events, never throw")
    }
}
