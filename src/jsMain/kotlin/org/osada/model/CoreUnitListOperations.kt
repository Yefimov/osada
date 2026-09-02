package org.osada.model

import org.osada.hero.FormationIdentity
import org.osada.hero.HeroCampaign
import org.osada.rules.GameRules
import org.osada.rules.PurchaseCap
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/**
 * Campaign core-unit list management: build, undeploy, restore (save-game load) and prune.
 * Split from the former unit-operations component (SRP / function-count limits).
 */
internal class CoreUnitListOperations(
    private val gameMap: GameMap,
) {
    companion object {
        private const val FULL_STRENGTH = 10
    }

    /**
     * Enrols [player]'s FIRST-scenario core: the units standing on their deployment hexes, plus
     * every formation the scenario author marked Make Core.
     *
     * The deployment-hex sweep is the original rule and stays the primary one -- an OG campaign's
     * opening force is placed on those hexes. [enrollAuthoredCoreUnits] adds the second source,
     * which that sweep could never see: `core="1"` on a formation the author put somewhere else on
     * the map entirely.
     */
    fun buildCoreUnitList(player: Player) {
        gameMap.units
            .filter {
                it.player?.id == player.id && it.getHex()?.isDeployment == player.id
            }.forEach { player.addCoreUnit(it) }
        enrollAuthoredCoreUnits(player)
    }

    /**
     * OG's **Make Core** (`.xscn` unit `@44` bit 2), enrolled rather than merely marked.
     *
     * [GameUnit.isCore] alone is enough for display and for the rule checks that read the flag, but
     * campaign persistence is owned by [Player]'s private core roster: `Player.setPlayerToHQ` walks
     * that roster and nothing else, so a unit wearing the marker without being enrolled would
     * disappear at the scenario transition. This is the ONE enrollment operation the backlog asks
     * for -- every loader path calls it rather than keeping its own copy.
     *
     * **Idempotent**, twice over: `Player.addCoreUnit` refuses a unit already in the roster by
     * identity, and calling this on a scenario that authors no Make Core unit does nothing at all.
     *
     * [GameUnit.isTemporaryBorrowed] opts out -- a formation lent for one battle is the one thing
     * that must never join the permanent roster, and `ensureFormationIds` excludes it for the same
     * reason.
     */
    fun enrollAuthoredCoreUnits(player: Player) {
        gameMap.units
            .filter { it.owner == player.id && it.isCore && !it.isTemporaryBorrowed }
            .forEach { enroll(player, it) }
    }

    /**
     * The single-unit form of [enrollAuthoredCoreUnits], for a formation that arrives AFTER the
     * load sweep -- an authored reinforcement wave, or a scenario event's spawn.
     *
     * Same three conditions, same idempotence. Kept beside the sweep rather than inlined at the
     * call site so "what makes a unit core" is stated once.
     */
    fun enrollIfAuthoredCore(
        player: Player,
        unit: GameUnit,
    ) {
        if (unit.owner == player.id && unit.isCore && !unit.isTemporaryBorrowed) {
            enroll(player, unit)
        }
    }

    /**
     * Adds an AUTHORED core formation to the roster and books it against OG's purchase cap.
     *
     * `addCoreUnit` returns false for a formation already enrolled, which is what keeps every
     * enrollment path idempotent AND keeps a re-run from charging the cap twice. The charge itself
     * is `opt_cores_off_cap`'s other half -- see `rules/PurchaseCap.recordDesignAddedCore`.
     */
    private fun enroll(
        player: Player,
        unit: GameUnit,
    ) {
        if (player.addCoreUnit(unit)) PurchaseCap.recordDesignAddedCore(player, unit)
    }

    /**
     * Gives every unit directly controlled by [player] a formation id, across the map, deployment
     * tray and any additional scripted-unit collection. [GameUnit.isCore] is deliberately not an
     * eligibility condition; only [GameUnit.isTemporaryBorrowed] opts a controlled unit out.
     *
     * Idempotent: a unit restored from a save keeps the id it already had; only missing ids are
     * minted, seeded past every id already present so two units never collide.
     */
    fun ensureFormationIds(
        player: Player,
        additionalUnits: Iterable<GameUnit> = emptyList(),
    ) {
        val candidates = gameMap.units + player.getCoreUnitList() + additionalUnits
        val existing =
            buildSet {
                candidates.mapNotNullTo(this) { it.formationId?.takeIf(String::isNotEmpty) }
                HeroCampaign.roster().allFormations().mapTo(this) { it.id.value }
            }.toMutableSet()
        candidates
            .filter { it.owner == player.id && !it.isTemporaryBorrowed }
            .distinct()
            .forEach { unit -> existing.add(FormationIdentity.ensure(unit, existing).value) }
    }

    /**
     * Lift the player's core units OFF the map back into the (undeployed) tray, so the player
     * deploys them by hand — the Open General campaign start behaviour. On the FIRST campaign
     * scenario [buildCoreUnitList] makes on-deploy-hex units core but leaves them DEPLOYED, unlike
     * later scenarios where the core arrives undeployed (setPlayerToHQ at the previous scenario's
     * end + restoreCoreUnitList). This makes the first scenario consistent: same tray + buy phase.
     * Safe when there are no core units (e.g. no deploy hexes) — it simply does nothing.
     */
    fun undeployCoreUnits(player: Player) {
        player.getCoreUnitList().toList().filter(::liftsIntoTray).forEach { unit ->
            val pos = unit.getPos()
            if (pos != null) {
                GameRules.setZOCRange(gameMap, unit, false)
                GameRules.setSpotRange(gameMap, unit, false)
                gameMap.map
                    ?.getOrNull(pos.row)
                    ?.getOrNull(pos.col)
                    ?.delUnit(unit)
            }
            unit.isDeployed = false
            gameMap.units.remove(unit)
        }
        gameMap.updateUnitList()
    }

    /**
     * Whether [undeployCoreUnits] may lift [unit] off the map into the buy/deploy tray.
     *
     * Only a formation standing on one of its owner's DEPLOYMENT hexes -- which is exactly the set
     * [buildCoreUnitList]'s first sweep enrols, and therefore exactly the pre-Make-Core behaviour.
     * An authored Make Core formation placed anywhere else is PLACED CONTENT: the author chose that
     * hex, and lifting it into the tray would silently rewrite the opening position of the battle
     * while adding it to the roster.
     *
     * A unit already off the map (no position) is left alone; it is in the tray already.
     */
    private fun liftsIntoTray(unit: GameUnit): Boolean = unit.getHex()?.let { it.isDeployment == unit.owner } ?: false

    fun restoreCoreUnitList(
        player: Player,
        saved: List<dynamic>,
    ) {
        // OWNERSHIP IS CHECKED, and it did not used to be. Before Make Core was imported, `isCore`
        // could only be set by campaign machinery and therefore only ever on the campaign player's
        // own units, so the filter was safe without it. A scenario author may now tick Make Core on
        // ANY player's formation -- including the AI's -- and enrolling one of those into the human
        // roster would hand the player an enemy unit at the next transition.
        gameMap.units
            .filter { it.owner == player.id && it.isCore && it.isDeployed }
            .forEach { player.addCoreUnit(it) }
        saved.filter { !(it.isDeployed as? Boolean ?: false) }.forEach { savedUnit ->
            player.addCoreUnit(buildRestoredUnit(savedUnit, player))
        }
    }

    private fun buildRestoredUnit(
        savedUnit: dynamic,
        player: Player,
    ): GameUnit {
        val unit = GameUnit((savedUnit.eqid as? Int) ?: 0)
        unit.id = savedUnit.id as? Int ?: -1
        unit.owner = savedUnit.owner as? Int ?: player.id
        unit.flag = savedUnit.flag as? Int ?: (player.country + 1)
        unit.strength = savedUnit.strength as? Int ?: FULL_STRENGTH
        unit.experience = savedUnit.experience as? Int ?: 0
        unit.leader = savedUnit.leader as? Int ?: -1
        unit.carrier = savedUnit.carrier as? Int ?: 0
        unit.isMounted = savedUnit.isMounted as? Boolean ?: false
        unit.isCore = true
        unit.isDeployed = false
        unit.hasOverstrength = savedUnit.hasOverstrength as? Boolean ?: false
        unit.customName = savedUnit.customName as? String // optional key (rename feature)
        unit.isTemporaryBorrowed = savedUnit.temporaryBorrowed as? Boolean ?: false
        unit.stalinRegimeBoosted = savedUnit.stalinRegimeBoosted as? Boolean ?: false
        // Core units bypass GameStateDeserializer because the campaign roster has a deliberately
        // smaller save shape. Restore the scenario-instance flags here too: in particular, an OG
        // Must-Survive unit may be the one formation carried through an entire campaign.
        unit.applySerializedScenarioProperties(savedUnit)
        if (unit.stalinRegimeBoosted) {
            unit.moveLeft *= GameUnit.STALIN_REGIME_MULTIPLIER
            unit.ammo *= GameUnit.STALIN_REGIME_MULTIPLIER
            unit.fuel *= GameUnit.STALIN_REGIME_MULTIPLIER
        }
        // Carried, never re-minted: this is the scenario transition the formation id exists to
        // survive. A pre-hero save has no key here and gets one on addCoreUnit below.
        unit.formationId = savedUnit.formationId as? String
        unit.player = player
        applyRestoredTransport(savedUnit, unit)
        return unit
    }

    /**
     * Restores a core unit's saved transport.
     *
     * The saved transport is pulled into a local and null-checked explicitly rather than written as
     * `savedUnit.transport?.let { }`: on a `dynamic` receiver, `?.let` is not the Kotlin scope
     * function but a MEMBER lookup on the JS object, so every unit that actually had a transport
     * threw "tmp0_safe_receiver.let is not a function" and aborted the whole core-roster restore
     * partway through.
     */
    private fun applyRestoredTransport(
        savedUnit: dynamic,
        unit: GameUnit,
    ) {
        val transport: dynamic = savedUnit.transport
        if (transport == null || transport == undefined) return
        val teqid = transport.eqid as? Int ?: 0
        if (teqid <= 0) return
        unit.setTransport(teqid)
        unit.transport?.ammo = transport.ammo as? Int ?: 0
        unit.transport?.fuel = transport.fuel as? Int ?: 0
    }

    fun removeNonCampaignUnits(player: Player) {
        var removed = false
        gameMap.units.filter { it.player?.id != player.id || !it.isCore }.forEach { unit ->
            val hex = unit.getHex()
            if (hex?.isDeployment == player.id) {
                hex.delUnit(unit)
                unit.destroyed = true
                unit.nodossier = true
                removed = true
            }
        }
        if (removed) gameMap.updateUnitList()
    }
}
