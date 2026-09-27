package org.osada.hero

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.enterCampaignForTests
import org.osada.leaveCampaignForTests
import org.osada.model.CombatLeaderAcquisition
import org.osada.model.CombatResults
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.model.addPlayer
import org.osada.model.addUnit
import org.osada.model.allocMap
import org.osada.model.resetEquipment
import org.osada.model.setTransport
import org.osada.model.updateUnitList
import org.osada.rules.GameRandomSource
import org.osada.rules.UnitExperience
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The campaign-hero scope (`hero/CampaignHeroScope`, `docs/design/combat-movement-heroes-weather-audit.md`
 * §3): heroes exist only for the campaign player inside a campaign run, everyone else keeps the
 * legacy leader path, and records that should never have been enrolled are kept but inert.
 */
class CampaignHeroScopeTest {
    private companion object {
        const val TANK_EQID = 7101
        const val INFANTRY_EQID = 7102
        const val TRUCK_EQID = 7103
        const val HERO = "H-SCOPE"
        const val FORMATION = "F-0-1"
    }

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(
            TANK_EQID,
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                name = "Tank"
            },
        )
        Equipment.putEquipment(
            INFANTRY_EQID,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                name = "Rifles"
            },
        )
        Equipment.putEquipment(
            TRUCK_EQID,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                name = "Truck"
            },
        )
        HeroCampaign.reset()
        GameRandomSource.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        HeroCampaign.reset()
        leaveCampaignForTests()
    }

    private fun unit(
        owner: Int,
        eqid: Int = TANK_EQID,
    ) = GameUnit(eqid).apply { this.owner = owner }

    private fun heroOn(formationId: String) {
        HeroCampaign.roster().putFormation(
            CoreFormation(
                id = FormationId(formationId),
                ownerId = 0,
                country = 19,
                displayName = "1st Tank",
                currentEquipmentId = TANK_EQID,
                unitClass = UnitClass.TANK.value,
                assignedHeroId = HeroId(HERO),
            ),
        )
        HeroCampaign.roster().putHero(
            HeroDefinition(
                id = HeroId(HERO),
                origin = HeroOrigin.PROCEDURAL,
                displayName = "Ivan Orlov",
                backgroundId = "",
                biographyFacts = HeroBiographyFacts(emergenceEventId = "x"),
                portrait = PortraitComposition(seed = 1),
            ),
            HeroState(
                heroId = HeroId(HERO),
                rankId = "major",
                status = HeroStatus.ACTIVE,
                assignedFormationId = FormationId(formationId),
                learnedTraitIds = setOf(LegacyTraitMapping.toTraitId(LeaderType.AGGRESSIVE_ATTACK)),
            ),
        )
    }

    // --------------------------------------------------------------- enrolment

    @Test
    fun aStandalonePurchaseIsNeverEnrolled() {
        val bought = unit(owner = 0)
        Player().apply { id = 0 }.addCoreUnit(bought)

        assertNull(bought.formationId, "no campaign run, so no formation id")
        assertTrue(HeroCampaign.roster().allFormations().isEmpty())
        assertFalse(HeroCampaign.isHeroEligible(bought))
    }

    @Test
    fun anAiPurchaseInACampaignIsNeverEnrolled() {
        enterCampaignForTests()
        val bought = unit(owner = 1)
        Player().apply { id = 1 }.addCoreUnit(bought)

        assertNull(bought.formationId, "the AI's purchase is core to its battle, not to the hero roster")
        assertTrue(HeroCampaign.roster().allFormations().isEmpty())
    }

    @Test
    fun theCampaignPlayersPurchaseIsEnrolled() {
        enterCampaignForTests()
        val bought = unit(owner = 0)
        Player().apply { id = 0 }.addCoreUnit(bought)

        assertNotNull(bought.formationId)
        assertTrue(HeroCampaign.isHeroEligible(bought))
    }

    // ---------------------------------------------------------- stale identity

    /** A hero's traits must not follow a formation id out of the campaign it belongs to. */
    @Test
    fun aCampaignHeroLendsNothingOnceTheRunIsLeft() {
        enterCampaignForTests()
        heroOn(FORMATION)
        val led = unit(owner = 0).apply { formationId = FORMATION }
        assertTrue(Leaders.unitHasLeader(led, LeaderType.AGGRESSIVE_ATTACK), "inside the run, the hero's trait applies")

        leaveCampaignForTests()

        assertNull(HeroCampaign.heroFor(led))
        assertFalse(Leaders.unitHasLeader(led, LeaderType.AGGRESSIVE_ATTACK), "a stale id authorises nothing")
    }

    /** An enemy unit carrying an id (an old save's AI purchase) takes the legacy path. */
    @Test
    fun anEnemyUnitWithAStaleIdTakesTheLegacyPath() {
        enterCampaignForTests()
        val enemy = unit(owner = 1).apply { formationId = "F-1-1" }

        val gain =
            CombatLeaderAcquisition.acquire(enemy, unit(owner = 0), CombatResults(), isAttacker = true)

        assertEquals(CombatLeaderAcquisition.Gain.NONE, gain)
        assertTrue(HeroCampaign.roster().allFormations().isEmpty(), "no record is created for the enemy")
    }

    // --------------------------------------------------------- inactive records

    @Test
    fun aRestoreDeactivatesEnemyRecordsButKeepsThem() {
        val roster =
            HeroRoster().apply {
                putFormation(
                    CoreFormation(
                        id = FormationId("F-1-1"),
                        ownerId = 1,
                        country = 5,
                        displayName = "Kihei 18",
                        currentEquipmentId = TANK_EQID,
                        unitClass = UnitClass.TANK.value,
                        history = listOf(FormationEvent("flag_captured", "s1", 3)),
                    ),
                )
            }
        HeroCampaign.restore(HeroSerializer.serialize(roster))
        enterCampaignForTests()

        val kept = assertNotNull(HeroCampaign.roster().formation(FormationId("F-1-1")))
        assertTrue(kept.inactive)
        assertEquals(1, kept.history.size, "its history survives")
        val enemy = unit(owner = 1).apply { formationId = "F-1-1" }
        assertNull(HeroCampaign.formationFor(enemy))

        val reread = HeroSerializer.deserialize(HeroSerializer.serialize(HeroCampaign.roster()))
        assertTrue(assertNotNull(reread.formation(FormationId("F-1-1"))).inactive, "the flag round-trips")
    }

    @Test
    fun aHeroOfAnInactiveFormationIsNotAmongServingCommanders() {
        enterCampaignForTests()
        heroOn(FORMATION)
        assertEquals(1, HeroCampaign.commanders().size)

        val record = assertNotNull(HeroCampaign.roster().formation(FormationId(FORMATION)))
        HeroCampaign.roster().putFormation(record.copy(inactive = true))

        assertEquals(0, HeroCampaign.commanders().size)
        assertNotNull(HeroCampaign.roster().state(HeroId(HERO)), "the officer's record is kept")
    }

    // -------------------------------------------------------- real equipment

    /** A formation first met riding its trucks is an infantry formation, not a truck one. */
    @Test
    fun aMountedFormationIsRecordedWithItsOwnEquipment() {
        enterCampaignForTests()
        val rifles =
            unit(owner = 0, eqid = INFANTRY_EQID).apply {
                formationId = "F-0-9"
                setTransport(TRUCK_EQID)
                isMounted = true
            }
        assertEquals(UnitClass.GROUND_TRANSPORT.value, rifles.unitData().uclass, "precondition: mounted")

        HeroCampaign.recordFormationEvent(rifles, "flag_captured")

        val record = assertNotNull(HeroCampaign.roster().formation(FormationId("F-0-9")))
        assertEquals(UnitClass.INFANTRY.value, record.unitClass)
        assertEquals("Rifles", record.displayName)
    }

    // ------------------------------------------------------ non-combat losses

    /** A commander's formation lost OUTSIDE a combat exchange (a minefield, an AA gun) still has
     *  its commander's fate resolved, by the unit sweep every loss passes through. */
    @Test
    fun aCommanderLostOutsideCombatIsResolved() {
        enterCampaignForTests()
        heroOn(FORMATION)
        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
                addPlayer(Player().apply { id = 0 })
            }
        val led = unit(owner = 0).apply { formationId = FORMATION }
        map.map!![1][1].setUnit(led)
        map.addUnit(led)

        led.destroyed = true
        map.updateUnitList()

        assertNull(HeroCampaign.roster().state(HeroId(HERO))?.assignedFormationId, "detached from the lost formation")
    }

    /** Disbanding is not a loss: no casualty roll for the commander. */
    @Test
    fun aDisbandedFormationRollsNoCasualty() {
        enterCampaignForTests()
        heroOn(FORMATION)
        val map =
            GameMap().apply {
                rows = 3
                cols = 3
                allocMap()
                addPlayer(Player().apply { id = 0 })
            }
        val led =
            unit(owner = 0).apply {
                formationId = FORMATION
                nodossier = true
            }
        map.map!![1][1].setUnit(led)
        map.addUnit(led)

        led.destroyed = true
        map.updateUnitList()

        assertEquals(HeroStatus.ACTIVE, HeroCampaign.roster().state(HeroId(HERO))?.status)
    }

    // ------------------------------------------------------ legacy acquisition

    /** PM's rule: no bar crossed, no roll -- however experienced the unit already is. */
    @Test
    fun aLegacyLeaderNeedsABarToBeCrossed() {
        val veteran = unit(owner = 1).apply { experience = 350 }
        repeat(ROLLS) {
            assertEquals(-1, Leaders.generateLeaderWithChance(veteran, expGained = 20))
        }
    }

    /** PM's interval `[bars, 10]` and threshold 8: 20% at the first bar, and the roll no longer dies
     *  above bar 1 the way the port's shrinking interval made it. */
    @Test
    fun aBarUpRollsPmsOdds() {
        val firstBar = successRate(experienceAfter = 120, gained = 40)
        val fourthBar = successRate(experienceAfter = 410, gained = 40)

        assertTrue(firstBar in 0.12..0.28, "about 20% at the first bar, was $firstBar")
        assertTrue(fourthBar in 0.19..0.38, "about 2/7 at the fourth bar, was $fourthBar")
    }

    @Test
    fun maximumExperienceStillGuaranteesALegacyLeader() {
        val elite = unit(owner = 1).apply { experience = UnitExperience.cap() }
        assertTrue(Leaders.generateLeaderWithChance(elite, expGained = 0) != -1)
    }

    private fun successRate(
        experienceAfter: Int,
        gained: Int,
    ): Double {
        var successes = 0
        repeat(ROLLS) {
            val candidate = unit(owner = 1).apply { experience = experienceAfter }
            if (Leaders.generateLeaderWithChance(candidate, gained) != -1) successes++
        }
        return successes.toDouble() / ROLLS
    }
}

private const val ROLLS = 600
