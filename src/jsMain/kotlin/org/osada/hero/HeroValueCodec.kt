package org.osada.hero

import org.osada.campaign.BriefingDynamic
import org.osada.hero.HeroValueCodec.readBiography
import kotlin.js.json

/**
 * Codecs for the small value objects inside a serialized hero.
 *
 * Split out of [HeroSerializer] purely to keep both files inside the project's 11-functions-per-file
 * budget; the two are one logical unit and share the same tolerance contract — every reader
 * degrades to an empty/zero value rather than throwing, because save data is untrusted input.
 */
internal object HeroValueCodec {
    fun serializeAttributes(attributes: CommandAttributes): dynamic =
        json(
            Pair("off", attributes.offense),
            Pair("def", attributes.defense),
            Pair("man", attributes.maneuver),
            Pair("coord", attributes.coordination),
        )

    fun readAttributes(value: dynamic): CommandAttributes =
        CommandAttributes(
            offense = BriefingDynamic.int(value?.off) ?: 0,
            defense = BriefingDynamic.int(value?.def) ?: 0,
            maneuver = BriefingDynamic.int(value?.man) ?: 0,
            coordination = BriefingDynamic.int(value?.coord) ?: 0,
        )

    fun serializeEvidence(evidence: Map<String, Int>): dynamic =
        json().also { out -> evidence.forEach { (category, amount) -> out[category] = amount } }

    fun readEvidence(value: dynamic): Map<String, Int> {
        if (!BriefingDynamic.isObject(value)) return emptyMap()
        return buildMap {
            for (key in js("Object.keys")(value).unsafeCast<Array<String>>()) {
                BriefingDynamic.int(value[key])?.takeIf { it > 0 }?.let { put(key, it) }
            }
        }
    }

    /**
     * Nullable biography fields are written as empty string / 0 rather than omitted, so the shape
     * of the object is constant and a reader never has to distinguish "absent" from "unknown" —
     * [readBiography] maps both back to null.
     */
    fun serializeBiography(facts: HeroBiographyFacts): dynamic =
        json(
            Pair("birthYear", facts.birthYear ?: 0),
            Pair("birthplace", facts.birthplaceId.orEmpty()),
            Pair("social", facts.socialBackgroundId.orEmpty()),
            Pair("profession", facts.prewarProfessionId.orEmpty()),
            Pair("education", facts.militaryEducationId.orEmpty()),
            // Written for a reader that predates `priorServices`, and read back by one that
            // postdates it: the legacy single-value key carries the FIRST prior-service fact, so an
            // older build loading a newer save still shows the hero a service history rather than
            // none. `readBiography` prefers the list and falls back to this.
            Pair("priorService", facts.priorServiceIds.firstOrNull().orEmpty()),
            Pair("emergence", facts.emergenceEventId),
            Pair("pack", facts.biographyPackId.orEmpty()),
            Pair("civEdu", facts.civilianEducationId.orEmpty()),
            Pair("entry", facts.serviceEntryId.orEmpty()),
            Pair("entryYear", facts.serviceStartYear ?: 0),
            Pair("warEntry", facts.warEntryId.orEmpty()),
            Pair("political", facts.politicalStatusId.orEmpty()),
            Pair("politicalYear", facts.politicalMembershipYear ?: 0),
            Pair("priorServices", facts.priorServiceIds.toTypedArray()),
        )

    fun readBiography(value: dynamic): HeroBiographyFacts =
        HeroBiographyFacts(
            birthYear = BriefingDynamic.int(value?.birthYear)?.takeIf { it > 0 },
            birthplaceId = nonBlank(value?.birthplace),
            socialBackgroundId = nonBlank(value?.social),
            prewarProfessionId = nonBlank(value?.profession),
            militaryEducationId = nonBlank(value?.education),
            emergenceEventId = BriefingDynamic.str(value?.emergence).orEmpty(),
            biographyPackId = nonBlank(value?.pack),
            civilianEducationId = nonBlank(value?.civEdu),
            serviceEntryId = nonBlank(value?.entry),
            serviceStartYear = BriefingDynamic.int(value?.entryYear)?.takeIf { it > 0 },
            warEntryId = nonBlank(value?.warEntry),
            politicalStatusId = nonBlank(value?.political),
            politicalMembershipYear = BriefingDynamic.int(value?.politicalYear)?.takeIf { it > 0 },
            priorServiceIds = readPriorServices(value),
        )

    /**
     * The prior-service list, from either shape. A save written before §8.6 carries one id in the
     * `priorService` string; one written after carries the list. Preferring the list and falling
     * back to the string is what makes the upgrade lossless in both directions.
     */
    private fun readPriorServices(value: dynamic): List<String> {
        val list = BriefingDynamic.strList(value?.priorServices)
        if (list.isNotEmpty()) return list
        return listOfNotNull(nonBlank(value?.priorService))
    }

    /** Name-based enum lookup that degrades to [fallback] instead of throwing on unknown input. */
    fun <T : Enum<T>> enumOr(
        name: String?,
        values: List<T>,
        fallback: T,
    ): T = values.firstOrNull { it.name == name } ?: fallback

    private fun nonBlank(value: dynamic): String? = BriefingDynamic.str(value)?.takeIf { it.isNotBlank() }
}
