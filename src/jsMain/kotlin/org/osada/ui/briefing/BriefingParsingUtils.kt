package org.osada.ui.briefing

/**
 * Small dynamic-value reading primitives shared by [BriefingParser] and [DialogueParser]. Split
 * out purely to keep those objects within the project's function-count limits.
 */
internal object BriefingParsingUtils {
    private val arrayIsArray: dynamic = js("Array.isArray")

    fun unwrapBriefing(rawData: dynamic): dynamic {
        if (!isPresent(rawData)) return null
        val briefing = rawData.briefing
        return when {
            isArray(rawData) -> rawData
            isPresent(briefing) -> briefing
            else -> rawData
        }
    }

    fun readString(value: dynamic): String? = value as? String

    /** First of [values] that reads as a non-null string -- e.g. alternate JSON field names. */
    fun readFirstString(vararg values: dynamic): String? {
        for (v in values) {
            val s = readString(v)
            if (s != null) return s
        }
        return null
    }

    fun readAssetPath(value: dynamic): String? {
        val path = readString(value)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val isUnsafe = path.contains("..") || path.contains('\\') || path.contains(':')
        return if (isUnsafe) {
            null
        } else {
            path.takeIf {
                it.startsWith("resources/") ||
                    it.startsWith("./resources/") ||
                    it.startsWith("/resources/")
            }
        }
    }

    fun readStringList(value: dynamic): List<String> {
        val single = readString(value)?.trim()?.takeIf { it.isNotBlank() }
        if (single != null) return listOf(single)
        return if (isPresent(value) && isArray(value)) collectStringItems(value) else emptyList()
    }

    private fun collectStringItems(value: dynamic): List<String> {
        val result = mutableListOf<String>()
        val length = (value.length as? Int) ?: 0
        for (index in 0 until length) {
            val item = readString(value[index])?.trim().orEmpty()
            if (item.isNotBlank()) result += item
        }
        return result
    }

    fun isPresent(value: dynamic): Boolean = value != null && value != undefined

    fun isArray(value: dynamic): Boolean = isPresent(value) && (arrayIsArray(value) as Boolean)

    fun isObject(value: dynamic): Boolean = isPresent(value) && !isArray(value)

    fun initialsFor(name: String): String {
        val letters =
            name
                .split(Regex("\\s+"))
                .mapNotNull { token -> token.firstOrNull { it.isLetterOrDigit() } }
                .take(2)
                .joinToString("")
                .uppercase()
        return letters.ifBlank { "HQ" }
    }
}
