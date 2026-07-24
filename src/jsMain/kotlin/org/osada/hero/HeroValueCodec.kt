package org.osada.hero

import org.osada.campaign.BriefingDynamic
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
            Pair("priorService", facts.priorServiceId.orEmpty()),
            Pair("emergence", facts.emergenceEventId),
        )

    fun readBiography(value: dynamic): HeroBiographyFacts =
        HeroBiographyFacts(
            birthYear = BriefingDynamic.int(value?.birthYear)?.takeIf { it > 0 },
            birthplaceId = nonBlank(value?.birthplace),
            socialBackgroundId = nonBlank(value?.social),
            prewarProfessionId = nonBlank(value?.profession),
            militaryEducationId = nonBlank(value?.education),
            priorServiceId = nonBlank(value?.priorService),
            emergenceEventId = BriefingDynamic.str(value?.emergence).orEmpty(),
        )

    /** Name-based enum lookup that degrades to [fallback] instead of throwing on unknown input. */
    fun <T : Enum<T>> enumOr(
        name: String?,
        values: List<T>,
        fallback: T,
    ): T = values.firstOrNull { it.name == name } ?: fallback

    private fun nonBlank(value: dynamic): String? = BriefingDynamic.str(value)?.takeIf { it.isNotBlank() }
}
