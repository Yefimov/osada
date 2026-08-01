package org.osada.model

import org.osada.GameHolder
import org.osada.hero.HeroCampaign

/**
 * Every formation id currently spoken for, from all three places one can live: [coreUnits] (the
 * roster being added to), the hero roster, and units on the map that are not (yet) core.
 *
 * **The map is not optional here.** `FormationIdentity.nextFor` mints `highest + 1` from the ids it
 * is shown, so seeding it from the core roster alone hands out ids that pre-placed scenario units
 * already hold — `GameMap.ensureFormationIds` gives every player-owned unit on the map an id at
 * scenario load, whether or not it is core.
 *
 * The collision is worst exactly where it is least visible. N_Kiel has **no authored deploy hexes**,
 * so `buildCoreUnitList` enrols nobody, the roster starts empty, and the first purchased unit is
 * minted `F-<id>-1` — the id the first pre-placed unit already carries. Two formations then share
 * one id, and `collectPersistentCampaignUnits` used to resolve that by dropping one of them, so the
 * unit never reached the next scenario. The player's own carry-over log said it:
 * `14/35 formations; destroyed=0, temporary=0, nodossier=0, duplicateIds=12` — twelve units,
 * including artillery bought that battle, silently deleted (reported 2026-07-31, "I remember that
 * I've bought more 7,7cm FK 96, but now in Phase Deploy I have only 1 option").
 *
 * Top-level rather than a [Player] member only to keep that class within the project's
 * function-count limit.
 */
internal fun knownFormationIds(coreUnits: List<GameUnit>): Collection<String> =
    buildSet {
        coreUnits.mapNotNullTo(this) { it.formationId?.takeIf(String::isNotEmpty) }
        HeroCampaign.roster().allFormations().mapTo(this) { it.id.value }
        GameHolder.instance
            ?.scenario
            ?.map
            ?.units
            ?.mapNotNullTo(this) { it.formationId?.takeIf(String::isNotEmpty) }
    }
