package org.osada.hero

/**
 * The profile-level hero archive (`docs/design/hero-desk-and-profile-archive.md` §3).
 *
 * ## Why the roster travels as its own serialized block
 *
 * The design asks the archive to hold "a complete immutable copy of the data needed by the existing
 * dossier". There is already exactly one encoding of that data — the `campaign.heroes` block
 * [HeroSerializer] writes into every save — and [HeroDossierAssembler] is pure over the records it
 * decodes. So the archive stores that block verbatim in [CampaignHeroArchive.rosterJson] rather
 * than re-describing every hero field a second time.
 *
 * That is not a shortcut, it is the property §9 asks to be tested: a live record and an archived
 * record reach the dossier through the same decoder and the same assembler, so they cannot drift.
 * A second hand-written schema for the same 30 fields is precisely how "full dossier parity" turns
 * into a list of quietly missing medals two releases later.
 *
 * Everything else the design lists as archive-owned — run identity, epoch, campaign naming, last
 * operation, timestamp, run status, legacy provenance — lives beside it as ordinary typed fields,
 * because none of it exists in the roster block.
 *
 * ## What is deliberately NOT here
 *
 * No localized prose, no rendered portrait, no display strings: the block stores stable ids and a
 * portrait recipe/seed, and the desk resolves labels through [HeroDisplay] at render time, so an
 * archive written in Russian opens in English and back.
 */
internal enum class ArchiveRunStatus {
    /** A run that still has a resumable battle. */
    IN_PROGRESS,

    /** The campaign was played to its end. Survivors present as retired from THIS run (§4). */
    COMPLETED,

    /** The campaign ended in defeat. Also terminal; kept distinct so the desk can say which. */
    DEFEATED,

    ;

    val terminal: Boolean get() = this != IN_PROGRESS

    companion object {
        fun forOutcome(outcome: String): ArchiveRunStatus = if (outcome == "lose") DEFEATED else COMPLETED
    }
}

/**
 * One campaign run's archived roster.
 *
 * [runEpoch] distinguishes a deliberate replay from the prior run of the same campaign. It is
 * minted when a replacement is confirmed (§3), never derived from names or timestamps during a
 * restore — two runs of one campaign can hold the same officers, and only the epoch separates them.
 *
 * [formationExperience] carries the unit experience the dossier's formation panel shows. It is not
 * part of [CoreFormation] (the live dossier reads it off the deployed `GameUnit` instead), so it is
 * the one dossier input the roster block cannot supply on its own.
 */
internal data class CampaignHeroArchive(
    val campaignRunId: String,
    val runEpoch: String,
    val campaignFile: String,
    val campaignName: String,
    val lastScenarioId: String,
    val lastScenarioIndex: Int,
    val updatedAt: Double,
    val runStatus: ArchiveRunStatus,
    /** The `campaign.heroes` block, exactly as [HeroSerializer] writes it, as a JSON string. */
    val rosterJson: String,
    /** Formation id -> the unit's experience when this projection was taken. */
    val formationExperience: Map<String, Int> = emptyMap(),
)

/**
 * A migrated `osada_hall_of_fame` entry (§5).
 *
 * The legacy store kept only localized summary strings, so these records cannot be expanded into
 * traits, a portrait recipe or a service history — and the desk must not invent any. They are a
 * separate type on purpose: nothing here can be mistaken for a complete record, and no code path
 * can accidentally hand one to the full dossier.
 */
internal data class LegacyHeroRecord(
    val name: String,
    val rank: String,
    val renown: String,
    val potential: String,
    val status: String,
    val campaignName: String,
)

internal data class HeroArchive(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Keyed by campaign run id -- v1 retains exactly one historic run per campaign (§10). */
    val campaigns: Map<String, CampaignHeroArchive> = emptyMap(),
    val legacy: List<LegacyHeroRecord> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1

        val EMPTY = HeroArchive()
    }
}
