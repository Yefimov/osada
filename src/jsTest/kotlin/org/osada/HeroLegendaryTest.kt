package org.osada

import org.osada.hero.CoreFormation
import org.osada.hero.EmergenceEvent
import org.osada.hero.FormationId
import org.osada.hero.HeroBackgrounds
import org.osada.hero.HeroBalance
import org.osada.hero.HeroPotential
import org.osada.hero.HeroRoster
import org.osada.hero.HeroSerializer
import org.osada.hero.LeaderAcquisitionService
import org.osada.hero.LegacyTraitMapping
import org.osada.hero.LegendaryHeroPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards Phase 5 legendary heroes (design brief §6, §23, §6.5 → §29.5, §29.6): the campaign reserves
 * one authored hero deterministically, forces it into the opening battles as an organic emergence,
 * gives it a rule-changing signature that reaches combat, and persists the reservation.
 */
class HeroLegendaryTest {
    private fun formation(unitClass: Int) =
        CoreFormation(
            id = FormationId("F-L"),
            ownerId = 0,
            country = 1,
            displayName = "1st Guards Tank Brigade",
            currentEquipmentId = 900,
            unitClass = unitClass,
            recognition = 40,
        )

    private fun context(
        scenarioIndex: Int,
        unitClass: Int,
        legendaryId: String?,
    ) = LeaderAcquisitionService.EmergenceContext(
        campaignId = "uranus",
        scenarioIndex = scenarioIndex,
        formation = formation(unitClass),
        event = EmergenceEvent.DISTINGUISHED_SERVICE,
        campaignDrought = 0,
        country = 1,
        unitExperience = 120,
        serviceYear = 1942,
        reservedLegendary = legendaryId?.let(LegendaryHeroPool::byId),
        // This combat is itself the qualifying one for the early-legendary onboarding roll
        // (LeaderAcquisitionService.resolveEarlyLegendary bails out at 0 — see its `<= 0` guard).
        earlyLegendaryQualifyingCombats = 1,
    )

    @Test
    fun reservationIsDeterministic() {
        assertEquals(LegendaryHeroPool.reserve("uranus", 1942), LegendaryHeroPool.reserve("uranus", 1942))
        assertNotNull(LegendaryHeroPool.reserve("uranus", 1942))
    }

    @Test
    fun everyShippedCampaignPlayerSideHasAnAuthoredLegendaryCandidate() {
        val campaigns =
            listOf(
                Triple("062d.json", 61, 1942),
                Triple("camp6.json", 61, 1942),
                Triple("rcampdfr.json", 61, 1941),
                Triple("camp6bn9.json", 19, 1936),
                Triple("camp6bn5.json", 19, 1941),
                Triple("reddestiny.json", 19, 1946),
                Triple("forward.json", 89, 1939),
                Triple("ga4.json", 89, 1939),
                Triple("ccampdfc.json", 103, 1918),
                Triple("volarm.json", 103, 1918),
                Triple("polsov.json", 103, 1919),
                Triple("simpob.json", 100, 1918),
                Triple("camp6bn8.json", 43, 1941),
                Triple("ncampdfn.json", 25, 1950),
                Triple("aljf.json", 196, 1848),
                Triple("rhu.json", 187, 1919),
                Triple("novemberrevolution.json", 188, 1918),
                Triple("acampdf2.json", 144, 1917),
                Triple("gce.json", 226, 1936),
                Triple("spa.json", 310, -72),
                Triple("nvc.json", 276, 1964),
                Triple("rsoc.json", 21, 1927),
                Triple("camp6bn4.json", 39, 1940),
            )
        val availableClasses =
            setOf(
                UnitClass.INFANTRY.value,
                UnitClass.TANK.value,
                UnitClass.RECON.value,
                UnitClass.ANTI_TANK.value,
                UnitClass.ARTILLERY.value,
            )

        campaigns.forEach { (campaign, country, year) ->
            val reservation = LegendaryHeroPool.reserve(campaign, country, year, availableClasses)
            assertNotEquals(
                LegendaryHeroPool.PROCEDURAL_FALLBACK_ID,
                reservation,
                "$campaign / country $country",
            )
            assertNotNull(LegendaryHeroPool.byId(reservation), "$campaign must reserve an authored hero")
        }
    }

    @Test
    fun compatibilityGatesByUnitClass() {
        val tankAce = assertNotNull(LegendaryHeroPool.byId("ussr_breakthrough"))
        assertTrue(LegendaryHeroPool.compatible(tankAce, UnitClass.TANK.value, 1942))
        assertTrue(!LegendaryHeroPool.compatible(tankAce, UnitClass.INFANTRY.value, 1942))
        assertTrue(!LegendaryHeroPool.compatible(tankAce, UnitClass.TANK.value, 1950))
    }

    @Test
    fun legendaryIsForcedByItsDeadlineAsAnOrganicEmergence() {
        val ctx =
            context(
                scenarioIndex = HeroBalance.DEFAULT.legendaryGuaranteedByScenarioIndex,
                unitClass = UnitClass.TANK.value,
                legendaryId = "ussr_breakthrough",
            )
        val result = LeaderAcquisitionService.tryGenerate(ctx)
        assertTrue(result is LeaderAcquisitionService.EmergenceResult.Emerged)
        val emerged = result
        assertTrue(emerged.legendary)
        assertTrue(!emerged.guaranteed, "the legendary must never be announced as guaranteed (§6.3)")
        assertEquals(HeroPotential.AUTHORED_LEGENDARY, emerged.state.potential)
    }

    @Test
    fun signatureAbilityReachesTheHeroAndCombat() {
        val ctx = context(scenarioIndex = 1, unitClass = UnitClass.TANK.value, legendaryId = "ussr_breakthrough")
        val emerged = LeaderAcquisitionService.tryGenerate(ctx) as LeaderAcquisitionService.EmergenceResult.Emerged
        // The signature is Overwhelming Attack — a real combat-honoured trait — carried as a learned trait.
        val signature = LegacyTraitMapping.toTraitId(LeaderType.OVERWHELMING_ATTACK)
        assertEquals(signature, emerged.definition.signatureTraitId)
        assertTrue(signature in emerged.state.learnedTraitIds)
    }

    @Test
    fun civilWarLineOfficerSignatureDoesNotDuplicateTheProfessionalBackground() {
        val hero = assertNotNull(LegendaryHeroPool.byId("rcw_line_officer"))
        val background = assertNotNull(HeroBackgrounds.byId(hero.backgroundId))

        assertEquals(LeaderType.FEROCIOUS_DEFENSE, hero.signatureTrait)
        assertTrue(hero.signatureTrait != background.grantedTrait)
    }

    @Test
    fun reservationPersistsAcrossTheSave() {
        val roster = HeroRoster().apply { reservedLegendary = "ussr_ace" }
        val restored = HeroSerializer.deserialize(HeroSerializer.serialize(roster))
        assertEquals("ussr_ace", restored.reservedLegendary)
        assertTrue(!restored.legendarySpawned)
    }
}
