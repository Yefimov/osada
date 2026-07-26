package org.osada.i18n

internal enum class Language(
    val code: String,
    val locale: String,
    val autonym: String,
) {
    ENGLISH("en", "en", "English"),
    RUSSIAN("ru", "ru", "Русский"),
    TALYSH("tly", "tly-Latn", "Tolışə zıvon"),
    ;

    companion object {
        fun fromCode(code: String?): Language = fromCodeOrNull(code) ?: ENGLISH

        fun fromCodeOrNull(code: String?): Language? {
            val normalized =
                code
                    ?.trim()
                    ?.replace('_', '-')
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { language ->
                language.locale.lowercase() == normalized || language.code.lowercase() == normalized
            } ?: entries.firstOrNull { it.code.lowercase() == normalized.substringBefore('-') }
        }

        fun bestMatch(languageTags: List<String>): Language =
            languageTags.firstNotNullOfOrNull(::fromCodeOrNull) ?: ENGLISH
    }
}
