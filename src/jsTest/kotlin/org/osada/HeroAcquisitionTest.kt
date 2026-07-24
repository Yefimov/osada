package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.EmergenceEvent
import org.osada.hero.FormationId
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroBalance
import org.osada.hero.HeroCampaign
import org.osada.hero.LeaderAcquisitionService
import org.osada.hero.RecognitionService
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards Phase 2 acquisition: recognition (§7.1), the seeded emergence check (§7.2, §22), procedural
 * generation (§8.2, §8.3, §16) and the lifted class restriction (§12). The load-bearing property is
 * determinism (§29.17) — an emergence must not reroll on reload.
 */
class HeroAcquisitionTest {
    private companion object {
        const val TANK_EQID = 900
        const val DESTROYER_EQID = 950
        const val CAMPAIGN = "test-campaign.json"
        const val SERVICE_YEAR = 1943
        val FLOOR = HeroBalance.DEFAULT.recognitionEmergenceFloor
        val GUARANTEE = HeroBalance.DEFAULT.guaranteedAfterEligibleFailures
    }

    @BeforeTest
    fun setup() {
        js("if (typeof window.scenariolist === 'undefined') { window.scenariolist = []; }")
        Equipment.resetEquipment()
        Equipment.putEquipment(
            TANK_EQID,
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                name = "Test Tank"
                cost = 100
            },
        )
        Equipment.putEquipment(
            DESTROYER_EQID,
            EquipmentData().apply {
                uclass = UnitClass.DESTROYER.value
                name = "Test Destroyer"
                cost = 200
            },
        )
        HeroCampaign.reset()
        HeroCampaign.setContext(CAMPAIGN, scenarioIndex = 0, serviceYear = SERVICE_YEAR)
    }

    @AfterTest
    fun tearDown() {
        HeroCampaign.reset()
    }

    private fun coreUnit(
        eqid: Int = TANK_EQID,
        country: Int = 19,
        experience: Int = 0,
    ): GameUnit {
        val unit = GameUnit(eqid).apply { this.experience = experience }
        val player =
            Player().apply {
                id = 0
                this.country = country
            }
        player.addCoreUnit(unit)
        return unit
    }

    private fun kill(stronger: Boolean = false) =
        RecognitionService.Contribution(
            role = RecognitionService.Contribution.Role.ATTACKER,
            destroyedEnemy = true,
            enemyStronger = stronger,
            kills = 10,
            gainedLevel = false,
            survivedCriticalDamage = false,
        )

    private val routine =
        RecognitionService.Contribution(
            role = RecognitionService.Contribution.Role.ATTACKER,
            destroyedEnemy = false,
            enemyStronger = false,
            kills = 0,
            gainedLevel = false,
            survivedCriticalDamage = false,
        )

    /** Places a formation already primed past the emergence floor, so the next notable action checks. */
    private fun primeFormation(
        unit: GameUnit,
        recognition: Int,
        drought: Int = 0,
    ) {
        val id = assertNotNull(FormationIdentity.of(unit))
        HeroCampaign.roster().putFormation(
            CoreFormation(
                id = id,
                ownerId = unit.owner,
                country = unit.player?.country ?: -1,
                displayName = unit.unitData().name,
                currentEquipmentId = unit.eqid,
                unitClass = unit.unitData().uclass,
                recognition = recognition,
            ),
        )
        HeroCampaign.roster().drought = drought
    }

    /** Primes a formation right at the floor with a maxed drought, so the next notable action is guaranteed. */
    private fun primeAtGuarantee(unit: GameUnit) = primeFormation(unit, recognition = FLOOR, drought = GUARANTEE)

    // ------------------------------------------------------------- recognition §7.1

    @Test
    fun routineCombatEarnsNoRecognition() {
        val unit = coreUnit()
        assertFalse(HeroCampaign.recordCombat(unit, routine), "a routine attack must not emerge a leader")
        val formation = assertNotNull(HeroCampaign.roster().formation(assertNotNull(FormationIdentity.of(unit))))
        assertEquals(0, formation.recognition, "recognition comes from notable actions, not every attack")
        assertEquals("No candidate identified", HeroCampaign.recognitionStatus(unit))
    }

    @Test
    fun notableActionAccumulatesRecognition() {
        val unit = coreUnit()
        HeroCampaign.recordCombat(unit, kill(stronger = true))
        val formation = assertNotNull(HeroCampaign.roster().formation(assertNotNull(FormationIdentity.of(unit))))
        // perKill (10) + strongerKillBonus (15) with the default balance.
        assertEquals(25, formation.recognition, "destroying a stronger enemy earns recognition")
    }

    @Test
    fun scenarioOnlyUnitIsNotHandledByTheHeroSystem() {
        // No core roster join → no formation → the hero path declines it (the combat site then
        // takes the legacy integer path instead).
        val loose = GameUnit(TANK_EQID)
        assertNull(FormationIdentity.of(loose))
        assertFalse(HeroCampaign.recordCombat(loose, kill(stronger = true)))
    }

    // ------------------------------------------------------- emergence check §7.2/22

    @Test
    fun emergenceChanceRisesWithRecognitionAndDroughtButIsCapped() {
        val balance = HeroBalance.DEFAULT
        val floor = balance.recognitionEmergenceFloor
        val low = LeaderAcquisitionService.chance(floor, drought = 0, balance = balance)
        val higher = LeaderAcquisitionService.chance(floor + 100, drought = 0, balance = balance)
        val drier = LeaderAcquisitionService.chance(floor, drought = 5, balance = balance)
        assertTrue(higher > low, "more recognition means a better chance")
        assertTrue(drier > low, "a longer drought means a better chance")
        assertEquals(
            balance.maxEmergenceChance,
            LeaderAcquisitionService.chance(floor + 100000, drought = 1000, balance = balance),
            "the chance is capped below certainty",
        )
    }

    @Test
    fun belowTheFloorNoCheckRunsAndDroughtDoesNotGrow() {
        val unit = coreUnit()
        HeroCampaign.roster().drought = 3
        // A single stronger kill (25) stays under the floor (30): eligible=false, no drought change.
        assertFalse(HeroCampaign.recordCombat(unit, kill(stronger = true)))
        assertEquals(3, HeroCampaign.roster().drought, "an ineligible formation must not feed the drought counter")
    }

    @Test
    fun droughtGuaranteesAnOfficerOnTheNextEligibleAction() {
        val unit = coreUnit(experience = 350)
        primeAtGuarantee(unit)

        assertTrue(HeroCampaign.recordCombat(unit, kill(stronger = true)), "the drought guarantee produces an officer")

        val hero = assertNotNull(HeroCampaign.heroFor(unit), "the formation now has a commander")
        assertEquals(0, HeroCampaign.roster().drought, "emergence resets the campaign drought")
        assertNotNull(HeroCampaign.roster().definition(hero.heroId), "the hero has an identity")
    }

    @Test
    fun aFormationNeverGetsASecondLeader() {
        val unit = coreUnit()
        primeAtGuarantee(unit)
        assertTrue(HeroCampaign.recordCombat(unit, kill(stronger = true)))
        val firstHero = assertNotNull(HeroCampaign.heroFor(unit))

        assertFalse(HeroCampaign.recordCombat(unit, kill(stronger = true)), "a led formation is not a candidate (§4.5)")
        assertEquals(firstHero.heroId, assertNotNull(HeroCampaign.heroFor(unit)).heroId)
        assertEquals(1, HeroCampaign.roster().allDefinitions().size)
    }

    // ------------------------------------------------- determinism §7.4/29.17

    @Test
    fun theSameCheckProducesTheSameOfficerOnReplay() {
        val formation =
            CoreFormation(
                id = FormationId("F-0-1"),
                ownerId = 0,
                country = 19,
                displayName = "24th Tank Brigade",
                currentEquipmentId = TANK_EQID,
                unitClass = UnitClass.TANK.value,
                recognition = 200,
                emergenceChecks = 7,
            )
        val context =
            LeaderAcquisitionService.EmergenceContext(
                campaignId = CAMPAIGN,
                scenarioIndex = 0,
                formation = formation,
                event = EmergenceEvent.DESTROYED_STRONGER_ENEMY,
                campaignDrought = GUARANTEE,
                country = 19,
                unitExperience = 0,
                serviceYear = SERVICE_YEAR,
            )

        val first = LeaderAcquisitionService.tryGenerate(context)
        val second = LeaderAcquisitionService.tryGenerate(context)
        val a = assertNotNull(first as? LeaderAcquisitionService.EmergenceResult.Emerged)
        val b = assertNotNull(second as? LeaderAcquisitionService.EmergenceResult.Emerged)
        assertEquals(a.definition.id, b.definition.id, "reloading before the same combat must not reroll the hero")
        assertEquals(a.definition.displayName, b.definition.displayName)
    }

    // ------------------------------------------------- procedural generation §8.2/8.3

    @Test
    fun anEmergedOfficerHasBothADistinctPersonalTraitAndBackground() {
        val unit = coreUnit()
        primeAtGuarantee(unit)
        HeroCampaign.recordCombat(unit, kill(stronger = true))

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        val definition = assertNotNull(HeroCampaign.roster().definition(hero.heroId))
        assertEquals("armored_academy_graduate", definition.backgroundId, "tank officer gets the armored background")
        assertTrue(definition.displayName.isNotBlank(), "a procedural officer has a name (§29.7)")
        assertEquals(1, hero.learnedTraitIds.size, "one personal trait, distinct from the background")
        // Background (Aggressive Tank Maneuver) reaches combat, and so does the personal trait.
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER), "background trait is honoured")
    }

    @Test
    fun aPreviouslyIneligibleClassCanNowGetALeader() {
        // Destroyers were among the 13 classes that could never receive a leader (§12).
        val destroyer = coreUnit(eqid = DESTROYER_EQID, country = 7)
        primeAtGuarantee(destroyer)

        assertTrue(HeroCampaign.recordCombat(destroyer, kill(stronger = true)), "a naval formation gets an officer")
        val hero = assertNotNull(HeroCampaign.heroFor(destroyer))
        val definition = assertNotNull(HeroCampaign.roster().definition(hero.heroId))
        assertEquals("destroyer_captain", definition.backgroundId)
        // The universal background trait takes effect regardless of the naval class.
        assertTrue(Leaders.unitHasLeader(destroyer, LeaderType.AGGRESSIVE_ATTACK), "naval commander has a real bonus")
    }
}
