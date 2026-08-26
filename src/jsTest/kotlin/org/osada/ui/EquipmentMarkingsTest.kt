package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.EquipmentData
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RULESET_SCHEMA_VERSION
import org.osada.rules.ruleset.RuleKey
import org.osada.rules.ruleset.RulesetDefaults
import org.osada.rules.ruleset.RulesetResolver
import org.osada.rules.ruleset.RulesetSource
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquipmentMarkingsTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
        ActiveRuleset.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        ActiveRuleset.resetForTest()
    }

    /** Locks `equipment_toggles` for one test. The badge reads the live ruleset, so a test about
     *  what a badge says has to state which profile it is speaking for. */
    private fun withEquipmentToggles(value: Int) {
        ActiveRuleset.set(
            RulesetResolver.fromEffective(
                id = "badge-test",
                name = "Test",
                source = RulesetSource.CUSTOM,
                schemaVersion = RULESET_SCHEMA_VERSION,
                effective = RulesetDefaults.OSADA + mapOf(RuleKey.EQUIPMENT_TOGGLES to value),
            ),
        )
    }

    @Test
    fun reconAndTankMarksExplainTheirRealRules() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.RECON.value })
        assertEquals("RCN", parent.firstElementChild?.textContent)
        assertTrue(parent.firstElementChild?.getAttribute("title")?.contains("move again") == true)

        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.TANK.value })
        assertEquals("OVR", parent.firstElementChild?.textContent)
        assertTrue(parent.firstElementChild?.getAttribute("title")?.contains("restores 1 movement point") == true)
    }

    /**
     * The SUP badge follows OG's effective rule — the class default **toggled** by `attr` bit 12 —
     * because rule and badge share one predicate ([UnitCapabilities.hasSupportFire], §7.14).
     *
     * `Support Fire` is a toggle, not a grant (`OG_ABILITY_AUDIT.md` §2). So an unflagged artillery
     * piece keeps the badge and a flagged one loses it, while a flagged anti-tank gun gains it. The
     * AA badge is a separate predicate and is unchanged.
     */
    @Test
    fun theSupportFireMarkFollowsTheToggledClassDefault() {
        val parent = document.createElement("div") as HTMLElement

        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.ARTILLERY.value })
        assertEquals("SUP", parent.firstElementChild?.textContent, "artillery defaults to fire support")

        EquipmentMarkings.render(
            parent,
            EquipmentData().apply {
                uclass = UnitClass.ARTILLERY.value
                attr = SUPPORT_FIRE_ATTR
            },
        )
        assertEquals(null, parent.firstElementChild, "the flag REVERSES the default, so no badge")

        EquipmentMarkings.render(
            parent,
            EquipmentData().apply {
                uclass = UnitClass.ANTI_TANK.value
                attr = SUPPORT_FIRE_ATTR
            },
        )
        assertEquals("SUP", parent.firstElementChild?.textContent, "74% of OG anti-tank is toggled ON")
    }

    /**
     * `Recon Skill` (`attr` bit 10) and `Overrun toggle` (`attrEx` bit 3) reverse the Recon/Tank
     * class default — **but only under `equipment_toggles`**, and this test now says so.
     *
     * It used to assert the toggle unconditionally, which is what let a 2026-08-25 review find that
     * the badge and the rule disagreed: the badge read `classDefault xor bit` while movement and
     * combat still tested the class, so a record carrying either bit wore a mark the engine would
     * not honour. Both now read the same function, and that function reads the key — so the honest
     * assertion is per profile, not absolute. The OFF case is the one the 502 shipped scenarios run.
     */
    @Test
    fun rconAndOvrBadgesFollowTheirToggledClassDefaults() {
        val parent = document.createElement("div") as HTMLElement
        withEquipmentToggles(1)

        val recon =
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                attr = RECON_SKILL_ATTR
            }
        EquipmentMarkings.render(parent, recon)
        assertEquals(null, parent.firstElementChild, "Recon Skill REVERSES the Recon default, so no RCN badge")

        val reconOnInfantry =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attr = RECON_SKILL_ATTR
            }
        EquipmentMarkings.render(parent, reconOnInfantry)
        assertEquals("RCN", parent.firstElementChild?.textContent, "the toggle grants it to a non-Recon record")

        val tank =
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                attrEx = OVERRUN_TOGGLE_ATTR_EX
            }
        EquipmentMarkings.render(parent, tank)
        assertEquals(null, parent.firstElementChild, "Overrun toggle REVERSES the Tank default, so no OVR badge")

        val overrunOnInfantry =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attrEx = OVERRUN_TOGGLE_ATTR_EX
            }
        EquipmentMarkings.render(parent, overrunOnInfantry)
        assertEquals("OVR", parent.firstElementChild?.textContent, "the toggle grants it to a non-Tank record")
    }

    /**
     * With `equipment_toggles` off — every profile but Open General Fidelity, and therefore every
     * shipped scenario — the two badges state the CLASS answer, because that is what the engine
     * will do. The badge is not allowed to be more Open General than the rule behind it.
     */
    @Test
    fun rconAndOvrBadgesStateTheClassAnswerWhenTheKeyIsOff() {
        val parent = document.createElement("div") as HTMLElement
        withEquipmentToggles(0)

        val reconCarryingTheBit =
            EquipmentData().apply {
                uclass = UnitClass.RECON.value
                attr = RECON_SKILL_ATTR
            }
        EquipmentMarkings.render(parent, reconCarryingTheBit)
        assertEquals("RCN", parent.firstElementChild?.textContent, "the class decides, so the badge stays")

        val infantryCarryingTheBit =
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                attr = RECON_SKILL_ATTR
            }
        EquipmentMarkings.render(parent, infantryCarryingTheBit)
        assertEquals(null, parent.firstElementChild, "and the bit grants nothing the engine would honour")

        val tankCarryingTheBit =
            EquipmentData().apply {
                uclass = UnitClass.TANK.value
                attrEx = OVERRUN_TOGGLE_ATTR_EX
            }
        EquipmentMarkings.render(parent, tankCarryingTheBit)
        assertEquals("OVR", parent.firstElementChild?.textContent, "a tank still overruns, so it still says so")
    }

    /** OG's `AD Support` (`SpecialEx` 61.5, `attrEx` bit 13) is a GRANT, not a toggle: it
     *  supplements the class list rather than replacing it, so a non-AA class carrying it alone
     *  gets the AA badge too. Wired 2026-08-19. */
    @Test
    fun adSupportGrantsTheAntiAirBadgeOutsideTheClassList() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(
            parent,
            EquipmentData().apply {
                uclass = UnitClass.CRUISER.value
                attrEx = AD_SUPPORT_ATTR_EX
            },
        )
        assertEquals("AA", parent.lastElementChild?.textContent, "AD Support grants the badge outside the class list")
    }

    @Test
    fun airDefenceClassesAdvertiseAntiAirFire() {
        val parent = document.createElement("div") as HTMLElement
        listOf(UnitClass.FLAK, UnitClass.AIR_DEFENCE, UnitClass.FIGHTER).forEach { cls ->
            EquipmentMarkings.render(
                parent,
                EquipmentData().apply {
                    uclass = cls.value
                    airatk = 8
                },
            )
            assertEquals("AA", parent.lastElementChild?.textContent, "$cls returns fire against aircraft")
        }
    }

    /**
     * The class alone is not the capability. `eqp-lxf` has 13 Flak/Air-Defence records with
     * `airatk = 0` — all of them radar sets (`Mobile Radar`, `SCR-584`, `SCR-268`) — and the real
     * rule rejects them, because `CombatResolver.isSupportFireEligible` also runs
     * `AttackEligibility.canInitiateAttack`. A badge on those claimed a role combat would never
     * grant: DEFERRED.md §4.6's failure mode, inside the predicate §7.14 shared to prevent it.
     */
    @Test
    fun anAirDefenceUnitThatCannotAttackAircraftGetsNoAntiAirMark() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(
            parent,
            EquipmentData().apply {
                uclass = UnitClass.AIR_DEFENCE.value
                airatk = 0
                name = "SCR-584"
            },
        )

        assertEquals(null, parent.firstElementChild, "a radar set advertises no defensive fire")
    }

    /** DEFERRED.md §1.1/§7.19: the engine now intercepts moving aircraft, so the badge must state
     *  the rule it actually runs rather than the old disclaimer that it never would. */
    @Test
    fun theAntiAirMarkNowPromisesInterception() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(
            parent,
            EquipmentData().apply {
                uclass = UnitClass.AIR_DEFENCE.value
                airatk = 8
            },
        )

        val title = parent.lastElementChild?.getAttribute("title").orEmpty()
        assertTrue(title.contains("intercepts"), "the AA tooltip must state the interception rule")
        assertTrue(!title.contains("does NOT yet intercept"), "the old disclaimer must be gone")
    }

    /** A class with no defensive-fire role must not pick up either badge. */
    @Test
    fun infantryGetsNoDefensiveFireMark() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(parent, EquipmentData().apply { uclass = UnitClass.INFANTRY.value })

        assertEquals(null, parent.firstElementChild)
    }

    /**
     * A logistics-sounding name is not a capability. The old LOG mark inferred one from the name
     * and then spent its tooltip explaining the port's own import status to the player; both the
     * inference and the explanation are gone. What is actually known about OG Depots lives in
     * DEFERRED.md §2.10, not in the UI.
     */
    @Test
    fun aLogisticsSoundingNameEarnsNoMark() {
        val parent = document.createElement("div") as HTMLElement
        EquipmentMarkings.render(parent, EquipmentData().apply { name = "Supply Depot" })

        assertEquals(null, parent.firstElementChild)
    }

    private companion object {
        /** `Equipment.attr` bit 12 — OG's `Support Fire`. */
        const val SUPPORT_FIRE_ATTR = 4096

        /** `Equipment.attr` bit 10 — OG's `Recon Skill`. */
        const val RECON_SKILL_ATTR = 1024

        /** `Equipment.attrEx` bit 3 — OG's `Overrun toggle`. */
        const val OVERRUN_TOGGLE_ATTR_EX = 8

        /** `Equipment.attrEx` bit 13 — OG's `AD Support`. */
        const val AD_SUPPORT_ATTR_EX = 8192
    }
}
