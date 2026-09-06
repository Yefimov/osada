package org.osada.hero

import org.osada.campaign.BriefingDynamic
import kotlin.js.json

/**
 * Codecs for the two record types the biography design added: [HeroAssociation] (§6) and
 * [HeroDistinction] (§12).
 *
 * Split from [HeroEventCodec] for the project's functions-per-object budget, which those four
 * readers and writers pushed it past — the same reason that file was split from [HeroValueCodec],
 * and [HeroValueCodec] from [HeroSerializer]. The tolerance contract is identical: a malformed
 * entry is DROPPED rather than defaulted, because a half-read relationship or a conferral with no
 * sequence would render as a sentence about nobody.
 */
internal object HeroRecordCodec {
    fun serializeAssociation(association: HeroAssociation): dynamic =
        json(
            Pair("type", association.type.name),
            Pair("other", association.otherHeroId.value),
            Pair("event", association.sourceEventId),
            Pair("scenario", association.scenarioId),
            Pair("date", association.date.orEmpty()),
            Pair("location", association.location.orEmpty()),
            Pair("formation", association.formationId?.value.orEmpty()),
        )

    /**
     * A link is dropped rather than defaulted when it names no other officer or an unknown type:
     * a half-read relationship would render as a sentence about nobody. §15's "a missing
     * relationship list means none, not corrupt data" applies at the list level, this at the entry.
     */
    @Suppress("ReturnCount") // a missing id and an unknown type are both "drop this entry"
    fun readAssociation(item: dynamic): HeroAssociation? {
        val other = nonBlank(item?.other) ?: return null
        val type =
            HeroAssociation.Type.entries.firstOrNull { it.name == BriefingDynamic.str(item?.type) }
                ?: return null
        return HeroAssociation(
            type = type,
            otherHeroId = HeroId(other),
            sourceEventId = BriefingDynamic.str(item?.event).orEmpty(),
            scenarioId = BriefingDynamic.str(item?.scenario).orEmpty(),
            date = nonBlank(item?.date),
            location = nonBlank(item?.location),
            formationId = nonBlank(item?.formation)?.let(::FormationId),
        )
    }

    fun serializeDistinction(distinction: HeroDistinction): dynamic =
        json(
            Pair("id", distinction.distinctionId),
            Pair("seq", distinction.sequence),
            Pair("scenario", distinction.scenarioId),
            Pair("turn", distinction.turn),
            Pair("date", distinction.date.orEmpty()),
            Pair("location", distinction.location.orEmpty()),
            Pair("deeds", distinction.deedEventIds.toTypedArray()),
            Pair("posthumous", if (distinction.posthumous) 1 else 0),
        )

    /**
     * A conferral without an id or a sequence is unusable — the sequence is what a repeat award is
     * counted by — so it is dropped rather than defaulted to 1, which would silently invent a
     * first award out of a damaged record.
     */
    @Suppress("ReturnCount") // a missing id and a missing sequence are both "drop this entry"
    fun readDistinction(item: dynamic): HeroDistinction? {
        val id = nonBlank(item?.id) ?: return null
        val sequence = BriefingDynamic.int(item?.seq)?.takeIf { it > 0 } ?: return null
        return HeroDistinction(
            distinctionId = id,
            sequence = sequence,
            scenarioId = BriefingDynamic.str(item?.scenario).orEmpty(),
            turn = BriefingDynamic.int(item?.turn) ?: 0,
            date = nonBlank(item?.date),
            location = nonBlank(item?.location),
            deedEventIds = BriefingDynamic.strList(item?.deeds),
            posthumous = (BriefingDynamic.int(item?.posthumous) ?: 0) == 1,
        )
    }

    private fun nonBlank(value: dynamic): String? = BriefingDynamic.str(value)?.takeIf { it.isNotBlank() }
}
