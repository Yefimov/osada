package org.osada.hero

import org.osada.i18n.I18n

/*
 * Pure read model for the main-menu Hero Desk
 * (`docs/design/hero-desk-and-profile-archive.md` §§2, 6).
 *
 * Everything here is a total function over its inputs: no storage, no GameHolder, no DOM. That is
 * the point — §2 requires reading every card to leave the live game completely alone, and §9 asks
 * for every filter predicate and the stable sort to be unit-tested. HeroDeskPresenter supplies the
 * records and renders them; it makes no decisions of its own about who belongs where.
 */

/** Where a desk card's facts came from. Shown to the player only where the difference matters. */
internal enum class HeroRecordSource {
    /** A campaign run that still has a resumable battle -- its own current save is authoritative. */
    LIVE,

    /** The profile archive: a career retained after the run was completed, cleared or abandoned. */
    ARCHIVE,

    /** A migrated `osada_hall_of_fame` summary (§5). Never a complete record. */
    LEGACY,
}

internal enum class HeroDeskFilter {
    ALL,
    ACTIVE,
    LEGENDARY,
    FALLEN,
    HALL_OF_FAME,
}

/**
 * One desk card.
 *
 * [dossier] is null only for [HeroRecordSource.LEGACY]: a legacy summary carries no traits, no
 * portrait recipe and no service history, and §5 forbids inventing any of them. Every other record
 * carries the full [LeaderDossierView] the in-campaign dossier uses, assembled by the same
 * [HeroDossierAssembler] from the same decoded roster — which is what makes live/archive parity a
 * structural property rather than a promise.
 */
internal data class HeroDeskRecord(
    val heroId: String,
    val campaignRunId: String,
    val runEpoch: String,
    val campaignName: String,
    val source: HeroRecordSource,
    val name: String,
    val rank: String,
    val formationName: String?,
    val status: HeroStatus?,
    val statusLabel: String,
    val renown: HeroRenown?,
    val renownLabel: String,
    val renownClass: String,
    val potential: HeroPotential?,
    val potentialLabel: String,
    /** The existing Hall of Fame predicate: renowned, authored legendary, or fallen. */
    val notable: Boolean,
    /** True when this record's campaign run can still be resumed. */
    val resumableRun: Boolean,
    /**
     * A survivor of a finished run. Presented as **retired from this run** WITHOUT mutating the
     * stored [status] (§4) — the original status stays in the record and in the dossier.
     */
    val retiredFromRun: Boolean,
    val inMemoriam: Boolean,
    /** Last-service time, for the stable sort's second key. */
    val updatedAt: Double,
    val dossier: LeaderDossierView?,
) {
    /** Identity for live-over-archive suppression (§2). */
    val identity: Pair<String, String> get() = campaignRunId to heroId

    val key: String get() = "$campaignRunId|$runEpoch|$heroId"
}

internal object HeroDeskModel {
    /**
     * Live records win over archived ones for the same campaign run and hero, so a mission-end
     * archive written beside a later autosave never shows the same officer twice (§2).
     *
     * v1 retains exactly one historic run per campaign (§10) and a confirmed replay deletes the
     * prior archive before the new run becomes active, so `(campaignRunId, heroId)` is already
     * unique across the two sources; `runEpoch` rides along on the record for display and for the
     * day that constraint is relaxed, but is deliberately not part of the suppression key — a live
     * run that has not been archived yet has no epoch to match against, and keying on it would
     * duplicate every officer in exactly the case the rule exists to cover.
     */
    fun merge(
        live: List<HeroDeskRecord>,
        archived: List<HeroDeskRecord>,
        legacy: List<HeroDeskRecord>,
    ): List<HeroDeskRecord> {
        val liveIdentities = live.map { it.identity }.toSet()
        val complete = live + archived.filterNot { it.identity in liveIdentities }
        val completeNames = complete.map { normalized(it.name) to normalized(it.campaignName) }.toSet()
        // A legacy summary is replaced the moment a complete record for the same officer and
        // campaign exists, and the player can see the replacement because the complete card is
        // right there in the same list (§5).
        return complete + legacy.filterNot { (normalized(it.name) to normalized(it.campaignName)) in completeNames }
    }

    fun matches(
        record: HeroDeskRecord,
        filter: HeroDeskFilter,
    ): Boolean =
        when (filter) {
            HeroDeskFilter.ALL -> true
            // "Non-terminal commander belonging to a resumable run" (§6). Non-terminal is
            // [HeroTransferService.isTransferEligible] — the codebase's existing single answer to
            // "can this officer still be posted somewhere?" — rather than a second status set here.
            HeroDeskFilter.ACTIVE ->
                record.resumableRun &&
                    !record.retiredFromRun &&
                    record.status?.let(HeroTransferService::isTransferEligible) == true

            HeroDeskFilter.LEGENDARY ->
                record.potential == HeroPotential.AUTHORED_LEGENDARY || record.renown == HeroRenown.LEGEND

            HeroDeskFilter.FALLEN -> record.status == HeroStatus.KILLED
            HeroDeskFilter.HALL_OF_FAME -> record.notable
        }

    /** Hero, campaign and formation names, case- and whitespace-insensitively (§6). */
    fun matchesSearch(
        record: HeroDeskRecord,
        query: String,
    ): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return listOfNotNull(record.name, record.campaignName, record.formationName, record.rank)
            .any { it.lowercase().contains(needle) }
    }

    /**
     * Renown descending, then last service, then name. Stable and total: two records that tie on
     * every key keep a deterministic order through the hero id, so the list never reshuffles
     * between two openings of the same desk.
     */
    fun sorted(records: List<HeroDeskRecord>): List<HeroDeskRecord> =
        records.sortedWith(
            compareByDescending<HeroDeskRecord> { it.renown?.ordinal ?: -1 }
                .thenByDescending { it.updatedAt }
                .thenBy { it.name.lowercase() }
                .thenBy { it.heroId },
        )

    fun view(
        records: List<HeroDeskRecord>,
        filter: HeroDeskFilter,
        query: String,
    ): List<HeroDeskRecord> = sorted(records.filter { matches(it, filter) && matchesSearch(it, query) })

    fun filterLabel(filter: HeroDeskFilter): String =
        when (filter) {
            // Reuses the phrase the old main-menu entry carried, rather than minting a second
            // translation of "Hall of Fame" -- the collection did not change name, it became a view.
            HeroDeskFilter.HALL_OF_FAME -> I18n.t("menu.main.hall_of_fame.label")
            else -> I18n.t("hero.desk.filter.${filter.name.lowercase()}")
        }

    /**
     * Turns a migrated Hall of Fame summary into a card. Its enum-typed fields stay null: the
     * legacy store kept only localized display strings, and guessing a [HeroStatus] back out of
     * one would be exactly the invention §5 forbids. The consequence is deliberate — a legacy
     * record answers ALL and Hall of Fame (it was harvested by that very predicate) and no other
     * filter.
     */
    fun legacyRecord(record: LegacyHeroRecord): HeroDeskRecord =
        HeroDeskRecord(
            heroId = "legacy:${normalized(record.name)}:${normalized(record.campaignName)}",
            campaignRunId = "",
            runEpoch = "",
            campaignName = record.campaignName,
            source = HeroRecordSource.LEGACY,
            name = record.name,
            rank = record.rank,
            formationName = null,
            status = null,
            statusLabel = record.status,
            renown = null,
            renownLabel = record.renown,
            renownClass = "",
            potential = null,
            potentialLabel = record.potential,
            notable = true,
            resumableRun = false,
            retiredFromRun = false,
            inMemoriam = false,
            updatedAt = 0.0,
            dossier = null,
        )

    private fun normalized(value: String): String = value.trim().lowercase()
}
