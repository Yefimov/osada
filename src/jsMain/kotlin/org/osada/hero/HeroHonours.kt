package org.osada.hero

import org.osada.i18n.I18n

/**
 * Battle honours a formation carries, as STORED TOKENS rendered at display time.
 *
 * `CoreFormation.battleHonors` is a list of plain strings written straight into the save, and the
 * memorial tradition a formation earns when its commander is killed used to be built as English
 * prose — `"Tradition of Nadya Sokolova"` — so a Russian player read an English sentence on their
 * own formation, and no re-translation was possible because the prose, not the fact, was what the
 * save held.
 *
 * A token keeps the FACT in the save and leaves the wording to the bundle. The commander's name is
 * the only variable part, so it travels as the token's payload.
 *
 * **A string that is not a token is passed through unchanged.** Saves written before this carry the
 * old English prose, and a scenario author may write any honour they like into `battleHonors`;
 * neither is ours to reword.
 */
internal object HeroHonours {
    private const val MEMORIAL_PREFIX = "memorial:"

    /** The stored form of "this formation keeps the tradition of [commanderName]". */
    fun memorialToken(commanderName: String): String = "$MEMORIAL_PREFIX$commanderName"

    /** One honour as the player should read it, in the current locale. */
    fun display(honour: String): String =
        if (honour.startsWith(MEMORIAL_PREFIX)) {
            I18n.t("hero.honour.memorial", mapOf("commander" to honour.removePrefix(MEMORIAL_PREFIX)))
        } else {
            honour
        }

    fun display(honours: List<String>): List<String> = honours.map(::display)
}
