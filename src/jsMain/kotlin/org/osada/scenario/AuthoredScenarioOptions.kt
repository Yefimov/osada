package org.osada.scenario

import org.w3c.dom.Element
import kotlin.js.Json
import kotlin.js.json

/**
 * One of OG's per-scenario Game-Settings switches: the XML attribute `add_scenario_options.py`
 * deploys, and the [Scenario] field it lands in.
 *
 * [read] and [write] are a pair on purpose — the table below is the ONLY place an attribute name
 * and its field are associated, so the XML parser, the save writer and the save reader cannot
 * drift apart. That drift is exactly what `docs/og-import-rules-backlog.md` recorded: the loader
 * knew 24 switches and the save file knew one of them.
 */
internal class AuthoredSwitch(
    val attribute: String,
    val read: (Scenario) -> Boolean?,
    val write: (Scenario, Boolean?) -> Unit,
)

/**
 * The complete set of authored scenario options, and the four operations every consumer needs:
 * [parse] them out of the scenario XML, [serialize] them into a save, [restore] them from one, and
 * [copy] them between two [Scenario] objects.
 *
 * **`null` means "the author did not say", and is NOT `false`.** 105 of the 502 deployed scenarios
 * name a source this install cannot read or find and carry no attributes at all; every reader
 * treats their silence as its own default rather than as a prohibition ([Scenario.canBuild]'s KDoc
 * carries the measurement). That distinction is why every operation here is absence-preserving: a
 * missing XML attribute stays `null`, a missing save key leaves whatever the scenario already had,
 * and a `null` field writes no key at all.
 *
 * 27 fields in total — 24 boolean switches, plus the prototype time frame, the custom music track
 * and the weather/ground link, which are authored the same way but are not booleans.
 */
internal object AuthoredScenarioOptions {
    /** Not a switch: OG's prototype time frame is a MONTH COUNT (`.xscn` `@848`). */
    const val PROTOTYPE_TIME_FRAME = "prototimeframe"

    /** Not a switch: the custom music track is a filename (`.xscn` `@127`). */
    const val MUSIC = "music"

    /**
     * Not a switch either, and the one option here that is not nullable: OSADA has always defaulted
     * it to `false`, so it keeps a plain [Boolean] and is always written.
     */
    const val WEATHER_CHANGES_GROUND = "weatherchg"

    /**
     * Every boolean option, in the order `add_scenario_options.py` deploys them.
     *
     * Three of them (`prototypes`, `subsneedlof`, `paradropocean`) are stored INVERTED relative to
     * the OG bit, so that the attribute always reads as a PERMISSION and `1` always means
     * "allowed" — which is what lets every reader treat a missing attribute as permitted with no
     * per-attribute special case. That inversion belongs to the importer; nothing here re-applies
     * it, and a save round-trips the deployed value.
     */
    val SWITCHES: List<AuthoredSwitch> =
        listOf(
            AuthoredSwitch("canbuild", { it.canBuild }, { s, v -> s.canBuild = v }),
            AuthoredSwitch("canblow", { it.canBlow }, { s, v -> s.canBlow = v }),
            AuthoredSwitch("canrepair", { it.canRepair }, { s, v -> s.canRepair = v }),
            AuthoredSwitch("extlos", { it.extendedLos }, { s, v -> s.extendedLos = v }),
            AuthoredSwitch("truedlof", { it.trueDirectLof }, { s, v -> s.trueDirectLof = v }),
            AuthoredSwitch("unitsblocklof", { it.unitsBlockLof }, { s, v -> s.unitsBlockLof = v }),
            AuthoredSwitch("barrage", { it.barrageAllowed }, { s, v -> s.barrageAllowed = v }),
            AuthoredSwitch("airzoc", { it.airZoc }, { s, v -> s.airZoc = v }),
            AuthoredSwitch("airmissions", { it.airMissions }, { s, v -> s.airMissions = v }),
            AuthoredSwitch("extnaval", { it.extendedNaval }, { s, v -> s.extendedNaval = v }),
            // Deployed 2026-08-28, each WITH its reader (`docs/og-fidelity-plan.md` §AD).
            // `add_scenario_options.py`'s standing policy is that an attribute nothing reads is a
            // promise nothing keeps, so these arrived with the rules that consult them.
            AuthoredSwitch("airintercept", { it.airIntercept }, { s, v -> s.airIntercept = v }),
            AuthoredSwitch("portsnosupply", { it.portsNoSupply }, { s, v -> s.portsNoSupply = v }),
            AuthoredSwitch(
                "portsnonavaldeploy",
                { it.portsNoNavalDeploy },
                { s, v -> s.portsNoNavalDeploy = v },
            ),
            // Deployed 2026-08-29 (§AF) under the same policy, both stored INVERTED:
            //   prototypes  -> GameScenarioLoading's award gate
            //   subsneedlof -> rules/ExtendedNaval.submarineLacksLineOfFire
            AuthoredSwitch("prototypes", { it.prototypesAllowed }, { s, v -> s.prototypesAllowed = v }),
            AuthoredSwitch("subsneedlof", { it.subsNeedLineOfFire }, { s, v -> s.subsNeedLineOfFire = v }),
            // Deployed 2026-08-30: the four largest authored options that had no consumer, plus
            // `basicstrength` and `typedvh`. 295 / 295 / 202 / 197 / 332 / 105 scenarios.
            AuthoredSwitch("truerange0", { it.trueRangeZero }, { s, v -> s.trueRangeZero = v }),
            AuthoredSwitch("truespotting0", { it.trueSpottingZero }, { s, v -> s.trueSpottingZero = v }),
            AuthoredSwitch(
                "reinfwhenactive",
                { it.reinforcementsWhenActive },
                { s, v -> s.reinforcementsWhenActive = v },
            ),
            AuthoredSwitch("capitalflak", { it.capitalShipsAsFlak }, { s, v -> s.capitalShipsAsFlak = v }),
            AuthoredSwitch("basicstrength", { it.useBasicStrength }, { s, v -> s.useBasicStrength = v }),
            AuthoredSwitch("typedvh", { it.typedVictoryHexes }, { s, v -> s.typedVictoryHexes = v }),
            // Deployed 2026-08-31: rules/ExtendedVictory.canWithdrawThrough, 15 scenarios.
            AuthoredSwitch(
                "ehmsuonly",
                { it.escapeHexesForMsuOnly },
                { s, v -> s.escapeHexesForMsuOnly = v },
            ),
            // rules/EmbarkRules.getDisembarkPositions, 19 scenarios. INVERTED like the two above.
            AuthoredSwitch(
                "paradropocean",
                { it.paradropOnOceanAllowed },
                { s, v -> s.paradropOnOceanAllowed = v },
            ),
            // Deployed 2026-09-01 with its reader, rules/PurchaseCap.recordDesignAddedCore, on 89
            // scenarios -- the other half of `opt_purchase_cap`.
            AuthoredSwitch(
                "coresoffcap",
                { it.coresExemptFromPurchaseCap },
                { s, v -> s.coresExemptFromPurchaseCap = v },
            ),
        )

    /**
     * Reads every authored option off the scenario's own `<map>` element.
     *
     * An ABSENT attribute stays `null` rather than becoming `false`, for the reason this object's
     * KDoc gives. [WEATHER_CHANGES_GROUND] is the exception and defaults to `false`, which is what
     * OSADA has always done with it.
     */
    fun parse(
        scenario: Scenario,
        mapElement: Element,
    ) {
        SWITCHES.forEach { option ->
            option.write(
                scenario,
                mapElement.getAttribute(option.attribute)?.toIntOrNull()?.let { it != 0 },
            )
        }
        // OG's prototype TIME FRAME, a month count rather than a switch. The importer writes it only
        // when `opt_custom_time_frame` is on, and 0 is OG's "switch on, never configured" state --
        // the manual's own default of 9 months, written down in `PROTOTYPE_DEFAULT_MONTHS`.
        scenario.prototypeTimeFrameMonths =
            mapElement.getAttribute(PROTOTYPE_TIME_FRAME)?.toIntOrNull()?.let {
                if (it > 0) it else PROTOTYPE_DEFAULT_MONTHS
            }
        // The custom music track, already gated on `opt_custom_music` by the importer. Deployed as
        // the author wrote it; `ui/ScenarioMusic` owns the case and format normalisation.
        scenario.musicTrack = mapElement.getAttribute(MUSIC)?.takeIf { it.isNotBlank() }
        scenario.weatherCanChangeGround =
            (mapElement.getAttribute(WEATHER_CHANGES_GROUND)?.toIntOrNull() ?: 0) != 0
    }

    /**
     * The save block: **only the options the author actually set**, under the same key names the
     * scenario XML uses, so one vocabulary serves both and a save stays readable by eye.
     *
     * An unauthored option writes NO KEY, which is what lets [restore] tell "the author said no"
     * from "the author said nothing" — the distinction the whole feature rests on, and the one a
     * `== true` coercion destroys. [WEATHER_CHANGES_GROUND] is always written because it is not
     * nullable.
     */
    fun serialize(scenario: Scenario): Json {
        val obj = json()
        SWITCHES.forEach { option ->
            option.read(scenario)?.let { obj[option.attribute] = it }
        }
        scenario.prototypeTimeFrameMonths?.let { obj[PROTOTYPE_TIME_FRAME] = it }
        scenario.musicTrack?.let { obj[MUSIC] = it }
        obj[WEATHER_CHANGES_GROUND] = scenario.weatherCanChangeGround
        return obj
    }

    /**
     * Applies a save's option block. A key the save does not carry LEAVES THE FIELD ALONE rather
     * than nulling it, so this composes with [parse]: a legacy save is back-filled from the
     * scenario XML first and then has the save's own keys applied over it, without the back-fill
     * being undone by the keys the save never had.
     */
    fun restore(
        scenario: Scenario,
        data: dynamic,
    ) {
        if (data == null || data == undefined) return
        SWITCHES.forEach { option ->
            (data[option.attribute] as? Boolean)?.let { option.write(scenario, it) }
        }
        (data[PROTOTYPE_TIME_FRAME] as? Int)?.let { scenario.prototypeTimeFrameMonths = it }
        (data[MUSIC] as? String)?.let { scenario.musicTrack = it }
        (data[WEATHER_CHANGES_GROUND] as? Boolean)?.let { scenario.weatherCanChangeGround = it }
    }

    /** Carries every authored option across a [Scenario.copy], nulls included. */
    fun copy(
        target: Scenario,
        source: Scenario,
    ) {
        SWITCHES.forEach { option -> option.write(target, option.read(source)) }
        target.prototypeTimeFrameMonths = source.prototypeTimeFrameMonths
        target.musicTrack = source.musicTrack
        target.weatherCanChangeGround = source.weatherCanChangeGround
    }
}
