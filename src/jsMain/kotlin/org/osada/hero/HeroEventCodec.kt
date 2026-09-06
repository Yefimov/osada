package org.osada.hero

import org.osada.campaign.BriefingDynamic
import kotlin.js.json

/**
 * Codecs for the record types Phase 3 starts actually writing: [HeroMedal], [HeroEvent] and
 * [FormationEvent]. Split out of [HeroValueCodec] for the same reason that file was split out of
 * [HeroSerializer] — the project's function-count-per-file budget — not because the contract
 * differs: every reader here degrades to dropping the malformed entry rather than throwing.
 */
internal object HeroEventCodec {
    fun serializeHeroMedal(medal: HeroMedal): dynamic =
        json(Pair("id", medal.medalId), Pair("scenario", medal.scenarioId))

    fun readHeroMedal(item: dynamic): HeroMedal? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        return HeroMedal(id, BriefingDynamic.str(item?.scenario).orEmpty())
    }

    fun serializeHeroEvent(event: HeroEvent): dynamic =
        json(
            Pair("id", event.eventId),
            Pair("scenario", event.scenarioId),
            Pair("turn", event.turn),
            Pair("date", event.date.orEmpty()),
            Pair("location", event.location.orEmpty()),
            Pair("formation", event.formationId?.value.orEmpty()),
            Pair("related", event.relatedHeroId?.value.orEmpty()),
        )

    fun readHeroEvent(item: dynamic): HeroEvent? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        return HeroEvent(
            id,
            BriefingDynamic.str(item?.scenario).orEmpty(),
            BriefingDynamic.int(item?.turn) ?: 0,
            BriefingDynamic.str(item?.date)?.takeIf { it.isNotBlank() },
            BriefingDynamic.str(item?.location)?.takeIf { it.isNotBlank() },
            // Absent in every save written before §5.3, which is exactly the "old events remain
            // valid without entity references" case §15 asks for: the event still renders, it just
            // contributes nothing to lineage.
            nonBlank(item?.formation)?.let(::FormationId),
            nonBlank(item?.related)?.let(::HeroId),
        )
    }

    /** [HeroInjury.permanent] is written as 1/0 to avoid a boolean-typed dynamic read (§11). */
    fun serializeHeroInjury(injury: HeroInjury): dynamic =
        json(
            Pair("id", injury.injuryId),
            Pair("scenario", injury.scenarioId),
            Pair("perm", if (injury.permanent) 1 else 0),
        )

    fun readHeroInjury(item: dynamic): HeroInjury? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        val permanent = (BriefingDynamic.int(item?.perm) ?: 0) == 1
        return HeroInjury(id, BriefingDynamic.str(item?.scenario).orEmpty(), permanent)
    }

    fun serializeFormationEvent(event: FormationEvent): dynamic =
        json(
            Pair("id", event.eventId),
            Pair("scenario", event.scenarioId),
            Pair("turn", event.turn),
            Pair("date", event.date.orEmpty()),
            Pair("location", event.location.orEmpty()),
            Pair("hero", event.heroId?.value.orEmpty()),
            Pair("related", event.relatedHeroId?.value.orEmpty()),
        )

    fun readFormationEvent(item: dynamic): FormationEvent? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        return FormationEvent(
            id,
            BriefingDynamic.str(item?.scenario).orEmpty(),
            BriefingDynamic.int(item?.turn) ?: 0,
            BriefingDynamic.str(item?.date)?.takeIf { it.isNotBlank() },
            BriefingDynamic.str(item?.location)?.takeIf { it.isNotBlank() },
            nonBlank(item?.hero)?.let(::HeroId),
            nonBlank(item?.related)?.let(::HeroId),
        )
    }

    private fun nonBlank(value: dynamic): String? = BriefingDynamic.str(value)?.takeIf { it.isNotBlank() }
}
