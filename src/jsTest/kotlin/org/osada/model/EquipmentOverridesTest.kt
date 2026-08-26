package org.osada.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The efile gate on [EquipmentOverrides].
 *
 * This exists because the whole point of the override layer is a NEGATIVE: the owner's ruling was
 * *"no changes across all campaigns, only those two. If it touches others, revert the edit."* A
 * shared `eqp-united` record is one object in every campaign that can field it, so the only thing
 * standing between "36 Gornostrelky is mountain-trained in the two Panzer Marshal campaigns" and
 * "…and also in Soviet Black Sea Fleet" is that `apply` keys on `Equipment.name`. A test is the
 * right place for that guarantee: observing it once in a browser proves nothing about the next
 * refactor.
 *
 * Uses the real values shipped in `eqp-united/overrides.json` for E 35810 (`36 Gornostrelky`,
 * merged from adlerkorps + basekorp) so the fixture cannot drift into testing a shape the file
 * does not actually have.
 */
class EquipmentOverridesTest {
    private val fixture =
        """
        {
          "_note": "documentation keys must be ignored, not parsed as an efile",
          "eqp-adlerkorps": {
            "35810": { "attr": 131971, "_name": "36 Gornostrelky" },
            "35889": { "attr": 131459, "_name": "46 Parashutisti" }
          }
        }
        """.trimIndent()

    private val originalName = Equipment.name

    @AfterTest
    fun restore() {
        Equipment.name = originalName
        EquipmentOverrides.resetForTest()
    }

    private fun gornostrelky() = EquipmentData().apply { attr = 131459 } // MTN clear, as on disk

    @Test
    fun appliesTheOverrideWhileItsOwnEfileIsLoaded() {
        EquipmentOverrides.setForTest(fixture)
        Equipment.name = "eqp-adlerkorps"
        val data = gornostrelky()
        EquipmentOverrides.apply(35810, data)
        assertEquals(131971, data.attr, "the adlerkorps campaigns must see Mountain granted")
    }

    @Test
    fun leavesTheRecordAloneUnderEveryOtherEfile() {
        EquipmentOverrides.setForTest(fixture)
        listOf("eqp-basekorp", "eqp-lxf", "eqp-united", "").forEach { efile ->
            Equipment.name = efile
            val data = gornostrelky()
            EquipmentOverrides.apply(35810, data)
            assertEquals(131459, data.attr, "'$efile' must see the record exactly as it is on disk")
        }
    }

    @Test
    fun leavesRecordsTheFileDoesNotMention() {
        EquipmentOverrides.setForTest(fixture)
        Equipment.name = "eqp-adlerkorps"
        val data = EquipmentData().apply { attr = 4198403 }
        EquipmentOverrides.apply(35849, data) // a PM-only record, patched in the JSON itself
        assertEquals(4198403, data.attr)
    }

    @Test
    fun touchesOnlyTheFieldsTheEntryNames() {
        EquipmentOverrides.setForTest(fixture)
        Equipment.name = "eqp-adlerkorps"
        val data =
            EquipmentData().apply {
                attr = 131459
                attr2 = 7
                attrEx = 192
                embark = 2
                cost = 276
            }
        EquipmentOverrides.apply(35810, data)
        assertEquals(131971, data.attr)
        assertEquals(7, data.attr2, "attr2 was not in the entry")
        assertEquals(192, data.attrEx, "attrEx was not in the entry")
        assertEquals(2, data.embark, "embark was not in the entry")
        assertEquals(276, data.cost, "cost is not an overridable field at all")
    }

    @Test
    fun documentationKeysAreNotEfiles() {
        EquipmentOverrides.setForTest(fixture)
        Equipment.name = "_note"
        val data = gornostrelky()
        EquipmentOverrides.apply(35810, data)
        assertEquals(131459, data.attr)
    }

    @Test
    fun anAbsentFileIsNotAnError() {
        EquipmentOverrides.setForTest(null)
        Equipment.name = "eqp-adlerkorps"
        val data = gornostrelky()
        EquipmentOverrides.apply(35810, data)
        assertEquals(131459, data.attr)
    }
}
