package org.osada.model

import org.osada.UnitClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bare Ground Transport is never bought as a unit of its own (user, 2026-07-26). It cannot
 * attack and cannot capture; it exists to be attached to a unit at purchase time, which this gate
 * does not touch.
 *
 * **This replaced an `attr` bit 262144 gate, and the bit is not a reliable "purchasable" signal.**
 * It permitted any transport whose country never set the bit, and that fallback is not universal:
 * 29 of 289 `eqp-united` countries do set the bit on a transport (country 20/USSR flags 4 of its
 * 28, refusing the other 24). But the bit still doesn't track purchasability — only 1,060 of 46,978
 * `eqp-united` records carry it (2.3%), **including zero Tank and zero Anti-tank records** (class 2
 * = 0/3,024, class 4 = 0/3,186). A bit that no tank in the game sets is not "purchasable". Do not
 * reinstate an attr-based gate here until DEFERRED.md §1.5/§1.7 have re-identified it.
 */
class PurchasableGroundTransportTest {
    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
    }

    @Test
    fun classesOtherThanGroundTransportAreNeverGated() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.INFANTRY.value
                country = 1
            },
        )

        assertTrue(Equipment.isPurchasableGroundTransport(1), "gate is scoped to Ground Transport only")
    }

    @Test
    fun aBareGroundTransportIsNeverBuyableOnItsOwn() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                name = "Mules"
            },
        )

        assertFalse(Equipment.isPurchasableGroundTransport(1), "buying a bare Mules has no defensible reading")
    }

    /** The old gate's escape hatch: setting the bit used to make a transport buyable. It must not
     *  any more, or the countries whose data happens to set it get the dumb case back. */
    @Test
    fun theOldPurchasableBitNoLongerReopensTheCase() {
        Equipment.putEquipment(
            1,
            EquipmentData().apply {
                uclass = UnitClass.GROUND_TRANSPORT.value
                country = 1
                attr = 262144
            },
        )

        assertFalse(Equipment.isPurchasableGroundTransport(1), "the attr bit no longer permits a standalone buy")
    }

    @Test
    fun anUnknownEquipmentIdIsNotTreatedAsATransport() {
        assertTrue(Equipment.isPurchasableGroundTransport(9999), "a missing record must not be gated as a transport")
    }
}
