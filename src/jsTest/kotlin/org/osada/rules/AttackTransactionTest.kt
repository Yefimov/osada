package org.osada.rules

import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.attackUnit
import org.osada.model.executeAttack
import org.osada.model.updateUnitList
import org.osada.multiplayer.command.AttackUnit
import org.osada.multiplayer.command.CommandValidation
import org.osada.multiplayer.command.OsadaGameCommandApplier
import org.osada.multiplayer.command.OsadaGameCommandValidator
import org.osada.rules.ruleset.RuleKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The complete attack order as the model resolves it (`model/executeAttack`): support fire, "Attack Stopped",
 * overrun, retreat and surrender -- and a multiplayer `AttackUnit` command producing the same state
 * as a local attack, which it did not before 2026-09-27.
 */
class AttackTransactionTest : OgRulesTestHarness() {
    private val stormEqid = 950

    @BeforeTest
    fun setup() {
        installTestWorld()
        Equipment.putEquipment(
            stormEqid,
            EquipmentData().apply {
                name = "Storm Battalion"
                uclass = UnitClass.INFANTRY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.LEG.value
                movpoints = 4
                ammo = 8
                softatk = 20
                grounddef = 8
                spotrange = 2
            },
        )
        GameRandomSource.resetForTest()
    }

    @AfterTest
    fun teardown() {
        clearTestWorld()
    }

    /** Every neighbour of ([row], [col]) except [keep] becomes ocean, so a defender there can only
     *  go where the test says. */
    private fun encircle(
        map: GameMap,
        row: Int,
        col: Int,
        vararg keep: Cell,
    ) {
        HexGeometry.getAdjacent(row, col).forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (keep.none { it.row == cell.row && it.col == cell.col }) hex.terrain = TerrainType.OCEAN.value
        }
    }

    /**
     * Sets [defender]'s strength to the highest value at which [attacker]'s forecast leaves it alive
     * but past the retreat threshold -- so the test does not depend on tuning combat numbers by hand.
     */
    private fun oweRetreat(
        attacker: GameUnit,
        defender: GameUnit,
    ): GameUnit {
        val strength =
            (10 downTo 2).firstOrNull { s ->
                defender.strength = s
                val kills = CombatResolver.calculateAttackResults(attacker, defender, true).kills
                kills < s && CombatResolver.isLossOverRetreatThreshold(s - kills, s)
            }
        assertNotNull(strength, "no strength makes this defender owe a retreat")
        defender.strength = strength
        return defender
    }

    private fun adjacentPair(): Pair<Cell, Cell> {
        val defender = Cell(4, 4)
        val neighbours = HexGeometry.getAdjacent(defender.row, defender.col)
        return neighbours[0] to neighbours[3]
    }

    // ------------------------------------------------------------------- retreat rule

    /** Panzer Marshal reads the ATTACKER's class: a bombardment never pushes a unit back. */
    @Test
    fun artilleryFireDoesNotForceARetreatButArtilleryCanBePushedBack() {
        val map = world()
        val gun = place(map, gunEqid, 4, 1, 0)
        val rifles = place(map, infantryEqid, 4, 3, 1).apply { strength = 3 }
        assertFalse(CombatResolver.shouldDefenderRetreat(gun, rifles, 10), "artillery fire forces no retreat")

        val assault = place(map, stormEqid, 2, 2, 0)
        val battery = place(map, gunEqid, 2, 3, 1).apply { strength = 3 }
        assertTrue(
            CombatResolver.shouldDefenderRetreat(assault, battery, 10),
            "an assaulted battery can be pushed back",
        )
    }

    // ------------------------------------------------------------------- the order

    @Test
    fun aDefenderPastTheThresholdRetreatsWithoutCapturingOrBeingAmbushed() {
        val map = world()
        holderFor(map)
        val (attackerCell, freeCell) = adjacentPair()
        val attacker =
            place(map, stormEqid, attackerCell.row, attackerCell.col, 0).apply {
                // Overwatch within reach of the retreat hex: an ordinary MOVE there would be ambushed.
                leader = LeaderType.OVERWATCH.value
            }
        val defender = oweRetreat(attacker, place(map, infantryEqid, 4, 4, 1))
        encircle(map, 4, 4, attackerCell, freeCell)
        val freeHex = map.map!![freeCell.row][freeCell.col]
        freeHex.owner = friendly.id
        freeHex.flag = friendly.country

        val outcome = map.executeAttack(attacker, defender)

        val retreat = assertNotNull(outcome.retreat, "losses past the threshold with a free hex: a retreat")
        assertEquals(freeCell.row to freeCell.col, retreat.to.row to retreat.to.col)
        assertEquals(freeCell.row to freeCell.col, defender.getPos()!!.row to defender.getPos()!!.col)
        assertEquals(friendly.id, freeHex.owner, "a forced retreat captures nothing")
        assertFalse(defender.isSurprised, "no ambush is sprung on a forced relocation")
        assertFalse(attacker.hasAmbushedThisTurn, "and the ambusher's reaction is not spent")
    }

    @Test
    fun aDefenderWithNowhereToGoSurrenders() {
        val map = world()
        holderFor(map)
        val (attackerCell, _) = adjacentPair()
        val attacker = place(map, stormEqid, attackerCell.row, attackerCell.col, 0)
        val defender = oweRetreat(attacker, place(map, infantryEqid, 4, 4, 1))
        encircle(map, 4, 4, attackerCell)

        val outcome = map.executeAttack(attacker, defender)

        assertNotNull(outcome.surrender, "an owed retreat with no hex is a surrender")
        assertTrue(defender.destroyed)
    }

    @Test
    fun anUndetectedMinefieldStrikesARetreatingUnit() {
        ruleset(RuleKey.MINEFIELDS to 1)
        val map = world()
        holderFor(map)
        val (attackerCell, freeCell) = adjacentPair()
        val attacker = place(map, stormEqid, attackerCell.row, attackerCell.col, 0)
        val defender = oweRetreat(attacker, place(map, infantryEqid, 4, 4, 1))
        encircle(map, 4, 4, attackerCell, freeCell)
        val mined = map.map!![freeCell.row][freeCell.col]
        Minefields.lay(mined, friendly.side)

        val outcome = map.executeAttack(attacker, defender)

        val retreat = assertNotNull(outcome.retreat)
        assertTrue(retreat.minefieldWasHidden)
        assertTrue(Minefields.isDetectedBy(mined, hostile.side), "the field is revealed to the side it struck")
    }

    @Test
    fun supportFireThatBreaksTheAttackerStopsTheAttack() {
        val map = world()
        holderFor(map)
        val attacker = place(map, infantryEqid, 4, 3, 0).apply { strength = 3 }
        val defender = place(map, infantryEqid, 4, 4, 1)
        place(map, gunEqid, 4, 5, 1)
        val defenderStrength = defender.strength

        val outcome = map.executeAttack(attacker, defender)

        assertTrue(outcome.attackStopped)
        assertEquals(1, outcome.supportShots.size)
        assertEquals(0, outcome.result.kills, "no exchange happened")
        assertEquals(defenderStrength, defender.strength)
        assertTrue(outcome.result.supportFireLosses > 0)
        assertTrue(attacker.hasFired, "the stopped attack is spent")
    }

    @Test
    fun anOverrunKillsWithoutSpendingTheShot() {
        val map = world()
        holderFor(map)
        val tank = place(map, riflemanEqid, 4, 3, 0)
        val remnant = place(map, infantryEqid, 4, 4, 1).apply { strength = 1 }

        val outcome = map.executeAttack(tank, remnant)

        assertTrue(outcome.result.isOverrun)
        assertTrue(remnant.destroyed)
        assertFalse(tank.hasFired, "an overrun hands the shot back")
    }

    /** A surprise belongs to the encounter that caused it and ends with it, whoever resolved it. */
    @Test
    fun aSurpriseEndsWithItsEncounter() {
        val map = world()
        holderFor(map)
        val mover = place(map, infantryEqid, 4, 3, 0).apply { isSurprised = true }
        val hidden = place(map, infantryEqid, 4, 4, 1)

        map.executeAttack(mover, hidden)

        assertFalse(mover.isSurprised)
    }

    /** An evading aircraft looks for a free AIR slot: it must never land on another aircraft and
     *  overwrite it in the hex, and a ground unit beneath does not block it. */
    @Test
    fun anAircraftRetreatHexIsJudgedOnTheAirLayer() {
        val fighterEqid = 951
        Equipment.putEquipment(
            fighterEqid,
            EquipmentData().apply {
                uclass = UnitClass.FIGHTER.value
                target = UnitType.AIR.value
                movmethod = MovMethod.AIR.value
                movpoints = 8
            },
        )
        val map = world()
        val plane = place(map, fighterEqid, 4, 4, 1)
        val neighbours = HexGeometry.getAdjacent(4, 4)
        // Every neighbour holds another aircraft except one, which holds a ground unit only.
        neighbours.drop(1).forEachIndexed { i, cell ->
            val other =
                GameUnit(fighterEqid).apply {
                    id = 5000 + i
                    owner = hostile.id
                    player = hostile
                }
            map.map!![cell.row][cell.col].setUnit(other)
        }
        val groundOnly = neighbours.first()
        place(map, infantryEqid, groundOnly.row, groundOnly.col, 0)

        val hex = CombatPositioning.getRetreatPosition(map.map, plane, map.rows)

        assertEquals(groundOnly.row to groundOnly.col, hex?.let { it.row to it.col })
    }

    // ------------------------------------------------------------------- support falloff

    /** The applied support shot fires at the strength the forecast promised (audit §4.3). */
    @Test
    fun theAppliedSupportShotMatchesTheForecastUnderFalloff() {
        ruleset(RuleKey.SUPPORT_FIRE_FALLOFF to 1)
        val map = world()
        holderFor(map)
        val attacker = place(map, stormEqid, 4, 3, 0)
        val defender = place(map, infantryEqid, 4, 4, 1)
        // Two hexes from the protected defender (so half strength), three from the attacker (in range).
        val gun = place(map, gunEqid, 4, 6, 1)
        val units = listOf(attacker, defender, gun)
        val halved =
            CombatResolver.calculateAttackResults(
                gun,
                attacker,
                true,
                units,
                attackerIsFiringSupport = true,
                halveStrength = true,
            )
        val full = CombatResolver.calculateAttackResults(gun, attacker, true, units, attackerIsFiringSupport = true)
        assertNotEquals(halved.kills, full.kills, "precondition: the falloff changes this shot")

        val outcome = map.executeAttack(attacker, defender)

        assertEquals(1, outcome.supportShots.size)
        assertEquals(halved.kills, outcome.result.supportFireLosses)
    }

    // ------------------------------------------------------------------- Devastating Fire

    /** Firing back in the enemy's turn must not spend the allowance of the turn to come (§4.4). */
    @Test
    fun devastatingFireSurvivesDefending() {
        val map = world()
        holderFor(map)
        val veteran = place(map, stormEqid, 4, 3, 1).apply { leader = LeaderType.DEVASTATING_FIRE.value }
        val raider = place(map, infantryEqid, 4, 4, 0)
        map.attackUnit(raider, veteran, supportFire = false)
        assertEquals(0, veteran.shotsThisTurn, "return fire is not an attack")

        val first = place(map, infantryEqid, 4, 2, 0)
        val second = place(map, infantryEqid, 3, 3, 0)
        map.attackUnit(veteran, first, supportFire = false)
        assertFalse(veteran.hasFired, "the first attack leaves the second")
        map.attackUnit(veteran, second, supportFire = false)
        assertTrue(veteran.hasFired, "the second attack spends it")
    }

    // ------------------------------------------------------------------- multiplayer validation

    /** The host accepts exactly the attacks the player's own screen offers: a spotted enemy inside
     *  the attack overlay. An out-of-range or unseen target is refused, not resolved. */
    @Test
    fun theHostRefusesAnAttackTheScreenWouldNotOffer() {
        val map = world()
        val game = holderFor(map)
        val validator = OsadaGameCommandValidator { game }
        val rifles = place(map, infantryEqid, 4, 3, 0)
        val adjacent = place(map, infantryEqid, 4, 4, 1)
        val distant = place(map, infantryEqid, 4, 7, 1)
        val hidden = place(map, infantryEqid, 3, 3, 1)
        map.map!![4][4].setSpotted(0, true)
        map.map!![4][7].setSpotted(0, true)
        // Placing the rifles spotted its neighbours; this one is made unseen on purpose.
        map.map!![3][3].setSpotted(0, false)

        fun verdict(defender: GameUnit) = validator.validate(AttackUnit(rifles.id, defender.id, rifles.owner), null)

        assertEquals(CommandValidation.Accepted, verdict(adjacent))
        assertTrue(verdict(distant) is CommandValidation.Rejected, "out of range")
        assertTrue(verdict(hidden) is CommandValidation.Rejected, "not seen")
    }

    /** Each support exchange is kept on the outcome, so what it produced (a leader gained on either
     *  side) can be announced like the main exchange's. */
    @Test
    fun eachSupportExchangeIsKeptOnTheOutcome() {
        val map = world()
        holderFor(map)
        val attacker = place(map, infantryEqid, 4, 3, 0)
        val defender = place(map, infantryEqid, 4, 4, 1)
        place(map, gunEqid, 4, 5, 1)

        val outcome = map.executeAttack(attacker, defender)

        val shot = outcome.supportShots.single()
        assertEquals(
            outcome.result.supportFireLosses,
            shot.result.kills,
            "the shot's own result is what hit the attacker",
        )
    }

    // ------------------------------------------------------------------- multiplayer parity

    /**
     * The same battle resolved twice from identical state -- once as a multiplayer command on the
     * host, once as a local order -- must leave identical state, and the command must now include
     * everything a local order does: support fire, the stopped attack, overrun, retreat, surrender.
     * The peers receive the host's state as a snapshot, so host parity is peer parity.
     */
    @Test
    fun aCommandResolvesTheSameCompleteOrderAsALocalAttack() {
        val viaCommand = battle(useCommand = true)
        val local = battle(useCommand = false)

        assertEquals(local, viaCommand)
        assertTrue(viaCommand.contains("stopped=true"), viaCommand)
        assertTrue(viaCommand.contains("overrun=true"), viaCommand)
        assertTrue(viaCommand.contains("retreated=true"), viaCommand)
        assertTrue(viaCommand.contains("surrendered=true"), viaCommand)
    }

    /** Four orders on one map, returning a fingerprint of every unit plus the random cursor. */
    private fun battle(useCommand: Boolean): String {
        GameRandomSource.resetForTest()
        val map = world()
        val game = holderFor(map)
        val applier = OsadaGameCommandApplier(gameProvider = { game })
        val notes = mutableListOf<String>()

        fun order(
            attacker: GameUnit,
            defender: GameUnit,
            before: () -> Unit = {},
        ) {
            before()
            val defenderPos = defender.getPos()
            if (useCommand) {
                applier.apply(AttackUnit(attacker.id, defender.id, attacker.owner))
            } else {
                map.executeAttack(attacker, defender)
                map.updateUnitList()
            }
            notes += "retreated=${defender.getPos()?.let { it.row != defenderPos?.row || it.col != defenderPos.col }}"
            notes += "surrendered=${defender.surrendered}"
            notes += "overrun=${defender.destroyed && !attacker.hasFired}"
        }

        // A bystander on each side, so no single order ends the battle by elimination -- the host
        // checks for that after every attack, as the local presenter does.
        place(map, infantryEqid, 0, 7, 0)
        place(map, infantryEqid, 7, 0, 1)
        // Support fire that stops a weak attacker.
        val weak = place(map, infantryEqid, 1, 1, 0).apply { strength = 3 }
        val covered = place(map, infantryEqid, 1, 2, 1)
        place(map, gunEqid, 1, 3, 1)
        order(weak, covered)
        notes += "stopped=${weak.hasFired && covered.strength == 10}"
        // An overrun.
        order(place(map, riflemanEqid, 6, 1, 0), place(map, infantryEqid, 6, 2, 1).apply { strength = 1 })
        // A retreat, then a surrender.
        val (attackerCell, freeCell) = adjacentPair()
        encircle(map, 4, 4, attackerCell, freeCell)
        val pusher = place(map, stormEqid, attackerCell.row, attackerCell.col, 0)
        order(pusher, oweRetreat(pusher, place(map, infantryEqid, 4, 4, 1)))
        val trapCell = HexGeometry.getAdjacent(6, 6)[0]
        encircle(map, 6, 6, trapCell)
        val captor = place(map, stormEqid, trapCell.row, trapCell.col, 0)
        order(captor, oweRetreat(captor, place(map, infantryEqid, 6, 6, 1)))

        val fingerprint =
            map.units.sortedBy { it.id }.joinToString(";") { u ->
                val pos = u.getPos()
                "${u.id}@${pos?.row},${pos?.col} s${u.strength} a${u.ammo} x${u.experience} " +
                    "f${u.hasFired} d${u.destroyed}"
            }
        return notes.joinToString(" ") + " | " + fingerprint + " | cursor=" + GameRandomSource.cursor()
    }
}
