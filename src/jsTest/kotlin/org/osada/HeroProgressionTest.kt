package org.osada

import org.osada.hero.CommandAttribute
import org.osada.hero.CommandAttributes
import org.osada.hero.CoreFormation
import org.osada.hero.EvidenceCategory
import org.osada.hero.FormationEvent
import org.osada.hero.FormationId
import org.osada.hero.FormationIdentity
import org.osada.hero.HeroBackgrounds
import org.osada.hero.HeroBalance
import org.osada.hero.HeroBiographyFacts
import org.osada.hero.HeroCampaign
import org.osada.hero.HeroDefinition
import org.osada.hero.HeroEvent
import org.osada.hero.HeroId
import org.osada.hero.HeroMedal
import org.osada.hero.HeroMedals
import org.osada.hero.HeroOrigin
import org.osada.hero.HeroRenown
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.HeroState
import org.osada.hero.HeroTraitCatalog
import org.osada.hero.LegacyTraitMapping
import org.osada.hero.PortraitComposition
import org.osada.hero.RecognitionService
import org.osada.model.CombatLeaderAcquisition
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.Transport
import org.osada.model.move
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards Phase 3 progression: evidence tracks (§8.4, §21), leader XP and renown (§8.1, §4.4),
 * promotion choices (§8.5) and the trait catalogue (§20). Complements `HeroAcquisitionTest`, which
 * covers the leaderless path this phase does not touch.
 */
class HeroProgressionTest {
    private companion object {
        const val TANK_EQID = 910
        const val TRUCK_EQID = 911
        const val CAMPAIGN = "progression-campaign.json"
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
        HeroCampaign.reset()
        HeroCampaign.setContext(CAMPAIGN, scenarioIndex = 1, serviceYear = 1943)
    }

    @AfterTest
    fun tearDown() {
        HeroCampaign.reset()
    }

    private fun coreUnit(): GameUnit {
        val unit = GameUnit(TANK_EQID)
        val player =
            Player().apply {
                id = 0
                country = 19
            }
        player.addCoreUnit(unit)
        return unit
    }

    /** Attaches an already-commanded formation to [unit], bypassing the Phase 2 emergence flow. */
    private fun ledFormation(
        unit: GameUnit,
        heroId: HeroId = HeroId("H-test"),
    ): CoreFormation {
        val formationId = assertNotNull(FormationIdentity.of(unit))
        val formation =
            CoreFormation(
                id = formationId,
                ownerId = unit.owner,
                country = 19,
                displayName = "24th Tank Brigade",
                currentEquipmentId = TANK_EQID,
                unitClass = UnitClass.TANK.value,
                assignedHeroId = heroId,
            )
        HeroCampaign.roster().putFormation(formation)
        HeroCampaign.roster().putHero(
            HeroDefinition(
                id = heroId,
                origin = HeroOrigin.PROCEDURAL,
                displayName = "K. Novak",
                backgroundId = "armored_academy_graduate",
                biographyFacts = HeroBiographyFacts(emergenceEventId = "test"),
                portrait = PortraitComposition(seed = 1),
            ),
            HeroState(heroId = heroId, rankId = "lieutenant", assignedFormationId = formationId),
        )
        return formation
    }

    private fun strongerKill() =
        RecognitionService.Contribution(
            role = RecognitionService.Contribution.Role.ATTACKER,
            destroyedEnemy = true,
            enemyStronger = true,
            kills = 10,
            gainedLevel = false,
            survivedCriticalDamage = false,
            enemyUnitClass = UnitClass.TANK.value,
        )

    private fun riverStand() =
        RecognitionService.Contribution(
            role = RecognitionService.Contribution.Role.DEFENDER,
            destroyedEnemy = false,
            enemyStronger = false,
            kills = 0,
            gainedLevel = false,
            survivedCriticalDamage = true,
            terrain = TerrainType.RIVER.value,
        )

    private fun routine() =
        RecognitionService.Contribution(
            role = RecognitionService.Contribution.Role.ATTACKER,
            destroyedEnemy = false,
            enemyStronger = false,
            kills = 0,
            gainedLevel = false,
            survivedCriticalDamage = false,
        )

    // ----------------------------------------------------------- evidence / xp §8.4/§21

    @Test
    fun ledFormationEarnsEvidenceAndXpFromANotableAction() {
        val unit = coreUnit()
        ledFormation(unit)

        assertFalse(HeroCampaign.recordCombat(unit, strongerKill()), "progression is never a leader-gain bounce")

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals(HeroBalance.DEFAULT.leaderXpPerCombat, hero.experience)
        assertEquals(25, hero.specializationEvidence[EvidenceCategory.OFFENSIVE_OPERATIONS.name])
        assertEquals(
            20,
            hero.specializationEvidence[EvidenceCategory.ARMORED_COMBAT.name],
            "a tank kill is also armor evidence",
        )
    }

    @Test
    fun terrainEvidenceComesFromTheContestedHex() {
        val unit = coreUnit()
        ledFormation(unit)

        HeroCampaign.recordCombat(unit, riverStand())

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals(20, hero.specializationEvidence[EvidenceCategory.RIVER_OPERATIONS.name])
        assertEquals(
            25,
            hero.specializationEvidence[EvidenceCategory.DEFENSIVE_OPERATIONS.name],
            "holding under attack",
        )
    }

    @Test
    fun routineActionOnALedFormationChangesNothing() {
        val unit = coreUnit()
        ledFormation(unit)
        val before = assertNotNull(HeroCampaign.heroFor(unit))

        HeroCampaign.recordCombat(unit, routine())

        assertEquals(before, HeroCampaign.heroFor(unit), "no XP, no evidence, no history for an unremarkable action")
    }

    // -------------------------------------------------------------------- renown §4.4/§8.1

    @Test
    fun renownRisesOnceAccumulatedExperienceCrossesItsThreshold() {
        val unit = coreUnit()
        ledFormation(unit)
        val combatsNeeded = HeroBalance.DEFAULT.renownThresholds[0] / HeroBalance.DEFAULT.leaderXpPerCombat + 1

        repeat(combatsNeeded) { HeroCampaign.recordCombat(unit, strongerKill()) }

        assertEquals(HeroRenown.EXPERIENCED, assertNotNull(HeroCampaign.heroFor(unit)).renown)
    }

    // --------------------------------------------------------------------------- medals §8.1

    @Test
    fun aStrongerKillEarnsTheValorMedalExactlyOnce() {
        val unit = coreUnit()
        ledFormation(unit)

        HeroCampaign.recordCombat(unit, strongerKill())
        HeroCampaign.recordCombat(unit, strongerKill())

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals(1, hero.medals.count { it.medalId == HeroMedals.VALOR_MEDAL_ID })
    }

    // ----------------------------------------------------------------------- promotion §8.5

    @Test
    fun promotionFiresAtTheFirstThresholdAndAdvancesRank() {
        val unit = coreUnit()
        ledFormation(unit)
        val combatsNeeded = HeroBalance.DEFAULT.promotionThresholds[0] / HeroBalance.DEFAULT.leaderXpPerCombat + 1

        repeat(combatsNeeded) { HeroCampaign.recordCombat(unit, strongerKill()) }

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals("captain", hero.rankId, "one rank up from lieutenant")
        assertEquals(1, hero.promotionsAwarded)
    }

    @Test
    fun applyingAPromotionChoiceLearnsTheTraitAndRaisesAnAttribute() {
        val unit = coreUnit()
        ledFormation(unit)
        val combatsNeeded = HeroBalance.DEFAULT.promotionThresholds[0] / HeroBalance.DEFAULT.leaderXpPerCombat + 1
        repeat(combatsNeeded) { HeroCampaign.recordCombat(unit, strongerKill()) }
        val hero = assertNotNull(HeroCampaign.heroFor(unit))

        val backgroundTrait = HeroBackgrounds.byId("armored_academy_graduate")?.grantedTrait
        val choice = HeroTraitCatalog.choose(hero, backgroundTrait, UnitClass.TANK.value).first()
        HeroCampaign.applyPromotionChoice(hero.heroId, choice.id)

        val updated = assertNotNull(HeroCampaign.heroFor(unit))
        assertTrue(LegacyTraitMapping.toTraitId(choice.legacyTrait) in updated.learnedTraitIds)
        assertEquals(
            attributeValue(hero.attributes, choice.categoryId.attribute) + 1,
            attributeValue(updated.attributes, choice.categoryId.attribute),
            "the chosen trait's category attribute rises by one",
        )
    }

    private fun attributeValue(
        attributes: CommandAttributes,
        attribute: CommandAttribute,
    ): Int =
        when (attribute) {
            CommandAttribute.OFFENSE -> attributes.offense
            CommandAttribute.DEFENSE -> attributes.defense
            CommandAttribute.MANEUVER -> attributes.maneuver
            CommandAttribute.COORDINATION -> attributes.coordination
        }

    @Test
    fun classGeneralFallbacksFillOutAPromotionWhenEvidenceIsThin() {
        val hero = HeroState(heroId = HeroId("H-thin"), rankId = "lieutenant")

        val choices = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.INFANTRY.value)

        assertEquals(2, choices.size)
        assertTrue(
            choices.all { it.requiredEvidence.isEmpty() },
            "with no evidence yet, only always-available options qualify",
        )
    }

    @Test
    fun promotionChoiceSelectionIsPureAndDeterministic() {
        val hero =
            HeroState(
                heroId = HeroId("H-det"),
                rankId = "lieutenant",
                specializationEvidence = mapOf(EvidenceCategory.DEFENSIVE_OPERATIONS.name to 90),
            )

        val first = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.TANK.value)
        val second = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.TANK.value)

        assertEquals(first.map { it.id }, second.map { it.id }, "the same state must always offer the same pair")
    }

    // ------------------------------------------------- newly-wired traits, DEFERRED.md §1.6/§7.43

    @Test
    fun groundAttackSpecialistIsOnlyOfferedToAirClasses() {
        val hero = HeroState(heroId = HeroId("H-air"), rankId = "lieutenant")

        val tankChoices = HeroTraitCatalog.eligibleFor(hero, backgroundTrait = null, unitClass = UnitClass.TANK.value)
        val fighterChoices =
            HeroTraitCatalog.eligibleFor(hero, backgroundTrait = null, unitClass = UnitClass.FIGHTER.value)

        assertTrue(
            tankChoices.none { it.id == "ground_attack_specialist" },
            "attacking a ground target from the air makes no sense for a tank's own leader",
        )
        assertTrue(fighterChoices.any { it.id == "ground_attack_specialist" })
    }

    @Test
    fun reconMovementTraitGrantsPhasedMovementToANonReconFormation() {
        val unit = coreUnit()
        ledFormation(unit)
        unit.moveLeft = 5

        unit.move(1)
        assertTrue(unit.hasMoved, "an ordinary tank has no phased movement without the trait")

        unit.hasMoved = false
        unit.moveLeft = 5
        HeroCampaign.applyPromotionChoice(HeroId("H-test"), "fluid_maneuver")

        unit.move(1)
        assertFalse(
            unit.hasMoved,
            "Fluid Maneuver (RECON_MOVEMENT) grants phased movement off the Recon class too",
        )
    }

    /**
     * The guard that was missing when §7.42 added three zero-evidence entries and silently took
     * both fallback slots. `choose` breaks ties by `id`, so the fallback pair is only stable while
     * exactly two entries are unGated — this pins the pair itself, not merely its shape.
     */
    @Test
    fun promotionFallbackPairIsTheTwoClassGeneralOptions() {
        val hero = HeroState(heroId = HeroId("H-thin2"), rankId = "lieutenant")

        val infantry = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.INFANTRY.value)
        val fighter = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.FIGHTER.value)

        assertEquals(listOf("steady_hand", "veteran_instincts"), infantry.map { it.id }.sorted())
        assertEquals(
            listOf("steady_hand", "veteran_instincts"),
            fighter.map { it.id }.sorted(),
            "an air formation with no evidence gets the same class-general pair, not a free specialisation",
        )
        assertEquals(
            2,
            HeroTraitCatalog.ALL.count { it.requiredEvidence.isEmpty() },
            "a third zero-evidence entry would change every early promotion in the game",
        )
    }

    @Test
    fun theOnceUngatedTraitsAreOfferedOnMeritWhenTheirEvidenceExists() {
        val hero =
            HeroState(
                heroId = HeroId("H-mobile"),
                rankId = "lieutenant",
                specializationEvidence = mapOf(EvidenceCategory.MOBILE_WARFARE.name to 60),
            )

        val choices = HeroTraitCatalog.choose(hero, backgroundTrait = null, unitClass = UnitClass.TANK.value)

        assertEquals("fluid_maneuver", choices.first().id, "the best-justified eligible trait comes first")
    }

    @Test
    fun everyTraitWithALiveRuleEffectIsNowReachable() {
        val catalogued = HeroTraitCatalog.ALL.map { it.legacyTrait }.toSet()

        assertTrue(LeaderType.SUPERIOR_MANEUVER in catalogued, "ZOC bypass had a rule but no way to earn it")
        assertTrue(LeaderType.FEROCIOUS_DEFENSE in catalogued, "hero path had no entry for it")
    }

    // ------------------------------------------- the three newly fed evidence categories §7.43

    @Test
    fun airKillOnAGroundTargetFeedsGroundAttackEvidence() {
        val unit = coreUnit()
        ledFormation(unit)

        HeroCampaign.recordCombat(unit, strongerKill().copy(attackedGroundFromAir = true))

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals(20, hero.specializationEvidence[EvidenceCategory.GROUND_ATTACK.name])
    }

    @Test
    fun onlyAKillTheFormationDroveToFeedsMobileWarfareEvidence() {
        val stationary = coreUnit()
        ledFormation(stationary, HeroId("H-static"))
        HeroCampaign.recordCombat(stationary, strongerKill().copy(closedDistanceBeforeAttack = false))
        assertEquals(
            null,
            assertNotNull(HeroCampaign.heroFor(stationary)).specializationEvidence[
                EvidenceCategory.MOBILE_WARFARE.name,
            ],
            "shelling from where it already stood is not mobile warfare",
        )

        val mobile = coreUnit()
        ledFormation(mobile, HeroId("H-mobile2"))
        HeroCampaign.recordCombat(mobile, strongerKill().copy(closedDistanceBeforeAttack = true))
        assertEquals(
            15,
            assertNotNull(HeroCampaign.heroFor(mobile)).specializationEvidence[EvidenceCategory.MOBILE_WARFARE.name],
        )
    }

    @Test
    fun aManeuverKillNeedsTheAttackerRole() {
        val unit = coreUnit()
        ledFormation(unit)

        HeroCampaign.recordCombat(
            unit,
            riverStand().copy(destroyedEnemy = true, closedDistanceBeforeAttack = true),
        )

        assertEquals(
            null,
            assertNotNull(HeroCampaign.heroFor(unit)).specializationEvidence[EvidenceCategory.MOBILE_WARFARE.name],
            "a defender never closed any distance, whatever the flag says",
        )
    }

    /**
     * `unitEndTurn` refills `moveLeft` from the unit's OWN equipment, but `unitData()` resolves to
     * the transport when mounted — so comparing the two would have credited a mobile-warfare kill to
     * every mounted formation that never moved.
     */
    @Test
    fun aMountedFormationThatNeverMovedScoresNoManeuverEvidence() {
        val unit = coreUnit()
        ledFormation(unit)
        Equipment.putEquipment(
            TRUCK_EQID,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                name = "Test Truck"
                movpoints = 8
            },
        )
        unit.transport = Transport(TRUCK_EQID)
        unit.isMounted = true
        unit.moveLeft = unit.unitData(true).movpoints

        assertTrue(
            unit.unitData().movpoints > unit.unitData(true).movpoints,
            "precondition: the transport out-runs the formation, which is what made this wrong",
        )
        assertFalse(
            CombatLeaderAcquisition.spentMovementThisTurn(unit),
            "a full tank of movement is a full tank of movement, mounted or not",
        )

        unit.moveLeft -= 1
        assertTrue(CombatLeaderAcquisition.spentMovementThisTurn(unit), "and spending any of it still counts")
    }

    @Test
    fun reconContactFeedsReconnaissanceEvidenceOncePerTurn() {
        val unit = coreUnit()
        ledFormation(unit)

        assertTrue(HeroCampaign.recordReconnaissance(unit, newlySpotted = 3, turn = 4))
        assertFalse(
            HeroCampaign.recordReconnaissance(unit, newlySpotted = 3, turn = 4),
            "shuttling in and out of spotting range must not bank the evidence twice in a turn",
        )
        assertTrue(HeroCampaign.recordReconnaissance(unit, newlySpotted = 1, turn = 5))

        val hero = assertNotNull(HeroCampaign.heroFor(unit))
        assertEquals(16, hero.specializationEvidence[EvidenceCategory.RECONNAISSANCE.name], "two turns, 8 each")
        assertEquals(0, hero.experience, "spotting is not a notable action: evidence only, no leader XP")
        assertTrue(hero.serviceEvents.isEmpty(), "and no dossier line per hex revealed")
    }

    @Test
    fun reconContactIsIgnoredForAFormationWithNoCommander() {
        val unit = coreUnit()

        assertFalse(
            HeroCampaign.recordReconnaissance(unit, newlySpotted = 2, turn = 1),
            "there is nobody to credit, and recognition deliberately does not accrue from spotting",
        )
    }

    // -------------------------------------------------------------------- persistence §29.12

    @Test
    fun progressionFieldsSurviveASaveRoundTrip() {
        val formationId = FormationId("F-0-9")
        val heroId = HeroId("H-F-0-9")
        val roster =
            HeroRoster().apply {
                putFormation(
                    CoreFormation(
                        id = formationId,
                        ownerId = 0,
                        country = 3,
                        displayName = "Test Brigade",
                        currentEquipmentId = TANK_EQID,
                        unitClass = UnitClass.TANK.value,
                        assignedHeroId = heroId,
                        history = listOf(FormationEvent("destroyed_stronger_enemy", "1", 4)),
                    ),
                )
                putHero(
                    HeroDefinition(
                        id = heroId,
                        origin = HeroOrigin.PROCEDURAL,
                        displayName = "K. Novak",
                        backgroundId = "armored_academy_graduate",
                        biographyFacts = HeroBiographyFacts(emergenceEventId = "test"),
                        portrait = PortraitComposition(seed = 1),
                    ),
                    HeroState(
                        heroId = heroId,
                        rankId = "captain",
                        promotionsAwarded = 1,
                        nicknameId = "the_hammer",
                        medals = listOf(HeroMedal(HeroMedals.VALOR_MEDAL_ID, "1")),
                        serviceEvents = listOf(HeroEvent("promoted_to_captain", "1", 4)),
                    ),
                )
            }

        val restored = HeroSerializer.deserialize(JSON.parse(JSON.stringify(HeroSerializer.serialize(roster))))

        val formation = assertNotNull(restored.formation(formationId))
        assertEquals(listOf("destroyed_stronger_enemy"), formation.history.map { it.eventId })

        val state = assertNotNull(restored.assignedHero(formationId))
        assertEquals(1, state.promotionsAwarded)
        assertEquals("the_hammer", state.nicknameId)
        assertEquals(listOf(HeroMedals.VALOR_MEDAL_ID), state.medals.map { it.medalId })
        assertEquals(listOf("promoted_to_captain"), state.serviceEvents.map { it.eventId })
    }
}
