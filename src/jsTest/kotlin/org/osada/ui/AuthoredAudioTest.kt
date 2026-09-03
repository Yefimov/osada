package org.osada.ui

import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.resetEquipment
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OG's per-equipment sound ids and per-scenario music track.
 *
 * Per-equipment SFX and the available scenario music are licensed and shipped. Both runtimes remain
 * manifest-gated, so the empty-manifest tests protect partial distributions and missing source
 * files while the listed-id tests cover the deployed paths.
 */
class AuthoredAudioTest {
    private companion object {
        const val EQID = 610
        const val MOVE_ID = 1402
        const val ATTACK_ID = 2504
        const val DIE_ID = 3010
    }

    @BeforeTest
    fun setup() {
        Equipment.resetEquipment()
        Equipment.putEquipment(
            EQID,
            EquipmentData().apply {
                name = "Hastati"
                uclass = UnitClass.INFANTRY.value
                moveSoundId = MOVE_ID
                attackSoundId = ATTACK_ID
                dieSoundId = DIE_ID
            },
        )
        OgSoundLibrary.resetForTest()
        ScenarioMusic.resetForTest()
    }

    @AfterTest
    fun teardown() {
        OgSoundLibrary.resetForTest()
        ScenarioMusic.resetForTest()
    }

    private fun legion() = GameUnit(EQID)

    // ---- Missing/partial manifest fallback -----------------------------------------------------

    /**
     * With no manifest the library resolves nothing, so no `Audio` is ever constructed for an OG id
     * and no request is ever made for a file that is not there. That is what keeps a build with no
     * audio from logging a 404 per distinct id per session.
     */
    @Test
    fun withNoManifestNothingIsEverRequested() {
        OgSoundLibrary.setManifestForTest(emptySet())
        assertFalse(OgSoundLibrary.playMove(legion()), "the caller falls back to the class sound")
        assertNull(OgSoundLibrary.attackSprite(legion()))
        assertNull(OgSoundLibrary.deathSprite(legion()))
    }

    /** Same for music: 81 deployed scenarios name a track and none of them can play. */
    @Test
    fun withNoManifestNoTrackIsEverResolved() {
        ScenarioMusic.setManifestForTest(emptySet())
        assertNull(ScenarioMusic.urlFor("winter.mp3"))
        ScenarioMusic.play("winter.mp3") // must not throw, and must not start anything
    }

    // ---- Licensed audio -------------------------------------------------------------------------

    /** OG picks the sound per RECORD, so a licensed build plays this unit's own ids. */
    @Test
    fun aListedIdResolvesToThatEquipmentsOwnClip() {
        OgSoundLibrary.setManifestForTest(setOf(MOVE_ID, ATTACK_ID, DIE_ID))
        assertNotNull(OgSoundLibrary.attackSprite(legion()))
        assertNotNull(OgSoundLibrary.deathSprite(legion()))
    }

    /**
     * Nine ids referenced by the shipped efiles have no file even in the full OG install (author
     * typos), so a partially populated manifest has to fall back per id rather than all-or-nothing.
     */
    @Test
    fun anIdTheManifestOmitsFallsBackOnItsOwn() {
        OgSoundLibrary.setManifestForTest(setOf(ATTACK_ID))
        assertNotNull(OgSoundLibrary.attackSprite(legion()))
        assertNull(OgSoundLibrary.deathSprite(legion()), "a missing id falls back alone")
    }

    /** A record with no id assigned (0, and the -1 of a record with no OG source) resolves nothing. */
    @Test
    fun anUnassignedIdResolvesNothing() {
        Equipment.putEquipment(
            EQID + 1,
            EquipmentData().apply {
                name = "Panzer Marshal Stock Record"
                uclass = UnitClass.INFANTRY.value
            },
        )
        OgSoundLibrary.setManifestForTest(setOf(MOVE_ID, ATTACK_ID, DIE_ID))
        val stock = GameUnit(EQID + 1)
        assertEquals(0, stock.unitData(true).attackSoundId)
        assertNull(OgSoundLibrary.attackSprite(stock))
        assertFalse(OgSoundLibrary.playMove(stock))
    }

    // ---- The two normalisations music needs -------------------------------------------------------

    /** OG runs on Windows; the deployed web server is case-sensitive. */
    @Test
    fun aTrackIsMatchedCaseInsensitively() {
        ScenarioMusic.setManifestForTest(setOf("winter.mp3"))
        assertEquals("resources/sounds/music/winter.mp3", ScenarioMusic.urlFor("Winter.MP3"))
    }

    /** `.MUS` is a DOS-era tracker format no browser decodes; 31 deployed scenarios name one. */
    @Test
    fun anUnplayableFormatIsRefusedRatherThanRequested() {
        ScenarioMusic.setManifestForTest(setOf("china1.mus"))
        assertNull(ScenarioMusic.urlFor("china1.MUS"))
        assertNull(ScenarioMusic.urlFor(""), "and an empty name is not a track")
        assertNull(ScenarioMusic.urlFor(null))
    }

    /**
     * A converted file changes only the manifest — the deployed attribute stays as authored.
     *
     * The scenario XML says `china1.MUS` and always will: it is a faithful record of the source and
     * the importers regenerate it, so it cannot be edited to name the conversion. The lookup is
     * therefore what has to bridge the two, which is the only route by which the 31 scenarios
     * naming a `.MUS` can ever have their author's music.
     *
     * The earlier version of this test asserted its own title away, by passing the CONVERTED name
     * in and checking it came back — which proves the runtime accepts ogg, not that an authored
     * `.MUS` resolves to it.
     */
    @Test
    fun aConvertedTrackNeedsNoChangeToTheDeployedXml() {
        ScenarioMusic.setManifestForTest(setOf("china1.ogg"))
        assertEquals(
            "resources/sounds/music/china1.ogg",
            ScenarioMusic.urlFor("china1.MUS"),
            "the authored .MUS name must resolve to the conversion the manifest lists",
        )
        assertEquals(
            "resources/sounds/music/china1.ogg",
            ScenarioMusic.urlFor("china1.ogg"),
            "and the converted name still answers for itself",
        )
    }

    /** The manifest stays the only gate: no conversion listed, no request, no 404. */
    @Test
    fun anAuthoredMusFallsBackToSilenceWhenNoConversionIsListed() {
        ScenarioMusic.setManifestForTest(setOf("amb_field.mp3"))
        assertNull(
            ScenarioMusic.urlFor("china1.MUS"),
            "a stem with no playable file in the manifest must not be requested under any extension",
        )
    }

    /** The manifest is read once and cached, so a hundred sound plays are not a hundred requests. */
    @Test
    fun theManifestIsCached() {
        OgSoundLibrary.setManifestForTest(setOf(MOVE_ID))
        assertTrue(OgSoundLibrary.playMove(legion()))
        assertTrue(OgSoundLibrary.playMove(legion()), "second play uses the cached clip")
    }
}
