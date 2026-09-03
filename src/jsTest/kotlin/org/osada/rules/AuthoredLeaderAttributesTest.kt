package org.osada.rules

import org.osada.GameStateSerializer
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.UnitClass
import org.osada.UnitType
import org.osada.hero.HeroCampaign
import org.osada.hero.LeaderMigration
import org.osada.hero.LegacyTraitMapping
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.model.Player
import org.osada.model.applySerializedScenarioProperties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OG's TWO authored leader attributes, and the rule that they never collapse into one.
 *
 * OpenSuite's Leader tab has two selectors with different defaults, and `.xscn` stores them in two
 * bytes: `@36` *"According list of leaders"* is picked per formation, `@37` *"According unit's
 * class"* is the class signature. `tools/og-import/add_leader_traits.py` carries the evidence for
 * which byte is which; this suite is about what the runtime does with them.
 *
 * The load-bearing test is [aDefaultDefaultFormationKeepsTheOrdinaryGeneratedPair]: the ~3,000
 * deployed formations that author neither selector must behave exactly as they did before either
 * field existed.
 */
class AuthoredLeaderAttributesTest : OgRulesTestHarness() {
    private val batteryEqid = 970
    private val bunkerEqid = 971

    @BeforeTest
    fun setup() {
        installTestWorld()
        HeroCampaign.reset()
        Equipment.putEquipment(
            batteryEqid,
            EquipmentData().apply {
                name = "Gun Battery"
                uclass = UnitClass.ARTILLERY.value
                target = UnitType.SOFT.value
                movmethod = MovMethod.TOWED.value
                movpoints = 2
            },
        )
        Equipment.putEquipment(
            bunkerEqid,
            EquipmentData().apply {
                name = "Casemate"
                uclass = UnitClass.FORTIFICATION.value
                target = UnitType.SOFT.value
            },
        )
    }

    @AfterTest
    fun teardown() {
        // The roster is campaign-global; leaking it would change other suites' trait resolution.
        HeroCampaign.reset()
        clearTestWorld()
    }

    private fun formation(
        eqid: Int,
        individual: Int = -1,
        classTrait: Int = -1,
    ): GameUnit =
        GameUnit(eqid).apply {
            owner = 0
            leader = individual
            leaderClassTrait = classTrait
            player = Player().apply { id = 0 }
        }

    /** Every [LeaderType] the unit answers true for — "has each trait once" is a set, not a count,
     *  because `unitHasLeader` is a membership question at all ten combat call sites. */
    private fun traitsOf(unit: GameUnit): Set<LeaderType> =
        LeaderType.entries.filter { Leaders.unitHasLeader(unit, it) }.toSet()

    /**
     * The backlog's own worked example: an artillery formation authored Marksman (`@37`, the class
     * attribute) plus Influence (`@36`, the individual one). Both, each once, and nothing else.
     */
    @Test
    fun anAuthoredPairGrantsExactlyItsTwoTraits() {
        val unit =
            formation(
                batteryEqid,
                individual = LeaderType.INFLUENCE.value,
                classTrait = LeaderType.MARKSMAN.value,
            )
        assertEquals(
            setOf(LeaderType.MARKSMAN, LeaderType.INFLUENCE),
            traitsOf(unit),
            "the authored pair, and no third trait",
        )
    }

    /**
     * The 497-scenario case. With neither selector authored the formation gets the rolled trait plus
     * the DERIVED class signature, which for artillery is Marksman — byte for byte the pre-2026
     * behaviour.
     */
    @Test
    fun aDefaultDefaultFormationKeepsTheOrdinaryGeneratedPair() {
        val unit = formation(batteryEqid, individual = LeaderType.FIRE_DISCIPLINE.value)
        assertEquals(-1, unit.leaderClassTrait, "no override authored")
        assertEquals(
            setOf(LeaderType.MARKSMAN, LeaderType.FIRE_DISCIPLINE),
            traitsOf(unit),
            "rolled trait plus the class signature the class list derives",
        )
    }

    /** The override replaces the derivation rather than adding to it. */
    @Test
    fun theOverrideReplacesTheDerivedClassSignature() {
        val unit =
            formation(
                batteryEqid,
                individual = LeaderType.FIRE_DISCIPLINE.value,
                classTrait = LeaderType.TENACIOUS_DEFENSE.value,
            )
        assertEquals(LeaderType.TENACIOUS_DEFENSE.value, Leaders.getUnitClassLeader(unit))
        assertFalse(
            Leaders.unitHasLeader(unit, LeaderType.MARKSMAN),
            "the derived artillery signature is overridden, not kept alongside",
        )
    }

    /**
     * Thirteen unit classes have an EMPTY leader list — a Panzer Marshal inheritance rather than an
     * OG rule (`HeroBackgrounds` §12 lifts the same restriction for heroes). For those the override
     * is the only way an authored class attribute can exist, so it must not be filtered by the pool.
     */
    @Test
    fun theOverrideWorksOnAClassWithNoLeaderPoolAtAll() {
        val unit =
            formation(
                bunkerEqid,
                individual = LeaderType.DETERMINED_DEFENSE.value,
                classTrait = LeaderType.FEROCIOUS_DEFENSE.value,
            )
        assertEquals(
            setOf(LeaderType.DETERMINED_DEFENSE, LeaderType.FEROCIOUS_DEFENSE),
            traitsOf(unit),
        )
    }

    /** OG's second attribute belongs to a LEADER; a formation without one has neither half. */
    @Test
    fun anOverrideWithoutALeaderGrantsNothing() {
        val unit = formation(batteryEqid, individual = -1, classTrait = LeaderType.MARKSMAN.value)
        assertEquals(emptySet(), traitsOf(unit))
        assertEquals(-1, Leaders.getUnitClassLeader(unit))
    }

    /** A save is text, and an authored attribute that does not survive one is not deployed. */
    @Test
    fun theOverrideSurvivesASaveRoundTrip() {
        val unit =
            formation(
                batteryEqid,
                individual = LeaderType.INFLUENCE.value,
                classTrait = LeaderType.MARKSMAN.value,
            )
        val restored = GameUnit(batteryEqid)
        restored.applySerializedScenarioProperties(reparse(GameStateSerializer.serializeUnit(unit)))
        assertEquals(LeaderType.MARKSMAN.value, restored.leaderClassTrait)

        val plain = GameUnit(batteryEqid)
        plain.applySerializedScenarioProperties(
            reparse(GameStateSerializer.serializeUnit(formation(batteryEqid, individual = 9))),
        )
        assertEquals(-1, plain.leaderClassTrait, "an absent key is 'derive it', as in every old save")
    }

    /**
     * A Make-Core formation carrying an authored override joins the campaign roster and gains a
     * hero — at which point [org.osada.hero.HeroTraitResolver] stops reading the legacy integers.
     * The override has to arrive as a learned trait or it is lost at exactly that moment.
     */
    @Test
    fun theOverrideSurvivesTheHeroMigrationThatStopsReadingIt() {
        val unit =
            formation(
                batteryEqid,
                individual = LeaderType.INFLUENCE.value,
                classTrait = LeaderType.FEROCIOUS_DEFENSE.value,
            )
        val player = Player().apply { id = 0 }
        player.addCoreUnit(unit)

        LeaderMigration.migrate(player, "authored-leader-test.json")

        val hero = assertNotNull(HeroCampaign.heroFor(unit), "a led core formation gets an officer")
        assertTrue(
            LegacyTraitMapping.toTraitId(LeaderType.FEROCIOUS_DEFENSE) in hero.learnedTraitIds,
            "the authored class attribute is carried as a learned trait — a background is chosen " +
                "by class and cannot express a per-formation override",
        )
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.INFLUENCE), "individual attribute survives")
        assertTrue(Leaders.unitHasLeader(unit, LeaderType.FEROCIOUS_DEFENSE), "override survives")
    }
}
