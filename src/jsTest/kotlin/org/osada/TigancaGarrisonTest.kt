package org.osada

import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.attackUnit
import org.osada.model.resetEquipment
import org.osada.model.toEquipmentData
import org.osada.model.updateUnitList
import org.osada.rules.CombatPositioning
import org.osada.rules.MovementRules
import kotlin.js.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consequences of Falciu 2's Tiganca garrison resolving -- or not.
 *
 * `rcampfa2.xml` garrisons the Romanian objective at row 9, col 8 with eqid 16132, OG's immobile
 * `Entrenched` infantry. Its record lives in merged country file 13 while every nationality the
 * scenario declares points at file 14, so the file was never fetched and the id resolved to
 * nothing. [org.osada.model.EquipmentCountryIndexTest] covers the loading fix; this covers what
 * the two outcomes MEAN on the map, because the reported symptom was not "a missing icon" but a
 * hex that could be neither taken nor shot at.
 *
 * The mechanism is `Equipment.canInitiateAttackOnUnitType`, which returns false when EITHER side's
 * record is absent from the equipment map -- so an unresolved defender is not a weak unit, it is
 * an invulnerable one.
 *
 * The garrison's stats below are `equipment-country-13.json`'s own row for 16132, fed through the
 * same [toEquipmentData] the loader uses, so this fails if the shipped record ever changes shape.
 */
class TigancaGarrisonTest {
    private companion object {
        const val GARRISON_EQID = 16132
        const val RIFLES_EQID = 1
        const val ROMANIAN_COUNTRY = 13
        const val SOVIET_COUNTRY = 61
        const val OBJECTIVE_ROW = 1
        const val OBJECTIVE_COL = 1
        const val ATTACKER_ROW = 1
        const val ATTACKER_COL = 0
        const val MAX_ASSAULT_ROUNDS = 20

        /** `parsehints` exactly as the merged country files declare it. */
        val PARSE_HINTS =
            listOf(
                "gunrange",
                "icon",
                "yearexpired",
                "cost",
                "initiative",
                "spotrange",
                "hardatk",
                "softatk",
                "uclass",
                "airdef",
                "fuel",
                "airseaweight",
                "rangedefmod",
                "airatk",
                "groundweight",
                "movmethod",
                "navalatk",
                "embark",
                "movpoints",
                "grounddef",
                "target",
                "yearavailable",
                "name",
                "country",
                "closedef",
                "ammo",
                "attr",
                "monthavailable",
                "monthexpired",
                "attr2",
                "attrEx",
                "bombsize",
                "hangarcap",
                "railtransportable",
                "airweight",
                "navalweight",
                "railweight",
                "heloweight",
                "hangarweight",
                "navaltransportable",
                "airtransportable",
                "paradroppable",
                "fronts",
                "factions",
                "movsound",
                "atksound",
                "diesound",
            )

        /** `units["16132"]` out of `equipment/eqp-united/equipment-country-13.json`, verbatim. */
        const val GARRISON_ROW =
            "[1,\"resources/units/images/basekorp/sbe04.png\",1946,16,8,1,3,10,1,10,0,0,8,6,145," +
                "4,4,2,0,15,0,1925,\"Entrenched\",13,1,12,8427523,1,12,1,8384,0,0,1,0,0,0,0," +
                "32768,1,1,0,0,0,1001,2002,3001]"
    }

    private fun garrisonRecord(): EquipmentData = JSON.parse<Json>(GARRISON_ROW).toEquipmentData(PARSE_HINTS)

    @BeforeTest
    fun setUp() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(
            RIFLES_EQID,
            EquipmentData().apply {
                name = "Rifles"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                gunrange = 1
                softatk = 14
                hardatk = 6
                grounddef = 6
                closedef = 8
                spotrange = 3
                ammo = 10
                initiative = 6
                country = SOVIET_COUNTRY + 1
            },
        )
    }

    @Test
    fun theShippedRecordIsARealCombatUnitAndNotACityMarker() {
        val record = garrisonRecord()

        assertEquals("Entrenched", record.name)
        assertEquals(UnitClass.INFANTRY.value, record.uclass, "class 1 -- infantry, not \"No Class\"")
        assertEquals("resources/units/images/basekorp/sbe04.png", record.icon)
        assertEquals(10, record.softatk)
        assertEquals(3, record.hardatk)
        assertEquals(15, record.grounddef)
        assertEquals(12, record.ammo)
        assertEquals(0, record.movpoints, "an entrenchment does not move -- this part was never wrong")
        assertEquals(ROMANIAN_COUNTRY, record.country, "the record's own country, which is not its flag")
    }

    /** The bug as reported: no record, so nothing can shoot at it and nothing can walk onto it. */
    @Test
    fun anUnresolvedGarrisonIsInvulnerableAndBlocksTheObjectiveForever() {
        val battle = battlefield(resolveGarrison = false)

        assertFalse(
            battle.objectiveIsUnderAttackRange(),
            "an absent defender record makes canInitiateAttackOnUnitType false",
        )
        assertFalse(battle.objectiveCanBeEntered(), "and the hex is still occupied, so nobody may enter")
    }

    @Test
    fun theResolvedGarrisonCanBeAttacked() {
        val battle = battlefield(resolveGarrison = true)

        assertTrue(battle.objectiveIsUnderAttackRange(), "Tiganca is a legal target once its record is loaded")
        assertFalse(battle.objectiveCanBeEntered(), "still held -- it has to be destroyed first")
    }

    @Test
    fun destroyingTheGarrisonFreesTheObjectiveForOccupationAndCapture() {
        val battle = battlefield(resolveGarrison = true)

        assertTrue(battle.assaultUntilDestroyed(), "the assault must kill it within $MAX_ASSAULT_ROUNDS rounds")
        assertTrue(battle.objectiveCanBeEntered(), "the objective is free once the garrison dies")

        val objective = battle.objectiveHex()
        battle.map.combatApplication.captureHex(objective, battle.attacker)

        assertEquals(battle.soviet.id, objective.owner, "the objective changes hands")
        assertEquals(battle.soviet.country, objective.flag, "and flies the new owner's flag")
    }

    /**
     * Two adjacent hexes standing in for Tiganca and the hex a Soviet rifle battalion assaults it
     * from. [resolveGarrison] is the whole experiment: it decides only whether eqid 16132 is in the
     * equipment map, which is exactly what the country-file fetch decides in the real game.
     */
    private fun battlefield(resolveGarrison: Boolean): Battlefield {
        if (resolveGarrison) Equipment.putEquipment(GARRISON_EQID, garrisonRecord())

        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
            }
        val soviet =
            Player().apply {
                id = 0
                side = 1
                country = SOVIET_COUNTRY
            }
        val romanian =
            Player().apply {
                id = 1
                side = 0
                country = ROMANIAN_COUNTRY
            }
        map.addPlayer(soviet)
        map.addPlayer(romanian)

        val attacker =
            GameUnit(RIFLES_EQID).apply {
                owner = soviet.id
                player = soviet
                strength = 10
                ammo = 10
            }
        // The scenario's own authored values for this formation.
        val garrison =
            GameUnit(GARRISON_EQID).apply {
                owner = romanian.id
                player = romanian
                strength = 7
                experience = 192
                flag = 14
                ammo = 12
            }

        map.map
            ?.get(ATTACKER_ROW)
            ?.get(ATTACKER_COL)
            ?.setUnit(attacker)
        map.map?.get(OBJECTIVE_ROW)?.get(OBJECTIVE_COL)?.apply {
            name = "Tiganca"
            owner = romanian.id
            flag = romanian.country
            victorySide = 1
            setUnit(garrison)
        }
        map.addUnit(attacker)
        map.addUnit(garrison)
        return Battlefield(map, soviet, attacker, garrison)
    }

    private class Battlefield(
        val map: GameMap,
        val soviet: Player,
        val attacker: GameUnit,
        val garrison: GameUnit,
    ) {
        fun objectiveHex() = map.map!![OBJECTIVE_ROW][OBJECTIVE_COL]

        fun objectiveIsUnderAttackRange(): Boolean =
            CombatPositioning
                .getUnitAttackCells(map.map, attacker, map.rows, map.cols)
                .any { it.row == OBJECTIVE_ROW && it.col == OBJECTIVE_COL }

        fun objectiveCanBeEntered(): Boolean =
            MovementRules.canPassInto(map.map, attacker, Cell(OBJECTIVE_ROW, OBJECTIVE_COL))

        /**
         * Assaults the hex round after round, re-arming between them, until the garrison dies.
         *
         * `updateUnitList` is what actually clears a destroyed formation off its hex -- combat only
         * marks it -- so it runs here for the same reason the turn flow runs it after an attack.
         */
        fun assaultUntilDestroyed(): Boolean {
            repeat(MAX_ASSAULT_ROUNDS) {
                if (objectiveHex().unit == null) return true
                attacker.hasFired = false
                attacker.hasMoved = false
                attacker.strength = 10
                attacker.ammo = 10
                map.attackUnit(attacker, garrison, false)
                map.updateUnitList()
            }
            return objectiveHex().unit == null
        }
    }
}
