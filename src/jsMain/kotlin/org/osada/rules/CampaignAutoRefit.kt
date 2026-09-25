package org.osada.rules

import org.osada.campaign.CampaignEffect
import org.osada.campaign.CampaignEffectLedger
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.refillAmmoFuel
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's free refit of the core army between campaign battles ([RuleKey.CAMPAIGN_AUTO_REFIT]).
 *
 * ## What OG does
 *
 * OG's campaign file carries "cam auto-refit/resupply" per scenario (the author's own changelog,
 * 0.92.0.0 and 0.90.51.0). The `.xcam` flag that switches the refit OFF is `@540` bit `0x02`: in
 * `forward` it is set on exactly the five scenarios whose briefings say so -- Forward26 *"This
 * scenario will be no-auto-refit, too"*, Forward27 *"You can use the massive pp for refitting"* --
 * and the campaign's own notes tell the player to *"save some pp"* for them. Everywhere else the army
 * arrives whole. That the refit costs nothing is read from those same notes and from
 * `green_autorefit`, whose only stated price is experience; it has not been measured in OG itself.
 *
 * ## How it runs here
 *
 * `Game.recordCampaignOutcome` queues a `CampaignEffect.AutoRefit` for the scenario the campaign
 * routes to, and the ordinary pending-effect pass applies it once that scenario has loaded. That
 * reuse is the point: the effect ledger already guarantees exactly-once application and survives a
 * save, so reloading inside the next battle can never refit the army a second time.
 *
 * Only strength below [FULL_STRENGTH] is restored; an overstrength formation keeps its extra
 * points. With `green_autorefit` the intake arrives green, through the same arithmetic the paid tray
 * pass uses (`model/ReserveRefit`), so the two refits can never disagree about what a replacement
 * does to a formation's experience.
 */
internal object CampaignAutoRefit {
    private const val FULL_STRENGTH = 10

    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.CAMPAIGN_AUTO_REFIT, false)

    /**
     * Whether the campaign's own record for a scenario leaves the refit on. Absent means on: it is
     * OG's default, and it is also what every PM campaign ran before the refit became paid.
     *
     * **A briefing that offers a resupply CHOICE switches it off too.** Story Mode dialogue is
     * OSADA's writing, authored against the paid refit: `n_willhelmsh` and `n_berlin` let the
     * player trade something for a full resupply, and OG refits both of them. Refitting on arrival
     * would make that trade worthless before the briefing even opened, so where the story asks the
     * question, the story's answer stands.
     */
    fun authoredFor(scenarioRecord: dynamic): Boolean =
        (refitsStrength(scenarioRecord) || resupplies(scenarioRecord)) && !offersResupplyChoice(scenarioRecord)

    /**
     * OG keeps the two halves apart: *"Disable auto-refit"* (`@540` `0x02`, `autorefit: false`) and
     * *"Disable auto-supply"* (`@540` `0x20`, `autosupply: false`) are separate checkboxes, told
     * apart by the owner's controlled diff of 2026-09-25. Refit is strength; supply is ammo and
     * fuel. `rhu` switches the refit off for all ten battles and the supply off for one, so nine of
     * them still arrive with full tanks and magazines.
     */
    private fun refitsStrength(scenarioRecord: dynamic): Boolean = (scenarioRecord?.autorefit as? Boolean) != false

    private fun resupplies(scenarioRecord: dynamic): Boolean = (scenarioRecord?.autosupply as? Boolean) != false

    /** True when any dialogue choice in [scenarioRecord]'s briefing carries a `resupply` effect. */
    private fun offersResupplyChoice(scenarioRecord: dynamic): Boolean {
        val lines = scenarioRecord?.briefing?.dialogue as? Array<dynamic> ?: return false
        return lines.any { line ->
            val choices = line?.choices as? Array<dynamic> ?: return@any false
            choices.any { choice ->
                val effects = choice?.effects as? Array<dynamic> ?: return@any false
                effects.any { it?.type == "resupply" }
            }
        }
    }

    /** Effect id for the [serial]-th refit of the run, on the transition [fromScenario] -> [toScenario]. */
    fun effectId(
        serial: Int,
        fromScenario: String,
        toScenario: String,
    ): String = "$ID_PREFIX$serial:$fromScenario>$toScenario"

    /**
     * The refit to queue for this transition, or null when one is already waiting.
     *
     * The id carries how many refits this run has already APPLIED, so every real transition gets a
     * fresh one -- including a second pass through the same pair of scenarios after a replay, which
     * a `from>to` id alone would have found already applied and skipped. A refit still waiting in
     * the queue means this is the same battle completing twice (the move-capture and end-turn paths
     * can both reach `continueCampaign`), and that must not queue a second one.
     */
    fun nextEffect(
        ledger: CampaignEffectLedger,
        fromScenario: String,
        toScenario: String,
    ): CampaignEffect.AutoRefit? {
        if (ledger.pending.any { it.effect is CampaignEffect.AutoRefit }) return null
        val serial = ledger.applied.count { it.startsWith(ID_PREFIX) }
        return CampaignEffect.AutoRefit(effectId(serial, fromScenario, toScenario))
    }

    private const val ID_PREFIX = "og-autorefit:"

    /** Refits every surviving core formation of [player] as [scenarioRecord] -- the battle being
     *  entered -- allows; returns how many it changed. No record means both halves, OG's default. */
    fun apply(
        player: Player,
        scenarioRecord: dynamic = null,
    ): Int {
        val strength = refitsStrength(scenarioRecord)
        val supply = resupplies(scenarioRecord)
        return player
            .getCoreUnitList()
            .filterNot { it.destroyed }
            .count { refit(it, strength, supply) }
    }

    private fun refit(
        unit: GameUnit,
        strength: Boolean,
        resupply: Boolean,
    ): Boolean {
        val missing = if (strength) (FULL_STRENGTH - unit.strength).coerceAtLeast(0) else 0
        val supply = unit.ammo to unit.fuel
        if (missing > 0) {
            // Experience BEFORE strength: the dilution averages the veterans present now with the
            // intake joining them.
            if (GreenReplacements.autorefitUsesGreens()) {
                unit.experience = GreenReplacements.experienceAfter(unit, missing)
            }
            unit.strength += missing
        }
        if (resupply) unit.refillAmmoFuel()
        return missing > 0 || supply != (unit.ammo to unit.fuel)
    }
}
