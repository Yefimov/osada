package org.osada.rules.ruleset

import kotlinx.browser.localStorage

/**
 * The one versioned browser-local profile library
 * (`docs/design/ruleset-profiles.md` §6).
 *
 * A profile is a small reusable OVERLAY, not a copy of the full settings, so one profile stays
 * meaningful across campaigns built on different efiles: omitted keys keep following the content.
 *
 * 13 vs. the 11-function budget: read/write/parse/serialize are two halves of one codec, and the
 * remaining entries are the create/rename/delete library operations §7 requires. Splitting them
 * across files would separate a store from its own format for no readability gain.
 */
@Suppress("TooManyFunctions")
object RulesetProfileStore {
    private const val KEY = "osada-ruleset-profiles"

    /**
     * The three entries that always exist, in the picker's documented order (§1).
     *
     * Open General Fidelity joined them on 2026-08-19 (`docs/og-fidelity-plan.md` D.1/D.3). It sits
     * last of the three because it is the most opinionated: the first entry follows the content,
     * the second is OSADA's baseline, and only the third asserts a whole foreign ruleset.
     */
    fun builtIns(): List<RulesetProfile> =
        listOf(
            RulesetProfile(
                id = RulesetProfile.AUTHORS_VISION_ID,
                name = "",
                source = RulesetSource.AUTHORS_VISION,
            ),
            RulesetProfile(
                id = RulesetProfile.OSADA_DEFAULT_ID,
                name = "",
                source = RulesetSource.OSADA_DEFAULT,
            ),
        )

    /** Saved profiles, sorted by player-visible name (§1). A malformed library yields an empty list
     *  rather than stopping the player from launching anything. */
    fun custom(): List<RulesetProfile> = parse(read()).sortedBy { it.name.lowercase() }

    fun all(): List<RulesetProfile> = builtIns() + custom()

    fun byId(id: String?): RulesetProfile? = all().firstOrNull { it.id == id }

    /** True when [name] is free for [exceptId]. §7: a save needs a non-blank unique display name. */
    fun isNameAvailable(
        name: String,
        exceptId: String? = null,
    ): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            custom().none { it.id != exceptId && it.name.trim().equals(trimmed, ignoreCase = true) }
    }

    /** Saves [profile] under its id, replacing any profile with the same id. Ids are stable across
     *  a rename (§7), so existing saves and room state keep resolving. */
    fun save(profile: RulesetProfile): RulesetProfile {
        val stored = profile.copy(source = RulesetSource.CUSTOM)
        write(parse(read()).filter { it.id != stored.id } + stored)
        return stored
    }

    fun rename(
        id: String,
        name: String,
    ): RulesetProfile? =
        byId(id)
            ?.takeIf { it.source == RulesetSource.CUSTOM }
            ?.let { save(it.copy(name = name.trim())) }

    fun delete(id: String) {
        write(parse(read()).filter { it.id != id })
    }

    /** Whole-library replacement, for a profile-backup import (§6). Never merges two profiles just
     *  because their display names match: the imported library replaces the local one outright. */
    fun replaceAll(profiles: List<RulesetProfile>) {
        write(profiles)
    }

    /** `custom-<n>`: storage-local and never derived from the name, so renaming cannot orphan the
     *  saves that reference it. */
    fun nextId(): String {
        val used = parse(read()).mapNotNull { it.id.removePrefix("custom-").toIntOrNull() }
        return "custom-" + ((used.maxOrNull() ?: 0) + 1)
    }

    private fun read(): String? =
        try {
            localStorage.getItem(KEY)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            console.warn("[OSADA] ruleset profiles unreadable; continuing with none", e)
            null
        }

    private fun write(profiles: List<RulesetProfile>) {
        try {
            localStorage.setItem(KEY, serialize(profiles))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            console.warn("[OSADA] ruleset profiles could not be saved", e)
        }
    }

    /** Round-trips unknown keys untouched: this build must not quietly drop a rule a newer one
     *  wrote and then present the profile as though it had understood it (§3). */
    internal fun serialize(profiles: List<RulesetProfile>): String {
        val array = js("[]")
        profiles.forEach { profile ->
            val overrides = js("{}")
            profile.overrides.forEach { (rule, value) -> overrides[rule.key] = value }
            profile.unknownKeys.forEach { key -> overrides[key] = null }
            val entry = js("{}")
            entry.id = profile.id
            entry.name = profile.name
            entry.schemaVersion = profile.schemaVersion
            entry.overrides = overrides
            array.push(entry)
        }
        return JSON.stringify(array)
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun parse(text: String?): List<RulesetProfile> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            readArray(JSON.parse<dynamic>(text))
        } catch (e: Throwable) {
            console.warn("[OSADA] ruleset profiles malformed; ignoring them", e)
            emptyList()
        }
    }

    private fun readArray(array: dynamic): List<RulesetProfile> {
        val length = (array?.length as? Int) ?: return emptyList()
        val profiles = mutableListOf<RulesetProfile>()
        for (index in 0 until length) {
            val entry: dynamic = array[index]
            val id = (entry?.id as? String)?.trim()
            if (!id.isNullOrBlank()) profiles += readProfile(id, entry)
        }
        return profiles
    }

    private fun readProfile(
        id: String,
        entry: dynamic,
    ): RulesetProfile {
        val overrides = mutableMapOf<RuleKey, Int>()
        val unknown = mutableSetOf<String>()
        val raw: dynamic = entry.overrides
        if (raw != null && raw != undefined) {
            js("Object.keys")(raw).unsafeCast<Array<String>>().forEach { key ->
                val rule = RuleKey.byKey(key)
                val value = raw[key] as? Int
                if (rule != null && value != null) overrides[rule] = rule.clampForEditor(value) else unknown += key
            }
        }
        return RulesetProfile(
            id = id,
            name = (entry.name as? String)?.trim().orEmpty(),
            schemaVersion = (entry.schemaVersion as? Int) ?: RULESET_SCHEMA_VERSION,
            overrides = overrides,
            source = RulesetSource.CUSTOM,
            unknownKeys = unknown,
        )
    }

    internal fun clearForTest() {
        try {
            localStorage.removeItem(KEY)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            console.warn("[OSADA] ruleset profile store could not be cleared", e)
        }
    }
}
