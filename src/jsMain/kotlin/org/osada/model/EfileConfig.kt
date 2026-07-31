package org.osada.model

import org.osada.model.EfileConfig.attachments
import org.osada.model.EfileConfig.flag
import org.osada.model.EfileConfig.intKey
import org.w3c.xhr.XMLHttpRequest
import kotlin.js.Json

/**
 * Per-efile `equip.cfg` settings and attachment slot definitions, imported from OG's own
 * `$Variable` config file (`tools/og-import/equip_cfg_to_json.py` -> `resources/efile-cfg/<tag>.json`).
 *
 * Absence is meaningful and is never treated as "off" here -- `docs/design/efile-config.md` trap 4:
 * `g2a_intercept_mode` unset means mode 0, which still intercepts; `attach_on` unset means 0, which
 * really is off. The DEFAULT for each key belongs at the call site (documented there), never baked
 * into this loader, so a caller can tell "efile said nothing" apart from "efile said 0".
 *
 * Five of the ten efiles OSADA ships have no `equip.cfg` at all -- including KAISER, which backs
 * more campaigns than any other efile -- so [intKey]/[flag]/[attachments] returning the "absent"
 * result is not an edge case, it is most of what actually runs.
 */
object EfileConfig {
    private const val PATH = "resources/efile-cfg/"
    private val httpSuccessRange = 200..299

    private var loadedForEfile: String? = null
    private var intKeys: Map<String, Int> = emptyMap()
    private var attachmentConfig: AttachmentConfig? = null

    data class AttachmentSlot(
        val name: String,
        val disabled: Boolean,
        val bonus: Int,
        val penalty: Int,
        val minCost: Int,
        val factCost: Int,
        val penaltyType: Int,
    )

    data class AttachmentConfig(
        val armyCost: Boolean,
        val minFuel: Int,
        val minMove: Int,
        val factorDefaultPct: Int,
        val minCostDefault: Int,
        val slots: Map<Int, AttachmentSlot>,
    )

    /** Integer `equip.cfg` key for the currently active efile ([Equipment.name]), or [default] when
     *  the efile has no `equip.cfg`, or the key is absent, or its value did not parse as a plain
     *  int (e.g. `class_evade=!30`, `build_turn=2,3,3,3,2` -- see `equip_cfg_to_json.py`'s `raw` vs
     *  `keys` split). */
    fun intKey(
        name: String,
        default: Int,
    ): Int {
        loadIfNeeded()
        return intKeys[name] ?: default
    }

    /** Boolean reading of an int `equip.cfg` key: present and non-zero -> true. */
    fun flag(
        name: String,
        default: Boolean,
    ): Boolean {
        loadIfNeeded()
        return intKeys[name]?.let { it != 0 } ?: default
    }

    /** Attachment system config for the currently active efile, or null when `attach_on` is 0/absent
     *  -- attachments are only live in the efiles that explicitly turn them on (LXF, ATOMIC,
     *  BASEKORP, GCE; not KAISER, which ships no `equip.cfg` at all). */
    fun attachments(): AttachmentConfig? {
        loadIfNeeded()
        return attachmentConfig
    }

    // Synchronous, like TerrainEx/EquipmentAvailability's fetches: a small per-efile file, read
    // lazily on first use after the efile changes rather than threaded through scenario loading.
    private fun loadIfNeeded() {
        val efile = Equipment.name
        if (efile == loadedForEfile) return
        loadedForEfile = efile
        val text = fetch(efile)
        intKeys = text?.let(::parseIntKeys) ?: emptyMap()
        attachmentConfig = text?.let(::parseAttachments)
    }

    private fun fetch(efile: String): String? {
        val request = XMLHttpRequest()
        request.open("GET", "$PATH${efile.removePrefix("eqp-")}.json", false)
        request.send(null)
        val status = request.status.toInt()
        return if (status in httpSuccessRange || status == 0) request.responseText else null
    }

    internal fun parseIntKeys(text: String): Map<String, Int> {
        val keys = JSON.parse<Json>(text).asDynamic().keys
        if (keys == null || keys == undefined) return emptyMap()
        val map = mutableMapOf<String, Int>()
        js("Object.keys")(keys).unsafeCast<Array<String>>().forEach { key ->
            (keys[key] as? Int)?.let { map[key] = it }
        }
        return map
    }

    /** `attach_on` false/absent -> null: the whole block is meaningless when attachments aren't
     *  live for this efile. */
    internal fun parseAttachments(text: String): AttachmentConfig? {
        val attachments = JSON.parse<Json>(text).asDynamic().attachments
        val isLive = attachments != null && attachments != undefined && attachments.on == true
        if (!isLive) return null

        return AttachmentConfig(
            armyCost = attachments.armyCost == true,
            minFuel = (attachments.minFuel as? Int) ?: 0,
            minMove = (attachments.minMove as? Int) ?: 0,
            factorDefaultPct = (attachments.factorDefaultPct as? Int) ?: DEFAULT_FACTOR_PCT,
            minCostDefault = (attachments.minCostDefault as? Int) ?: DEFAULT_MIN_COST_PCT,
            slots = parseSlots(attachments.slots),
        )
    }

    private fun parseSlots(slotsJson: dynamic): Map<Int, AttachmentSlot> {
        val slots = mutableMapOf<Int, AttachmentSlot>()
        if (slotsJson == null || slotsJson == undefined) return slots
        js("Object.keys")(slotsJson).unsafeCast<Array<String>>().forEach { id ->
            val slotId = id.toIntOrNull() ?: return@forEach
            val s = slotsJson[id]
            slots[slotId] =
                AttachmentSlot(
                    name = (s.name as? String) ?: "",
                    disabled = s.disabled == true,
                    bonus = (s.bonus as? Int) ?: 0,
                    penalty = (s.penalty as? Int) ?: 0,
                    minCost = (s.minCost as? Int) ?: 0,
                    factCost = (s.factCost as? Int) ?: 0,
                    penaltyType = (s.penaltyType as? Int) ?: 0,
                )
        }
        return slots
    }

    // Defaults to the CURRENT [Equipment.name] rather than a fixed sentinel, so the next accessor
    // call sees no efile change and does not clobber this with a real fetch.
    internal fun setForTest(
        intKeyMap: Map<String, Int> = emptyMap(),
        attachmentConfigValue: AttachmentConfig? = null,
        efile: String = Equipment.name,
    ) {
        intKeys = intKeyMap
        attachmentConfig = attachmentConfigValue
        loadedForEfile = efile
    }

    internal fun resetForTest() {
        intKeys = emptyMap()
        attachmentConfig = null
        loadedForEfile = null
    }

    // `EFILE_GCE/equip.cfg`'s own comment: mincost + (1SP cost x base strength x factor)/100,
    // defaults 25% factor / 30pp min-cost -- DEFERRED.md §1.4's design doc citation. Used only when
    // an efile turns attachments on but omits its own factor/min-cost (none observed to do this yet;
    // kept as the documented fallback rather than silently defaulting to 0).
    private const val DEFAULT_FACTOR_PCT = 25
    private const val DEFAULT_MIN_COST_PCT = 30
}
