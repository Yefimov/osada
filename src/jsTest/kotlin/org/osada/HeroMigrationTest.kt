package org.osada

import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroPotential
import org.osada.hero.LeaderMigration
import org.osada.hero.LegacyTraitMapping
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the §25 save migration.
 *
 * The load-bearing property is the FIRST test: a unit that had a leader before the hero system
 * must fight identically after migration. The old integer granted two effective traits (the rolled
 * one plus its class's hidden signature); the migrated hero must still answer true for both, via
 * a learned trait and a professional background respectively.
 */
class HeroMigrationTest {
    private companion object {
        const val TANK_EQID = 900
        const val CAMPAIGN = "test-campaign.json"
        const val VETERAN_EXPERIENCE = 350
    }

    private fun tankUnit(
        leader: Int,
        experience: Int = 0,
    ): GameUnit =
        GameUnit(TANK_EQID).apply {
            owner = 0
            this.leader = leader
            this.experience = experience
            player = Player().apply { id = 0 }
        }

    private fun playerWith(vararg units: GameUnit): Player =
        Player().apply {
            id = 0
            units.forEach { addCoreUnit(it) }
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
            },
        )
        HeroCampaign.reset()
    }

    @AfterTest
    fun tearDown() {
        // The roster is campaign-global; leaking it would change other tests' trait resolution.
        HeroCampaign.reset()
    }

    @Test
    fun migratedUnitKeepsBothEffectiveTraits() {
        // AGGRESSIVE_ATTACK is the rolled trait; AGGRESSIVE_TANK_MANEUVER is the tank class
        // signature the old system granted invisibly alongside it.
        val unit = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        val player = playerWith(unit)

        assertTrue(Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_ATTACK), "rolled trait before migration")
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER), "signature before migration")

        LeaderMigration.migrate(player, CAMPAIGN)

        assertTrue(Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_ATTACK), "rolled trait survives migration")
        assertTrue(
            Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER),
            "class signature survives migration, now via the professional background",
        )
        assertTrue(
            !Leaders.unitHasLeader(unit, LeaderType.FIRST_STRIKE),
            "migration must not grant traits the unit never had",
        )
    }

    @Test
    fun migrationAttributesTheSignatureTraitToAnExplicitBackground() {
        val unit = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        LeaderMigration.migrate(playerWith(unit), CAMPAIGN)

        val hero = assertNotNull(HeroCampaign.heroFor(unit), "core unit with a leader gets a hero")
        val definition = assertNotNull(HeroCampaign.roster().definition(hero.heroId))

        assertEquals(
            "armored_academy_graduate",
            definition.backgroundId,
            "the hidden tank class signature becomes a named, inspectable background",
        )
        assertEquals(
            setOf(LegacyTraitMapping.toTraitId(LeaderType.AGGRESSIVE_ATTACK)),
            hero.learnedTraitIds,
            "only the rolled trait is a learned trait; the signature is not duplicated there",
        )
    }

    @Test
    fun leaderlessCoreUnitGetsAFormationButNoHero() {
        val unit = tankUnit(leader = -1)
        LeaderMigration.migrate(playerWith(unit), CAMPAIGN)

        val formationId = assertNotNull(FormationIdentity.of(unit))
        assertNotNull(
            HeroCampaign.roster().formation(formationId),
            "a leaderless formation must still exist — it is what accumulates recognition",
        )
        assertNull(HeroCampaign.heroFor(unit), "no leader integer means no reconstructed hero")
    }

    @Test
    fun migrationIsIdempotent() {
        val unit = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        val player = playerWith(unit)

        LeaderMigration.migrate(player, CAMPAIGN)
        val first = assertNotNull(HeroCampaign.heroFor(unit))
        LeaderMigration.migrate(player, CAMPAIGN)
        val second = assertNotNull(HeroCampaign.heroFor(unit))

        assertEquals(first.heroId, second.heroId, "re-loading a migrated save must not add a second officer")
        assertEquals(1, HeroCampaign.roster().allDefinitions().size)
    }

    @Test
    fun migrationIsDeterministicAcrossReloads() {
        val unit = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        LeaderMigration.migrate(playerWith(unit), CAMPAIGN)
        val firstName =
            HeroCampaign
                .roster()
                .allDefinitions()
                .single()
                .displayName

        // Simulate quitting and reloading the same save: fresh roster, same inputs.
        HeroCampaign.reset()
        val reloaded = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        reloaded.formationId = unit.formationId
        LeaderMigration.migrate(playerWith(reloaded), CAMPAIGN)

        assertEquals(
            firstName,
            HeroCampaign
                .roster()
                .allDefinitions()
                .single()
                .displayName,
            "reloading must not reroll the officer (§29.17)",
        )
    }

    @Test
    fun potentialReflectsFormationExperience() {
        val green = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)
        val veteran = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value, experience = VETERAN_EXPERIENCE)
        LeaderMigration.migrate(playerWith(green, veteran), CAMPAIGN)

        assertEquals(HeroPotential.LINE_OFFICER, assertNotNull(HeroCampaign.heroFor(green)).potential)
        assertEquals(HeroPotential.PROMISING, assertNotNull(HeroCampaign.heroFor(veteran)).potential)
    }

    @Test
    fun scenarioOnlyUnitsStillUseTheLegacyPath() {
        // Never added to a core roster, so it has no formation and no hero — exactly the case
        // ScenarioUnitParser produces. It must keep resolving traits the old way.
        val scenarioUnit = tankUnit(leader = LeaderType.AGGRESSIVE_ATTACK.value)

        assertNull(FormationIdentity.of(scenarioUnit))
        assertTrue(Leaders.unitHasLeader(scenarioUnit, LeaderType.AGGRESSIVE_ATTACK))
        assertTrue(Leaders.unitHasLeader(scenarioUnit, LeaderType.AGGRESSIVE_TANK_MANEUVER))
    }
}
