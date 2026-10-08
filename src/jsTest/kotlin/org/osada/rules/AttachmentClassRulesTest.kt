package org.osada.rules

import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.resetEquipment
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [AttachmentClassRules]: what a formation may buy, by class and by what it already is. */
class AttachmentClassRulesTest {
    private val eqid = 511

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
    }

    /** A record that has something for every slot to extend, so only the rule under test decides. */
    private fun allows(
        slot: Int,
        setup: EquipmentData.() -> Unit,
    ): Boolean {
        Equipment.putEquipment(
            eqid,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                ammo = 6
                fuel = 20
                airatk = 2
                setup()
            },
        )
        return AttachmentClassRules.allows(GameUnit(eqid), slot)
    }

    private fun allows(
        uclass: UnitClass,
        slot: Int,
    ): Boolean = allows(slot) { this.uclass = uclass.value }

    /** The class table is the intersection of the four efiles' `equip.xeqa` masks (2026-09-26). */
    @Test
    fun classTableIsTheIntersectionOfTheShippedMasks() {
        assertTrue(allows(UnitClass.FIGHTER, Attachments.SLOT_AIR_DEFENSE))
        assertFalse(allows(UnitClass.FIGHTER, Attachments.SLOT_FUEL_PODS), "ATOMIC: aircraft rarely take Fuel Pods")
        assertFalse(allows(UnitClass.DESTROYER, Attachments.SLOT_BUNKER_BUSTER), "ATOMIC: warships rarely do")
        assertFalse(allows(UnitClass.SUBMARINE, Attachments.SLOT_FAST_ENTRENCH))
        assertFalse(allows(UnitClass.ARTILLERY, Attachments.SLOT_AIR_DEFENSE), "ATOMIC: artillery rarely does")
        assertFalse(allows(UnitClass.FORTIFICATION, Attachments.SLOT_FAST_SPEED))
        assertTrue(allows(UnitClass.TANK, Attachments.SLOT_BUNKER_BUSTER))
        assertTrue(allows(UnitClass.INFANTRY, Attachments.SLOT_FAST_ENTRENCH))
    }

    /** An attachment that would change nothing for the formation is not sold: Bunker Buster to an
     *  engineer (it IS the Ignore-entrench ability), Fuel Pods to one with no fuel, Support to one
     *  with no ammunition. */
    @Test
    fun anAttachmentThatAddsNothingIsNotOffered() {
        assertFalse(allows(Attachments.SLOT_BUNKER_BUSTER) { attr = 4 }, "an engineer already ignores entrenchment")
        assertTrue(allows(Attachments.SLOT_BUNKER_BUSTER) { attr = 0 })
        assertTrue(allows(Attachments.SLOT_AIR_DEFENSE) { airatk = 0 }, "it adds air defence and an air attack")
        assertFalse(allows(Attachments.SLOT_FUEL_PODS) { fuel = 0 }, "a foot unit carries no fuel")
        assertFalse(allows(Attachments.SLOT_SUPPORT_AMMO) { ammo = 0 })
        assertTrue(allows(Attachments.SLOT_FAST_ENTRENCH) {}, "infantry digs in")
    }
}
