package org.osada.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-efile `equip.cfg` settings (DEFERRED.md §6.6 item 0, `docs/design/efile-config.md`).
 * [EfileConfig.intKey]/[EfileConfig.flag]/[EfileConfig.attachments] must prefer the imported
 * per-efile value and fall back to the documented default whenever the active efile has no
 * `equip.cfg`, or the key is absent, or (for [EfileConfig.attachments]) `attach_on` is 0/absent.
 */
class EfileConfigTest {
    @AfterTest
    fun cleanup() {
        EfileConfig.resetForTest()
    }

    @Test
    fun parsesIntKeysFromJson() {
        val json =
            """
            {"efile":"LXF","keys":{"flak_range":4,"g2a_intercept_mode":2,"zoc_evade":5},
             "raw":{"class_evade":"!30"},"attachments":{"slots":{}}}
            """.trimIndent()

        val parsed = EfileConfig.parseIntKeys(json)

        assertEquals(4, parsed["flak_range"])
        assertEquals(2, parsed["g2a_intercept_mode"])
        assertEquals(5, parsed["zoc_evade"])
        assertEquals(null, parsed["class_evade"], "non-integer raw-only values must not leak into keys")
    }

    @Test
    fun malformedOrEmptyJsonYieldsNoEntries() {
        assertEquals(emptyMap(), EfileConfig.parseIntKeys("""{"efile":"X"}"""))
        assertEquals(emptyMap(), EfileConfig.parseIntKeys("{}"))
    }

    @Test
    fun absentKeyReturnsTheDocumentedDefault() {
        // KAISER-shaped case: an efile with real keys, but not the one being asked about.
        EfileConfig.setForTest(mapOf("zoc_evade" to 5))

        assertEquals(1, EfileConfig.intKey("flak_range", 1), "flak_range unset means 1, not 0")
    }

    @Test
    fun absentFileFallsBackForEveryAccessor() {
        // KAISER itself: ships no equip.cfg at all. This is the case eight campaigns actually run.
        EfileConfig.setForTest()

        assertEquals(1, EfileConfig.intKey("flak_range", 1))
        assertEquals(false, EfileConfig.flag("attach_on", false))
        assertNull(EfileConfig.attachments())
    }

    @Test
    fun flagIsTrueOnlyWhenThePresentValueIsNonZero() {
        EfileConfig.setForTest(mapOf("kamikaze" to 1, "upgrade_ldr" to 0))

        assertTrue(EfileConfig.flag("kamikaze", false))
        assertEquals(false, EfileConfig.flag("upgrade_ldr", true), "present but zero is still false")
        assertEquals(true, EfileConfig.flag("never_set", true), "absent falls back to the given default")
    }

    @Test
    fun attachmentsIsNullWhenAttachOnIsAbsent() {
        // AG-shaped case: has an equip.cfg, but never sets attach_on.
        val json = """{"efile":"AG","keys":{"zoc_evade":5},"raw":{},"attachments":{"slots":{}}}"""

        assertNull(EfileConfig.parseAttachments(json))
    }

    @Test
    fun attachmentSlotsResolveByNumberNotName() {
        // ATOMIC's slot 5 is "Ammunition"; LXF's and GCE's is "Support" -- same mechanic, same slot id.
        val atomicJson =
            """
            {"efile":"ATOMIC","keys":{},"raw":{},"attachments":{"on":true,
             "slots":{"5":{"name":"Ammunition","disabled":false,"bonus":2,"penalty":0,"minCost":0,"factCost":25,"penaltyType":0}}}}
            """.trimIndent()
        val lxfJson =
            """
            {"efile":"LXF","keys":{},"raw":{},"attachments":{"on":true,
             "slots":{"5":{"name":"Support","disabled":false,"bonus":2,"penalty":-1,"minCost":30,"factCost":10,"penaltyType":1}}}}
            """.trimIndent()

        val atomicSlot5 = EfileConfig.parseAttachments(atomicJson)?.slots?.get(5)
        val lxfSlot5 = EfileConfig.parseAttachments(lxfJson)?.slots?.get(5)

        assertEquals("Ammunition", atomicSlot5?.name)
        assertEquals("Support", lxfSlot5?.name)
        // Different names, same slot id -- a caller keying off `slots[5]` gets the mechanic either way.
        assertEquals(5, atomicSlot5?.let { 5 })
        assertEquals(5, lxfSlot5?.let { 5 })
    }

    @Test
    fun attachmentConfigCarriesTheSystemLevelFields() {
        val json =
            """
            {"efile":"LXF","keys":{},"raw":{},"attachments":{"on":true,"armyCost":true,
             "minFuel":8,"minMove":1,"slots":{}}}
            """.trimIndent()

        val config = EfileConfig.parseAttachments(json)

        assertEquals(true, config?.armyCost)
        assertEquals(8, config?.minFuel)
        assertEquals(1, config?.minMove)
    }
}
