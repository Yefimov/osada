package org.osada.hero

/**
 * A persistent core formation — design brief §18.
 *
 * This is the campaign-level record of a unit that outlives both its equipment and any single
 * scenario. The deployed [org.osada.model.GameUnit] carrying the matching `formationId` is the
 * tactical instance; this is the thing that "is the same brigade as last battle".
 *
 * **Deliberate omission: there is no `experience` field here**, although §18 lists one. Unit
 * experience already lives on `GameUnit.experience` and is already carried across scenarios by
 * `GameStateSerializer.serializeCoreUnit`. Mirroring it here would create two sources of truth
 * that drift the first time combat awards XP without the mirror being updated. §9.3's requirement
 * is that formation experience stay a SEPARATE CONCEPT from leader experience and recognition —
 * which it is: those two live on [HeroState.experience] and [recognition] respectively. Read
 * formation experience from the unit.
 *
 * [attachmentIds], [medals], [battleHonors] and [history] are declared and serialized now but
 * never written yet — see the note on [HeroState] for why the save format is being settled ahead
 * of the features. As of Phase 2 [recognition] and [emergenceChecks] ARE written.
 *
 * [emergenceChecks] is a monotonic count of emergence checks run against this formation. It is the
 * seed source [LeaderAcquisitionService] uses, so persisting it is what makes a reload replay the
 * same check result rather than reroll (§29.17).
 */
data class CoreFormation(
    val id: FormationId,
    val ownerId: Int,
    val country: Int,
    val displayName: String,
    val currentEquipmentId: Int,
    val unitClass: Int,
    val assignedHeroId: HeroId? = null,
    val recognition: Int = 0,
    val emergenceChecks: Int = 0,
    val attachmentIds: List<String> = emptyList(),
    val medals: List<FormationMedal> = emptyList(),
    val battleHonors: List<String> = emptyList(),
    val history: List<FormationEvent> = emptyList(),
)

/** A formation decoration (§9.2). Reserved for a later phase. */
data class FormationMedal(
    val medalId: String,
    val scenarioId: String,
)

/** A structured formation-history entry (§9.2, §19). Reserved for a later phase. */
data class FormationEvent(
    val eventId: String,
    val scenarioId: String,
    val turn: Int,
    val date: String? = null,
    val location: String? = null,
)
