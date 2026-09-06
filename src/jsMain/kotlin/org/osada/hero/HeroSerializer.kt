package org.osada.hero

import org.osada.campaign.BriefingDynamic
import org.osada.hero.HeroSerializer.deserialize
import kotlin.js.json

/**
 * Serializes a [HeroRoster] into the save's `campaign.heroes` block and back.
 *
 * Manual key-by-key emission (never `JSON.stringify` of a Kotlin object) because Kotlin/JS IR
 * mangles property names — see AGENTS.md, "Porting gotchas". The keys written here are the
 * save-file contract.
 *
 * Compatibility follows [org.osada.campaign.CampaignNarrativeSerializer] exactly:
 * - **backward, by absence** — a save written before this system has no `heroes` key,
 *   [deserialize] gets null and returns an empty roster;
 * - **forward, by tolerance** — unknown keys are ignored and a corrupt field degrades to its empty
 *   value rather than failing the load. A damaged save costs the player their hero roster, not
 *   their campaign.
 *
 * Enums are written by NAME and read through a defaulting lookup, so reordering the enums or
 * loading a save from a newer build cannot throw.
 */
internal object HeroSerializer {
    private const val HEROES_VERSION = 1

    fun serialize(roster: HeroRoster): dynamic =
        json(
            Pair("version", HEROES_VERSION),
            Pair("drought", roster.drought),
            Pair("reservedLegendary", roster.reservedLegendary ?: ""),
            Pair("legendarySpawned", if (roster.legendarySpawned) 1 else 0),
            Pair("formations", roster.allFormations().map(::serializeFormation).toTypedArray()),
            Pair(
                "heroes",
                roster
                    .allDefinitions()
                    .map { serializeHero(it, roster.state(it.id)) }
                    .toTypedArray(),
            ),
        )

    @Suppress("TooGenericExceptionCaught")
    fun deserialize(value: dynamic): HeroRoster {
        if (!BriefingDynamic.isObject(value)) return HeroRoster()
        return try {
            HeroRoster().apply {
                drought = BriefingDynamic.int(value.drought) ?: 0
                reservedLegendary = BriefingDynamic.str(value.reservedLegendary)?.takeIf { it.isNotBlank() }
                legendarySpawned = (BriefingDynamic.int(value.legendarySpawned) ?: 0) == 1
                BriefingDynamic.mapArray(value.formations) { readFormation(it) }.forEach(::putFormation)
                BriefingDynamic.mapArray(value.heroes) { readHero(it) }.forEach { putHero(it.first, it.second) }
            }
        } catch (e: Throwable) {
            console.warn("[OSADA] campaign hero roster corrupt, starting from an empty roster", e)
            HeroRoster()
        }
    }

    // ------------------------------------------------------------- formations

    private fun serializeFormation(formation: CoreFormation): dynamic =
        json(
            Pair("id", formation.id.value),
            Pair("owner", formation.ownerId),
            Pair("country", formation.country),
            Pair("name", formation.displayName),
            Pair("eqid", formation.currentEquipmentId),
            Pair("uclass", formation.unitClass),
            Pair("hero", formation.assignedHeroId?.value ?: ""),
            Pair("recognition", formation.recognition),
            Pair("checks", formation.emergenceChecks),
            Pair("attachments", formation.attachmentIds.toTypedArray()),
            Pair("honors", formation.battleHonors.toTypedArray()),
            Pair("history", formation.history.map(HeroEventCodec::serializeFormationEvent).toTypedArray()),
        )

    /** A formation without an id is unusable as a key, so it is dropped rather than defaulted. */
    private fun readFormation(item: dynamic): CoreFormation? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        return CoreFormation(
            id = FormationId(id),
            ownerId = BriefingDynamic.int(item?.owner) ?: -1,
            country = BriefingDynamic.int(item?.country) ?: -1,
            displayName = BriefingDynamic.str(item?.name).orEmpty(),
            currentEquipmentId = BriefingDynamic.int(item?.eqid) ?: 0,
            unitClass = BriefingDynamic.int(item?.uclass) ?: 0,
            assignedHeroId = BriefingDynamic.str(item?.hero)?.takeIf { it.isNotBlank() }?.let { HeroId(it) },
            recognition = BriefingDynamic.int(item?.recognition) ?: 0,
            emergenceChecks = BriefingDynamic.int(item?.checks) ?: 0,
            attachmentIds = BriefingDynamic.strList(item?.attachments),
            battleHonors = BriefingDynamic.strList(item?.honors),
            history = BriefingDynamic.mapArray(item?.history, HeroEventCodec::readFormationEvent),
        )
    }

    // ------------------------------------------------------------------ heroes

    /**
     * Identity and career are emitted into one object; they are only split in memory.
     *
     * A missing [state] is defaulted ONCE into an empty career rather than field by field. The
     * per-field form needed an elvis operator per key and grew one with every field the career
     * gained, until the method's branch count was mostly punctuation.
     */
    private fun serializeHero(
        definition: HeroDefinition,
        state: HeroState?,
    ): dynamic {
        val career = state ?: HeroState(heroId = definition.id, rankId = "")
        return json(
            Pair("id", definition.id.value),
            Pair("origin", definition.origin.name),
            Pair("name", definition.displayName),
            Pair("background", definition.backgroundId),
            Pair("signature", definition.signatureTraitId ?: ""),
            Pair("portraitSeed", definition.portrait.seed),
            Pair("portraitLayers", definition.portrait.layerIds.toTypedArray()),
            Pair("portraitArt", definition.portrait.artId.orEmpty()),
            Pair("portraitFemale", authoredGender(definition.portrait.female)),
            Pair("portraitPool", definition.portrait.poolId.orEmpty()),
            Pair("bio", HeroValueCodec.serializeBiography(definition.biographyFacts)),
            Pair("rank", career.rankId),
            Pair("status", career.status.name),
            Pair("potential", career.potential.name),
            Pair("renown", career.renown.name),
            Pair("xp", career.experience),
            Pair("formation", career.assignedFormationId?.value.orEmpty()),
            Pair("traits", career.learnedTraitIds.toTypedArray()),
            Pair("attributes", HeroValueCodec.serializeAttributes(career.attributes)),
            Pair("evidence", HeroValueCodec.serializeEvidence(career.specializationEvidence)),
            Pair("promotions", career.promotionsAwarded),
            Pair("settling", serializeSettling(career)),
            Pair("nickname", career.nicknameId.orEmpty()),
            Pair("medals", career.medals.map(HeroEventCodec::serializeHeroMedal).toTypedArray()),
            Pair(
                "injuries",
                career.injuries.map(HeroEventCodec::serializeHeroInjury).toTypedArray(),
            ),
            Pair(
                "events",
                career.serviceEvents.map(HeroEventCodec::serializeHeroEvent).toTypedArray(),
            ),
            Pair(
                "associations",
                career.associations.map(HeroRecordCodec::serializeAssociation).toTypedArray(),
            ),
            Pair(
                "distinctions",
                career.distinctions.map(HeroRecordCodec::serializeDistinction).toTypedArray(),
            ),
        )
    }

    /**
     * Settling-in after a commander transfer (§1.10), as its own object.
     *
     * Nested rather than two more flat keys because the scenario id and the turn are one fact and
     * are meaningless apart: a turn number without the battle it belongs to would read as a live
     * penalty in the next scenario. A save written before transfers existed has no `settling` key
     * at all, which [readSettling] reads as "settled" — which every commander in such a save is.
     */
    private fun serializeSettling(state: HeroState): dynamic =
        json(
            Pair("scenario", state.settlingScenarioId.orEmpty()),
            Pair("untilTurn", state.settlingUntilTurn),
        )

    /** [serializeSettling]'s inverse: (scenario id or null, first turn the traits count again). */
    private fun readSettling(item: dynamic): Pair<String?, Int> {
        val settling = item?.settling
        return if (BriefingDynamic.isObject(settling)) {
            BriefingDynamic.str(settling.scenario)?.takeIf { it.isNotBlank() } to
                (BriefingDynamic.int(settling.untilTurn) ?: 0)
        } else {
            null to 0
        }
    }

    /**
     * An authored hero's stated gender, or `""` for "not stated" — a procedural hero must keep
     * rolling it from the portrait seed on reload rather than having one roll frozen into the save.
     */
    private fun authoredGender(female: Boolean?): String =
        when (female) {
            true -> "true"
            false -> "false"
            null -> ""
        }

    private fun readHero(item: dynamic): Pair<HeroDefinition, HeroState>? {
        val id = BriefingDynamic.str(item?.id)?.takeIf { it.isNotBlank() } ?: return null
        val heroId = HeroId(id)
        val definition =
            HeroDefinition(
                id = heroId,
                origin =
                    HeroValueCodec.enumOr(
                        BriefingDynamic.str(item?.origin),
                        HeroOrigin.entries,
                        HeroOrigin.PROCEDURAL,
                    ),
                displayName = BriefingDynamic.str(item?.name).orEmpty(),
                backgroundId = BriefingDynamic.str(item?.background).orEmpty(),
                biographyFacts = HeroValueCodec.readBiography(item?.bio),
                portrait =
                    PortraitComposition(
                        seed = BriefingDynamic.int(item?.portraitSeed) ?: 0,
                        layerIds = BriefingDynamic.strList(item?.portraitLayers),
                        artId = BriefingDynamic.str(item?.portraitArt)?.takeIf { it.isNotBlank() },
                        female = BriefingDynamic.str(item?.portraitFemale)?.takeIf { it.isNotBlank() }?.toBoolean(),
                        poolId = BriefingDynamic.str(item?.portraitPool)?.takeIf { it.isNotBlank() },
                    ),
                signatureTraitId = BriefingDynamic.str(item?.signature)?.takeIf { it.isNotBlank() },
            )
        return definition to readState(item, heroId)
    }

    private fun readState(
        item: dynamic,
        heroId: HeroId,
    ): HeroState {
        val (settlingScenario, settlingUntilTurn) = readSettling(item)
        return HeroState(
            heroId = heroId,
            rankId = BriefingDynamic.str(item?.rank).orEmpty(),
            status =
                HeroValueCodec.enumOr(
                    BriefingDynamic.str(item?.status),
                    HeroStatus.entries,
                    HeroStatus.ACTIVE,
                ),
            potential =
                HeroValueCodec.enumOr(
                    BriefingDynamic.str(item?.potential),
                    HeroPotential.entries,
                    HeroPotential.LINE_OFFICER,
                ),
            renown =
                HeroValueCodec.enumOr(
                    BriefingDynamic.str(item?.renown),
                    HeroRenown.entries,
                    HeroRenown.UNKNOWN,
                ),
            attributes = HeroValueCodec.readAttributes(item?.attributes),
            experience = BriefingDynamic.int(item?.xp) ?: 0,
            assignedFormationId =
                BriefingDynamic.str(item?.formation)?.takeIf { it.isNotBlank() }?.let { FormationId(it) },
            learnedTraitIds = BriefingDynamic.strList(item?.traits).toSet(),
            specializationEvidence = HeroValueCodec.readEvidence(item?.evidence),
            promotionsAwarded = BriefingDynamic.int(item?.promotions) ?: 0,
            settlingScenarioId = settlingScenario,
            settlingUntilTurn = settlingUntilTurn,
            nicknameId = BriefingDynamic.str(item?.nickname)?.takeIf { it.isNotBlank() },
            medals = BriefingDynamic.mapArray(item?.medals, HeroEventCodec::readHeroMedal),
            injuries = BriefingDynamic.mapArray(item?.injuries, HeroEventCodec::readHeroInjury),
            serviceEvents = BriefingDynamic.mapArray(item?.events, HeroEventCodec::readHeroEvent),
            associations = BriefingDynamic.mapArray(item?.associations, HeroRecordCodec::readAssociation),
            distinctions = BriefingDynamic.mapArray(item?.distinctions, HeroRecordCodec::readDistinction),
        )
    }
}
