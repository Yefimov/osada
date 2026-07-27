package org.osada.ui

import kotlinx.browser.document
import org.osada.UnitClass
import org.osada.i18n.installEnglishUiBundleForTests
import org.osada.model.EquipmentData
import org.w3c.dom.HTMLElement
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquipmentMarkingsTest {
    @BeforeTest
    fun setup() {
        installEnglishUiBundleForTests()
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
    }
}
