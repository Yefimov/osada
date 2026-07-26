package org.osada.model

import org.osada.UnitClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in DEFERRED.md §1.7: OG's `attr` bit 262144 marks Ground Transport equipment purchasable
 * as a combat unit rather than existing only to be assigned as a unit's organic transport. Most of
 * the class never sets the bit (~1% of 5,041 records in the shipped `eqp-united`), so the gate
 * must fall back to permitting everything for a country whose data never uses the flag at all --
 * otherwise a naive gate makes the whole class unbuyable for most nations.
 */
class PurchasableGroundTransportTest {
    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
    }

    private val purchasableFlag = 262144

    @Test
    fun classesOtherThanGroundTransportAreNeverGated() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                country = 1
                attr = 0
            },
        )

        assertTrue(Equipment.isPurchasableGroundTransport(1), "gate is scoped to Ground Transport only")
    }

    @Test
    fun countryThatNeverSetsTheBitPermitsEveryTransport() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = 0
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = 0
            },
        )

        assertTrue(Equipment.isPurchasableGroundTransport(1), "bare Horse/Mule case: bit unused, fall back to permit")
        assertTrue(Equipment.isPurchasableGroundTransport(2))
    }

    @Test
    fun countryThatUsesTheBitGatesOnItStrictly() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = purchasableFlag
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = 0
            },
        )

        assertTrue(Equipment.isPurchasableGroundTransport(1), "flagged record stays buyable")
        assertFalse(
            Equipment.isPurchasableGroundTransport(2),
            "unflagged record is gated once the country uses the bit",
        )
    }

    @Test
    fun gateIsPerCountryNotGlobal() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = purchasableFlag
            },
        )
        Equipment.putEquipment(
            2,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 2
                attr = 0
            },
        )

        assertTrue(
            Equipment.isPurchasableGroundTransport(2),
            "country 2 never uses the bit, so its own record is unaffected by country 1 using it",
        )
    }
}
