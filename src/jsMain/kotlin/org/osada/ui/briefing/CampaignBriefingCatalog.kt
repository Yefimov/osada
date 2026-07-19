package org.osada.ui.briefing

import kotlin.js.json

/**
 * Authored campaign-only conversations and operational summaries.
 *
 * Standalone scenario launches never consult this catalogue. Campaign files may override an entry
 * by defining their own `briefing` object next to `scenario` and `intro`.
 */
internal object CampaignBriefingCatalog {
    private val entries: Map<String, () -> dynamic> =
        mapOf(
            "bn9s00.xml" to ::battleOfSesena,
            "rd01.xml" to ::bialystokInsurrection,
        )

    fun forScenario(file: String): dynamic = entries[file.lowercase()]?.invoke()

    /** Scenario XML files with authored dialogue/briefing content -- the single source of truth
     *  a campaign is automatically marked "story" against (see [StoryCampaignDetector]). Adding
     *  an entry to [entries] above lights the marker with zero further edits. */
    internal val storyScenarioFiles: Set<String> get() = entries.keys

    private fun battleOfSesena(): dynamic =
        json(
            "act" to "ACT I — THE SPANISH WAR",
            "location" to "Seseña, Spain",
            "player" to
                json(
                    "speaker" to "Commander",
                    "role" to "Republican Armoured Group",
                    "side" to "left",
                ),
            "dialogue" to battleOfSesenaDialogue(),
            "orders" to battleOfSesenaOrders(),
        )

    private fun battleOfSesenaDialogue(): Array<dynamic> = battleOfSesenaOpeningNodes() + battleOfSesenaResponseNodes()

    private fun battleOfSesenaOpeningNodes(): Array<dynamic> =
        arrayOf(
            json(
                "id" to "staff-opening",
                "speaker" to "Republican General Staff",
                "role" to "Operations Directorate",
                "side" to "left",
                "text" to
                    "Nationalist formations south of Madrid are still disorganised. We have a short " +
                    "opportunity to disrupt their advance before they establish a continuous front.",
                "next" to "armour-order",
            ),
            json(
                "id" to "armour-order",
                "speaker" to "Soviet Armoured Adviser",
                "role" to "Tank Group Headquarters",
                "side" to "right",
                "text" to
                    "Move west from Seseña. Esquivias and Illescas are the key road centres. How do you " +
                    "intend to handle the advance?",
                "choices" to
                    arrayOf(
                        json(
                            "id" to "methodical",
                            "text" to
                                "We secure Seseña first, then advance with infantry close behind the tanks.",
                            "next" to "methodical-reply",
                        ),
                        json(
                            "id" to "aggressive",
                            "text" to
                                "We strike immediately and take both road centres before the enemy can react.",
                            "next" to "aggressive-reply",
                        ),
                    ),
            ),
        )

    private fun battleOfSesenaResponseNodes(): Array<dynamic> =
        arrayOf(
            json(
                "id" to "methodical-reply",
                "speaker" to "Soviet Armoured Adviser",
                "role" to "Tank Group Headquarters",
                "side" to "right",
                "text" to
                    "A controlled advance will protect the spearhead, but do not surrender the initiative. " +
                    "The enemy gains strength with every hour.",
                "next" to "air-support",
            ),
            json(
                "id" to "aggressive-reply",
                "speaker" to "Soviet Armoured Adviser",
                "role" to "Tank Group Headquarters",
                "side" to "right",
                "text" to
                    "Then keep the tanks concentrated. A rapid blow can succeed, but an isolated vehicle " +
                    "column will be destroyed piecemeal.",
                "next" to "air-support",
            ),
            json(
                "id" to "air-support",
                "speaker" to "Air Liaison Officer",
                "role" to "Republican Air Command",
                "side" to "left",
                "text" to
                    "Aircraft are standing by. Mark the strongest resistance and we will suppress it ahead " +
                    "of the armoured group.",
            ),
        )

    private fun battleOfSesenaOrders(): dynamic =
        json(
            "situation" to
                "Nationalist forces are advancing south of Madrid but have not yet consolidated their " +
                "positions around Seseña.",
            "mission" to
                "Strike west with the armoured group, occupy Esquivias and Illescas, and preserve the " +
                "Republican position at Seseña.",
            "primaryObjectives" to
                arrayOf(
                    "Capture Esquivias",
                    "Capture Illescas",
                    "Retain control of Seseña",
                ),
            "secondaryObjectives" to
                arrayOf(
                    "Keep the armoured spearhead supplied",
                    "Use air support to reduce unnecessary tank losses",
                ),
            "enemyIntelligence" to
                "The enemy is initially dispersed, but delay will allow reserves to form a stronger " +
                "defensive line.",
            "availableSupport" to "Republican aircraft and local infantry formations support the operation.",
        )

    private fun bialystokInsurrection(): dynamic =
        json(
            "act" to "ACT I — A NEW EUROPEAN WAR",
            "location" to "Białystok",
            "player" to
                json(
                    "speaker" to "Commander",
                    "role" to "District Field Command",
                    "side" to "left",
                ),
            "dialogue" to bialystokDialogue(),
            "orders" to bialystokOrders(),
        )

    private fun bialystokDialogue(): Array<dynamic> =
        arrayOf(
            json(
                "id" to "district-report",
                "speaker" to "Western District Headquarters",
                "role" to "Operations Section",
                "side" to "left",
                "text" to
                    "Armed Polish formations have seized Białystok after overwhelming the local garrison. " +
                    "Their success may encourage a wider uprising if it is not contained immediately.",
                "next" to "intelligence",
            ),
            json(
                "id" to "intelligence",
                "speaker" to "Front Intelligence Officer",
                "role" to "Reconnaissance Directorate",
                "side" to "right",
                "text" to
                    "The insurgents include remnants of the former Polish Army. Expect organised resistance " +
                    "around the city and on the principal approaches.",
                "choices" to
                    arrayOf(
                        json(
                            "id" to "concentrate",
                            "text" to
                                "We concentrate on the decisive objectives and refuse battle with isolated " +
                                "detachments.",
                            "next" to "commander-approval",
                        ),
                        json(
                            "id" to "clear-all",
                            "text" to
                                "We clear every approach before entering the city. No hostile formation will " +
                                "remain behind us.",
                            "next" to "commander-warning",
                        ),
                    ),
            ),
            json(
                "id" to "commander-approval",
                "speaker" to "District Commander",
                "role" to "Western Military District",
                "side" to "left",
                "text" to
                    "Agreed. Break organised resistance and secure the decisive points before the revolt can " +
                    "spread.",
            ),
            json(
                "id" to "commander-warning",
                "speaker" to "District Commander",
                "role" to "Western Military District",
                "side" to "left",
                "text" to
                    "Thoroughness has value, but time does not favour us. Do not let minor detachments delay " +
                    "the recapture of Białystok.",
            ),
        )

    private fun bialystokOrders(): dynamic =
        json(
            "situation" to "Polish insurgent units have taken Białystok and displaced the local garrison.",
            "mission" to
                "Suppress the organised uprising and restore control of the principal objectives in the " +
                "Białystok area.",
            "primaryObjectives" to
                arrayOf(
                    "Recapture the designated victory objectives",
                    "Prevent the insurgent force from consolidating its position",
                ),
            "enemyIntelligence" to
                "Opposition includes experienced remnants of the former Polish Army rather than an " +
                "improvised civilian force.",
            "notes" to
                "Maintain concentration. A rapid attack is preferable to a prolonged battle of attrition.",
        )
}
