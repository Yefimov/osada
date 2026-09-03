package org.osada.ui

import org.osada.model.GameUnit
import org.w3c.xhr.XMLHttpRequest

/**
 * Open General's **per-equipment sound**, backed by the licensed subset shipped in the manifest.
 *
 * ## What OG does, and what OSADA does instead
 *
 * OG picks a unit's sound per EQUIPMENT RECORD — three `u16` ids in `equip.xeqp` naming
 * `SFX/<id>.mp3`, indexed by the shipped `SFX/Open_SFX.txt`: *"10xx.mp3 files are the sound for
 * units moving, 20xx.mp3 files are sounds for units attacking, 30xx.mp3 files are for when a unit
 * gets destroyed."* OSADA's set is per CLASS, inherited from Panzer Marshal, so `EFILE_AG`'s Hastati
 * and a 1943 rifle squad make the same noise where OG gives the first `1402 "Troup Infantry -
 * Antique"` and `2504 "Infantry Attack + Spears - Antique"`.
 *
 * [EquipmentData.moveSoundId] and its two siblings carry the ids since 2026-09-01. This object is
 * what would play them.
 *
 * ## Licensed asset set and fallback
 *
 * The 572 ids the deployed equipment references would require 35.4 MB of mp3, and the OG install's own
 * `README/read_me_first.html` says equipment, campaigns, graphics and **sounds** may not be hosted,
 * edited, renamed or redistributed without the particular owner's written permission. That
 * permission was confirmed by the repository owner on 2026-09-01. The repository therefore ships
 * the 563 referenced files present in that install and a MANIFEST at [MANIFEST_URL] listing them.
 *
 * That gate is not only a licence formality. It is also what stops a 404 per distinct id per
 * session: nothing is ever requested unless the manifest says it is there. Nine referenced ids have no file
 * even in the full OG install (author typos), which is why the class fallback stays permanent rather
 * than becoming a migration step.
 *
 * ## Lazy, and negative-cached
 *
 * `SoundSprite` builds and loads its `Audio` eagerly, which is right for eighteen class sprites and
 * wrong for 572 ids: constructing them at equipment-load time would request the entire library
 * up front. Clips here are built on first use and cached, and an id that fails to play is remembered
 * as failed so it is never retried.
 */
object OgSoundLibrary {
    /** The list of ids that actually ship; generated from files copied into the resource tree. */
    const val MANIFEST_URL = "resources/sounds/og/manifest.json"

    private const val CLIP_PATH = "resources/sounds/og/"

    /** null until the manifest has been read; empty on an absent or invalid manifest. */
    private var available: Set<Int>? = null

    private val clips: MutableMap<Int, SoundSprite> = mutableMapOf()

    /**
     * Reads the manifest once, synchronously, and treats any failure as "no audio ships".
     *
     * Synchronous because it must answer the very first move sound and there is nothing to wait
     * for in the shipped configuration: the request 404s, the catch fires, and the set is empty for
     * the rest of the session. `ScenarioLoader` uses the same `XMLHttpRequest` shape for the same
     * reason.
     */
    private fun manifest(): Set<Int> {
        available?.let { return it }
        val ids =
            try {
                val request = XMLHttpRequest()
                request.open("GET", MANIFEST_URL, false)
                request.send()
                if (request.status.toInt() != 200) {
                    emptySet()
                } else {
                    val parsed = JSON.parse<dynamic>(request.responseText)
                    val length = parsed.length as? Int ?: 0
                    (0 until length).mapNotNull { parsed[it] as? Int }.toSet()
                }
            } catch (_: Throwable) {
                emptySet()
            }
        available = ids
        return ids
    }

    /** The sprite for [id], or null when no manifest lists it. Built once and cached. */
    private fun clipFor(id: Int): SoundSprite? {
        if (id <= 0 || id !in manifest()) return null
        return clips.getOrPut(id) { SoundSprite(listOf("$CLIP_PATH$id.mp3")) }
    }

    /**
     * Plays [unit]'s own movement sound, returning whether it did.
     *
     * False means the id is unassigned, absent from the licensed set, or unavailable; the caller
     * then plays the class sound.
     */
    fun playMove(unit: GameUnit): Boolean = play(unit.unitData().moveSoundId)

    /** [unit]'s own attack sound, or null for the class sprite to be used instead. */
    fun attackSprite(unit: GameUnit?): SoundSprite? = unit?.let { clipFor(it.unitData(true).attackSoundId) }

    /** [unit]'s own destruction sound, or null for the class sprite to be used instead. */
    fun deathSprite(unit: GameUnit?): SoundSprite? = unit?.let { clipFor(it.unitData(true).dieSoundId) }

    private fun play(id: Int): Boolean {
        val clip = clipFor(id) ?: return false
        clip.play()
        return true
    }

    /** Drops the cached manifest and clips, so a test may exercise both states. */
    internal fun resetForTest() {
        available = null
        clips.clear()
    }

    /** Installs a manifest without any network access, for tests. */
    internal fun setManifestForTest(ids: Set<Int>) {
        available = ids
        clips.clear()
    }
}
