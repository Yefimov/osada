package org.osada.hero

import org.osada.campaign.BriefingDynamic
import kotlin.js.json

/**
 * JSON codec for the [HeroArchive], written key by key rather than through `JSON.stringify` of a
 * Kotlin object — Kotlin/JS IR mangles property names (AGENTS.md, "Porting gotchas"), and these
 * keys are a storage contract shared with the profile backup file.
 *
 * Compatibility follows [HeroSerializer]'s two rules exactly:
 *
 * - **backward, by absence** — a profile backup written before the archive existed has no
 *   `heroArchive` field at all, [parse] gets null and yields an empty archive (§4);
 * - **forward, by tolerance** — an unknown key is ignored, a corrupt campaign entry drops itself
 *   and leaves its siblings intact, and an unreadable archive as a whole degrades to empty rather
 *   than taking the campaign register down with it (§8).
 *
 * [ArchiveRunStatus] is written by name and read through a defaulting lookup, so reordering the
 * enum or reading a newer build's archive cannot throw.
 */
internal object HeroArchiveCodec {
    fun serialize(archive: HeroArchive): dynamic =
        json(
            Pair("schemaVersion", archive.schemaVersion),
            Pair(
                "campaigns",
                archive.campaigns.values
                    .map(::serializeCampaign)
                    .toTypedArray(),
            ),
            Pair("legacy", archive.legacy.map(::serializeLegacy).toTypedArray()),
        )

    fun stringify(archive: HeroArchive): String = JSON.stringify(serialize(archive))

    @Suppress("TooGenericExceptionCaught")
    fun parse(value: dynamic): HeroArchive {
        if (!BriefingDynamic.isObject(value)) return HeroArchive.EMPTY
        return try {
            HeroArchive(
                schemaVersion = BriefingDynamic.int(value.schemaVersion) ?: HeroArchive.SCHEMA_VERSION,
                campaigns =
                    BriefingDynamic
                        .mapArray(value.campaigns) { readCampaign(it) }
                        .associateBy { it.campaignRunId },
                legacy = BriefingDynamic.mapArray(value.legacy) { readLegacy(it) },
            )
        } catch (e: Throwable) {
            console.warn("[OSADA] hero archive unreadable, starting from an empty archive", e)
            HeroArchive.EMPTY
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun parseString(raw: String?): HeroArchive {
        if (raw.isNullOrBlank()) return HeroArchive.EMPTY
        return try {
            parse(JSON.parse<dynamic>(raw))
        } catch (e: Throwable) {
            console.warn("[OSADA] hero archive JSON unreadable", e)
            HeroArchive.EMPTY
        }
    }

    private fun serializeCampaign(archive: CampaignHeroArchive): dynamic =
        json(
            Pair("campaignRunId", archive.campaignRunId),
            Pair("runEpoch", archive.runEpoch),
            Pair("campaignFile", archive.campaignFile),
            Pair("campaignName", archive.campaignName),
            Pair("lastScenarioId", archive.lastScenarioId),
            Pair("lastScenarioIndex", archive.lastScenarioIndex),
            Pair("updatedAt", archive.updatedAt),
            Pair("runStatus", archive.runStatus.name),
            Pair("roster", archive.rosterJson),
            Pair(
                "formationExperience",
                archive.formationExperience
                    .map { (id, xp) -> json(Pair("id", id), Pair("xp", xp)) }
                    .toTypedArray(),
            ),
        )

    /** A campaign entry without a run id cannot be keyed, so it is dropped rather than defaulted. */
    private fun readCampaign(item: dynamic): CampaignHeroArchive? {
        val runId = BriefingDynamic.str(item?.campaignRunId)?.takeIf { it.isNotBlank() } ?: return null
        return CampaignHeroArchive(
            campaignRunId = runId,
            runEpoch = BriefingDynamic.str(item?.runEpoch).orEmpty(),
            campaignFile = BriefingDynamic.str(item?.campaignFile).orEmpty(),
            campaignName = BriefingDynamic.str(item?.campaignName).orEmpty(),
            lastScenarioId = BriefingDynamic.str(item?.lastScenarioId).orEmpty(),
            lastScenarioIndex = BriefingDynamic.int(item?.lastScenarioIndex) ?: 0,
            updatedAt = (item?.updatedAt as? Double) ?: 0.0,
            runStatus =
                HeroValueCodec.enumOr(
                    BriefingDynamic.str(item?.runStatus),
                    ArchiveRunStatus.entries,
                    ArchiveRunStatus.IN_PROGRESS,
                ),
            rosterJson = BriefingDynamic.str(item?.roster).orEmpty(),
            formationExperience =
                BriefingDynamic
                    .mapArray(item?.formationExperience) { entry ->
                        val id = BriefingDynamic.str(entry?.id)?.takeIf { it.isNotBlank() }
                        val xp = BriefingDynamic.int(entry?.xp)
                        if (id == null || xp == null) null else id to xp
                    }.toMap(),
        )
    }

    private fun serializeLegacy(record: LegacyHeroRecord): dynamic =
        json(
            Pair("name", record.name),
            Pair("rank", record.rank),
            Pair("renown", record.renown),
            Pair("potential", record.potential),
            Pair("status", record.status),
            Pair("campaign", record.campaignName),
        )

    private fun readLegacy(item: dynamic): LegacyHeroRecord? {
        val name = BriefingDynamic.str(item?.name)?.takeIf { it.isNotBlank() } ?: return null
        return LegacyHeroRecord(
            name = name,
            rank = BriefingDynamic.str(item?.rank).orEmpty(),
            renown = BriefingDynamic.str(item?.renown).orEmpty(),
            potential = BriefingDynamic.str(item?.potential).orEmpty(),
            status = BriefingDynamic.str(item?.status).orEmpty(),
            campaignName = BriefingDynamic.str(item?.campaign).orEmpty(),
        )
    }
}
