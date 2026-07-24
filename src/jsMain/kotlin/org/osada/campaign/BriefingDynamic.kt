package org.osada.campaign

import org.osada.ui.briefing.BriefingParsingUtils

/**
 * Dynamic-value readers for campaign condition/effect JSON.
 *
 * String and array handling delegates to [BriefingParsingUtils] so the briefing and campaign
 * parsers agree on what "present" means; the numeric and boolean readers live here because the
 * briefing parser never needed them.
 *
 * Every reader is total: bad input yields null, never an exception. JSON numbers arrive as JS
 * doubles and string-typed values are tolerated (`"3"` reads as 3) because authored campaign
 * data is hand-written and quoting mistakes should not break a run.
 */
internal object BriefingDynamic {
    fun isPresent(value: dynamic): Boolean = BriefingParsingUtils.isPresent(value)

    fun isArray(value: dynamic): Boolean = BriefingParsingUtils.isArray(value)

    fun isObject(value: dynamic): Boolean = BriefingParsingUtils.isObject(value)

    fun str(value: dynamic): String? = BriefingParsingUtils.readString(value)

    fun strList(value: dynamic): List<String> = BriefingParsingUtils.readStringList(value)

    fun int(value: dynamic): Int? =
        when {
            !isPresent(value) -> null
            else -> (value as? Int) ?: (value as? Double)?.toInt() ?: (value as? String)?.trim()?.toIntOrNull()
        }

    /**
     * Maps a JS array through [transform], dropping entries it rejects (returns null).
     *
     * Every campaign-JSON list parser goes through here so "a malformed entry drops itself and
     * leaves its siblings intact" is implemented once rather than re-derived per parser.
     */
    fun <T> mapArray(
        value: dynamic,
        transform: (dynamic) -> T?,
    ): List<T> {
        if (!isArray(value)) return emptyList()
        val length = (value.length as? Int) ?: 0
        val out = mutableListOf<T>()
        for (index in 0 until length) {
            transform(value[index])?.let { out += it }
        }
        return out
    }

    fun bool(value: dynamic): Boolean? =
        when {
            !isPresent(value) -> null
            else ->
                (value as? Boolean)
                    ?: when ((value as? String)?.trim()?.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
        }
}
