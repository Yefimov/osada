package org.osada.hero

import org.osada.campaign.BriefingDynamic

/**
 * Turns a campaign save payload into an archive projection, and an archive entry into desk records
 * (`docs/design/hero-desk-and-profile-archive.md` §2).
 *
 * Pure with respect to the game: it parses JSON and decodes a roster, and never restores a game
 * into `GameHolder`, changes the selected campaign, loads UI settings or mutates hero state. That
 * is the hard constraint §2 places on reading live cards — the desk sits on the main menu, where a
 * side effect on the active campaign would be indistinguishable from data loss.
 *
 * Both directions go through [HeroSerializer], the save's own hero codec, so an archived roster and
 * a live one are literally the same bytes decoded by the same reader.
 */
internal object HeroSnapshotProjector {
    /**
     * Projects one campaign save [payload] into an archive entry, or null when the run has no hero
     * roster to archive at all (a campaign that has produced no formations writes no `heroes` key,
     * and an empty archive entry would be a card-less row in the desk rather than information).
     *
     * Broad catch: a save payload is untrusted input, and a corrupt one must degrade to "this run
     * contributes nothing" rather than take the desk or a mission transition down (§8).
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    fun project(
        payload: String,
        campaignRunId: String,
        runEpoch: String,
        campaignFile: String,
        campaignName: String,
        lastScenarioId: String,
        lastScenarioIndex: Int,
        updatedAt: Double,
        runStatus: ArchiveRunStatus,
    ): CampaignHeroArchive? =
        try {
            val campaign = JSON.parse<dynamic>(payload).campaign
            val heroes = campaign?.heroes
            if (!BriefingDynamic.isObject(heroes)) {
                null
            } else {
                CampaignHeroArchive(
                    campaignRunId = campaignRunId,
                    runEpoch = runEpoch,
                    campaignFile = campaignFile,
                    campaignName = campaignName,
                    lastScenarioId = lastScenarioId,
                    lastScenarioIndex = lastScenarioIndex,
                    updatedAt = updatedAt,
                    runStatus = runStatus,
                    rosterJson = JSON.stringify(heroes),
                    formationExperience = readFormationExperience(campaign?.coreUnits),
                )
            }
        } catch (e: Throwable) {
            console.warn("[osada] hero projection failed for '$campaignRunId'", e)
            null
        }

    /**
     * Unit experience per formation, read from the save's own core-unit list.
     *
     * This is the one dossier input the roster block cannot supply: experience lives on the
     * `GameUnit`, not on [CoreFormation], and the live dossier reads it off the deployed unit. A
     * unit with no formation id (a scenario-only or auxiliary unit) contributes nothing.
     */
    private fun readFormationExperience(coreUnits: dynamic): Map<String, Int> =
        BriefingDynamic
            .mapArray(coreUnits) { unit ->
                val formationId = BriefingDynamic.str(unit?.formationId)?.takeIf { it.isNotBlank() }
                val experience = BriefingDynamic.int(unit?.experience)
                if (formationId == null || experience == null) null else formationId to experience
            }.toMap()

    /** Projects the LIVE in-memory roster, for the archive upsert at a mission/campaign transition. */
    fun projectRoster(
        roster: HeroRoster,
        formationExperience: Map<String, Int>,
        campaignRunId: String,
        runEpoch: String,
        campaignFile: String,
        campaignName: String,
        lastScenarioId: String,
        lastScenarioIndex: Int,
        updatedAt: Double,
        runStatus: ArchiveRunStatus,
    ): CampaignHeroArchive? =
        if (roster.isEmpty) {
            null
        } else {
            CampaignHeroArchive(
                campaignRunId = campaignRunId,
                runEpoch = runEpoch,
                campaignFile = campaignFile,
                campaignName = campaignName,
                lastScenarioId = lastScenarioId,
                lastScenarioIndex = lastScenarioIndex,
                updatedAt = updatedAt,
                runStatus = runStatus,
                // A COPY, taken now: the archive must never hold a reference into a live mutable
                // roster (§3), and serializing is how this codebase already makes that copy.
                rosterJson = JSON.stringify(HeroSerializer.serialize(roster)),
                formationExperience = formationExperience,
            )
        }

    /**
     * Every hero in [archive] as a desk card, with the full dossier attached.
     *
     * Settling-in is reported as 0 rather than recomputed: [HeroTransferService.settlingTurnsLeft]
     * answers "how many turns of THIS battle are left", and there is no battle loaded when the desk
     * is open. A stale settling notice on a card the player cannot act on would be a false
     * statement about a live rule, which §26 of the hero brief forbids.
     */
    @Suppress("TooGenericExceptionCaught")
    fun records(
        archive: CampaignHeroArchive,
        source: HeroRecordSource,
        resumableRun: Boolean,
    ): List<HeroDeskRecord> {
        val roster =
            try {
                HeroSerializer.deserialize(JSON.parse<dynamic>(archive.rosterJson))
            } catch (e: Throwable) {
                console.warn("[osada] archived roster for '${archive.campaignRunId}' unreadable", e)
                return emptyList()
            }
        return roster.allDefinitions().mapNotNull { definition ->
            roster.state(definition.id)?.let { state ->
                record(archive, source, resumableRun, roster, definition, state)
            }
        }
    }

    private fun record(
        archive: CampaignHeroArchive,
        source: HeroRecordSource,
        resumableRun: Boolean,
        roster: HeroRoster,
        definition: HeroDefinition,
        state: HeroState,
    ): HeroDeskRecord {
        val formation = state.assignedFormationId?.let(roster::formation)
        val experience = formation?.let { archive.formationExperience[it.id.value] }
        val row = HeroDossierAssembler.commanderRow(definition, state, formation?.displayName)
        return HeroDeskRecord(
            heroId = definition.id.value,
            campaignRunId = archive.campaignRunId,
            runEpoch = archive.runEpoch,
            campaignName = archive.campaignName.ifBlank { archive.campaignFile },
            source = source,
            name = row.name,
            rank = row.rank,
            formationName = row.formationName,
            status = state.status,
            statusLabel = row.statusLabel,
            renown = state.renown,
            renownLabel = row.renown,
            renownClass = row.renownClass,
            potential = state.potential,
            potentialLabel = row.potential,
            notable = row.notable,
            resumableRun = resumableRun,
            // A survivor of a finished run reads as retired WITHOUT the stored status being
            // touched (§4); the fallen, missing and captured keep their own status exactly.
            retiredFromRun = archive.runStatus.terminal && HeroTransferService.isTransferEligible(state.status),
            inMemoriam = state.status == HeroStatus.KILLED,
            updatedAt = archive.updatedAt,
            dossier = HeroDossierAssembler.dossier(definition, state, formation, experience, settlingTurns = 0),
        )
    }
}
