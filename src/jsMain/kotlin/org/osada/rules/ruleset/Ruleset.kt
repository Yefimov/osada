package org.osada.rules.ruleset

/*
 * The typed ruleset model (`docs/design/ruleset-profiles.md` §§2-3).
 *
 * Three different objects, deliberately not collapsed into one "ruleset": a reusable browser-local
 * overlay ([RulesetProfile]), one rule after resolution ([ResolvedRule]), and the complete immutable
 * result the engine actually executes ([ResolvedRuleset]).
 *
 * Every key here steers a branch OSADA already executes. A key for a mechanic the engine does not
 * run is an explicit non-goal (§10) -- it would make this window a list of promises.
 */

/**
 * Bumped when the MEANING of a key changes, never for display copy. Part of the hash (§5).
 *
 * 2 (2026-08-17) added the four weather switches. Additive: a schema-1 profile stays selectable and
 * simply names none of them, so those four keep following the content, which is exactly the
 * behaviour that profile already had.
 *
 * 3 (2026-08-18) added [RuleKey.REPLACEMENT_EXPERIENCE]. Additive on the same terms, but note that
 * its default is ON: a schema-2 profile that names nothing gets dilution, because the owner's
 * decision is that dilution is what OSADA should do. A campaign that wants the old behaviour selects
 * it explicitly.
 *
 * 4 (2026-08-18) added [RuleKey.HEAVY_MOVE_FIRE], [RuleKey.SNOW_FUEL], [RuleKey.SUPPORT_FIRE_FALLOFF],
 * [RuleKey.DRY_UNIT_PENALTIES] and [RuleKey.MINEFIELDS] (`docs/og-fidelity-plan.md` B.1, B.2, B.7,
 * B.8 and C.1). Additive, and every one of the five defaults to the behaviour OSADA already ran, so
 * a schema-1..3 profile that names none of them is byte-identical in play to what it was.
 *
 * 5 (2026-08-19) added [RuleKey.AIR_FUEL], [RuleKey.INITIATIVE_MODEL], [RuleKey.SPOTTING_MEMORY],
 * [RuleKey.INSTALLATION_SPOTTING] and [RuleKey.GROUND_AUTO_SUPPLY] (`docs/og-fidelity-plan.md` B.3,
 * B.6, B.4, B.5 and A.3 item 2) -- the last five rules that document names. Additive on the same
 * terms as schema 4, and every one of the five again defaults to what OSADA already ran. This is
 * The built-in profiles are Author's Vision and OSADA Default; the third, Open General Fidelity,
 * was retired 2026-08-28 (`docs/og-fidelity-plan.md` §AC).
 *
 * 6 (2026-08-25) added [RuleKey.COUNTERBATTERY], [RuleKey.EXTENDED_LOS],
 * [RuleKey.BUILD_AND_REPAIR] and [RuleKey.EQUIPMENT_TOGGLES] -- three of the five Open General
 * OPTIONAL RULES (manual section 9)
 * that `docs/og-fidelity-plan.md` section C never listed at all. Additive on the same terms as
 * schemas 4 and 5, and all three again default to what OSADA already ran, so the 502 shipped
 * scenarios stay arithmetically unchanged until a profile asks otherwise.
 *
 * 7 (2026-08-26) added [RuleKey.BARRAGE] -- the fourth of section 9's optional rules, and the one
 * schema 6 called permanently keyless. It was held back only because §L.6 had filed
 * `Can bombard/barrage` as an undecoded special bit; §Q.2 found it is not a bit at all but the
 * record's Bomber Size, and the rule followed. Additive and defaulted off, on the same terms.
 * The one section-9 rule still missing after this is Triggers (9.10), which has no key because the
 * mechanic does not exist; it is named in the profile's own gap list instead.
 *
 * 8 (2026-08-26) added [RuleKey.CRATERS] -- the first key in this enum that is NOT an Open General
 * rule. See its own documentation for why a house rule is allowed a key at all.
 *
 * 9 (2026-08-27) added [RuleKey.EXTENDED_NAVAL] -- OG 9.6, and by shipped content the largest gap
 * this project had: 238 of the 457 scenarios whose source is readable author it. One key for all
 * four of the manual's bullets, because OG itself has one switch for them. Additive and defaulted
 * off, on the same terms as every schema since 4 -- but note that two of its four bullets take
 * shots AWAY, which is exactly why it is behind a key rather than universal.
 *
 * 10 (2026-08-27) added [RuleKey.AIR_ZOC] -- OG 6.30's own word *"usually"*, and the shortest rule
 * in this enum. The option has been imported since section O and read by nothing; 79 of the 457
 * readable scenarios author it. Additive and defaulted off, on the same terms.
 *
 * 11 (2026-08-28) added [RuleKey.NAVAL_CRITICAL_HITS] -- `critical_hit`, a complete OG combat
 * formula that **`eqp-lxf` has been setting to 2 all along** and nothing read
 * (`docs/og-fidelity-plan.md` §AA.6). It is the first key in this enum found by reading an efile
 * key nobody had looked up rather than by working through the manual, and it is behind a key rather
 * than universal for one reason: it SINKS ships outright, so honouring LXF's own value silently
 * would rewrite every naval battle in the campaigns built on it. Additive and defaulted off.
 *
 * 12 (2026-08-28) added [RuleKey.DEPOT_SUPPLY], [RuleKey.RAIL_TRANSPORT] and
 * [RuleKey.CARRIER_DEPLOY] -- the three mechanics §AA built with no key at all, because each was
 * already gated by content that no shipped scenario carries. **That was the wrong reason to skip a
 * key**: the owner's rule is that anything the port can do belongs in the Rules window so a player
 * can choose it, not only where the shipped content happens to ask. All three are additive and
 * defaulted off, and each keeps its content gate on top -- turning one on cannot invent a Depot,
 * a rail pool or a carrier that the scenario does not have.
 *
 * 14 (2026-08-30) added [RuleKey.GREEN_REPLACEMENTS] and [RuleKey.CARRIER_HANGARS] -- the
 * largest and the last of the four mechanics §Y.3 had left unbuilt.
 * Like the schema-12 three it keeps a content gate on top -- the efile's own `green` key -- and
 * unlike them four shipped efiles set it.
 *
 * 13 (2026-08-29) added the former `trigger_hexes` key, OG 9.10.
 *
 * 15 (2026-08-31) retired that key. Trigger hexes are authored scenario content, like escape hexes
 * and MSU designations, and now always execute when present. Stored schema-13/14 profiles and saves
 * may still contain the retired stable key; readers ignore it rather than misclassifying it as an
 * unknown future rule. Multiplayer requires schema 15 so an older client that can suppress the
 * same trigger cannot join while claiming equivalent rules.
 *
 * 16 (2026-09-04) retired `stalin_regime` on the same terms, and for the reason a player gave when
 * they found it here: *"why is this in the ruleset? it belongs in settings"*. It always did -- the
 * key never had a value of its own, it was seeded from the **STALIN REGIME** checkbox at launch and
 * then frozen, which is what made ticking that box mid-battle look broken and needed a whole
 * explanatory note beside it to excuse. A cheat toggle is not a rule of the game, so it is the
 * setting alone again ([org.osada.model.usesStalinRegime]) and takes effect the moment it is
 * ticked, exactly as the units-and-prestige synchronisation it already calls always allowed.
 * Stored schema-<=15 profiles and saves may still carry the key; readers ignore it.
 */
const val RULESET_SCHEMA_VERSION = 16

/** Serialized keys understood historically but no longer configurable or gameplay-relevant. */
internal val RETIRED_RULE_KEYS: Set<String> = setOf("trigger_hexes", "stalin_regime")

/**
 * One configurable rule.
 *
 * [key] is the stable serialized name: it goes into saves, into the multiplayer hash and into the
 * profile store, and is never renamed for display reasons.
 *
 * [efileKey] is the `equip.cfg` name this rule reads, or `null` for a rule OSADA owns outright.
 *
 * [editorMin]/[editorMax] bound the EDITOR only. Resolution never narrows a content value with them
 * (§2): LXF ships `flak_range = 4`, and clamping Author's Vision would silently rewrite that
 * campaign's air war.
 */
enum class RuleKey(
    val key: String,
    val efileKey: String?,
    val editorMin: Int,
    val editorMax: Int,
) {
    /** OG's `g2a_intercept_mode` bitmask. 0 is NOT "off": concealed AA already intercepts at 0. */
    AA_INTERCEPT_MODE("aa_intercept_mode", "g2a_intercept_mode", 0, 3),

    /** Hexes an AA gun reaches, for interception and for flak support fire. */
    FLAK_RANGE("flak_range", "flak_range", 1, 4),

    /** Whether content attachments are allowed at all (`attach_on` plus the efile's slot table). */
    ATTACHMENTS("attachments", "attach_on", 0, 1),

    /** 1 = each efile's own per-terrain supply factors, 0 = the flat off-city rule OSADA already
     *  runs for the five efiles that ship no terrain data. */
    SUPPLY_MODEL("supply_model", null, 0, 1),

    // ---- weather (schema 2) ---------------------------------------------------------------
    // Four switches rather than one, because they are four separate branches with four separate
    // call sites -- collapsing them would hide from the player which of the four they turned off.
    // All default ON, so the shipped game is unchanged unless somebody deliberately changes them.

    /** Whether bad weather stops aircraft initiating attacks at all
     *  (`AttackEligibility.airGroundedByWeather`). */
    WEATHER_GROUNDS_AIRCRAFT("weather_grounds_aircraft", null, 0, 1),

    /** Whether bad weather halves the strength points an air<->ground exchange brings to bear
     *  (`WeatherCombatRules.firingStrength`). */
    WEATHER_HALVES_AIR_GROUND("weather_halves_air_ground", null, 0, 1),

    /** Whether rain and snow add +3 to defence (`WeatherCombatRules.defenseBonus`). */
    WEATHER_DEFENSE_BONUS("weather_defense_bonus", null, 0, 1),

    /** Whether bad weather halves spotting range (`WeatherCombatRules.spotRange`). */
    WEATHER_HALVES_SPOTTING("weather_halves_spotting", null, 0, 1),

    /**
     * Whether rain and snow turn the ground to mud and frozen at all
     * (`GroundConditionModel`). Three states rather than a switch, because the scenario author has
     * an opinion of their own here (`weatherchg`) and the honest default is to keep it:
     * 0 = never, 1 = as each scenario authorises, 2 = always.
     */
    GROUND_FOLLOWS_WEATHER("ground_follows_weather", null, 0, 2),

    /** How many continuous turns of one sky it takes to move the ground one step
     *  (`GroundConditionModel.TURNS_TO_CHANGE`). */
    GROUND_CHANGE_TURNS("ground_change_turns", null, 1, 6),

    // ---- replacements (schema 3) ----------------------------------------------------------

    /**
     * Whether ordinary replacements dilute a formation's experience
     * (`rules/ReplacementExperience`). 1 = the strength-weighted average, 0 = the pre-2026-08-18
     * behaviour, which preserved experience completely.
     *
     * OSADA owns this outright: no `equip.cfg` key expresses it, and the fidelity register records
     * nothing about what OG or PM do here, so it must not be presented as content-derived.
     */
    REPLACEMENT_EXPERIENCE("replacement_experience", null, 0, 1),

    // ---- Open General rules OSADA did not execute at all (schema 4) -------------------------
    // Each of these is a BRANCH, not a tuning number, and each defaults to today's behaviour, so
    // selecting nothing leaves all 502 shipped scenarios arithmetically untouched.

    /**
     * Whether artillery and air defence must fire BEFORE moving (OG) or may do either order
     * (OSADA today). 0 = `flexible`, 1 = `og_mechanized`.
     *
     * At 1 the restriction is waived for equipment carrying OG's `Mechanized` attribute
     * (`attr` bit 21) and for a `Mechanized Veteran` commander — the same two-source pattern
     * `Recon Skill` / `Reconnaissance Movement` already use for phased movement.
     * Call site: `rules/AttackEligibility.blockedByMoveThenFire`.
     */
    HEAVY_MOVE_FIRE("heavy_move_fire", null, 0, 1),

    /**
     * Whether snow doubles fuel spent per movement point, quoted verbatim from OG 6.23:
     * *"it spends one point of fuel for each movement point used, except in snow, when it uses two
     * points of fuel for each movement point."* 0 = `normal`, 1 = `double`.
     * Call site: `model/GameUnitActions.move`, previewed by `rules/MovementRules.getUnitMoveRange`.
     */
    SNOW_FUEL("snow_fuel", null, 0, 1),

    /**
     * Whether non-adjacent support fire lands at half strength (OG 6.24: *"units adjacent to the
     * attacked unit give full strength support fire, while others do it with halved strength"*).
     * 0 = `full`, 1 = `og_halved`. Call site: `rules/CombatResolver.calculateCombatResults`.
     */
    SUPPORT_FIRE_FALLOFF("support_fire_range_falloff", null, 0, 1),

    /**
     * Whether an empty unit is penalised as well as prohibited (OG 6.23: no ammo *"defends with
     * halved unsuppressed strength and halved initiative"*, no fuel *"cannot move and have its
     * initiative halved"*). 0 = `off`, 1 = `og`. OSADA already enforces both PROHIBITIONS; this
     * key adds only the halvings. Call site: `rules/AttackCalculation.applyDryUnitPenalties`.
     */
    DRY_UNIT_PENALTIES("dry_unit_penalties", null, 0, 1),

    /**
     * Whether land minefields exist at all — pre-placed by the scenario author, laid by units with
     * OG's `Drop mines` attribute and cleared by engineers (OG 9.9, `docs/og-fidelity-plan.md`
     * C.1). 0 = `off`, 1 = `og`.
     *
     * **Default off, and deliberately so.** Undetected mines damage a unit mid-move with no visible
     * cause, which is precisely the failure `DEFERRED.md` §1.1 forbids for AA interception
     * (*"Movement damage with no visible cause reads as a bug"*). Detected fields are drawn and warn
     * before a route commits; only undetected ones ambush. A player who has not asked for the
     * mechanic never meets either.
     */
    MINEFIELDS("minefields", null, 0, 1),

    // ---- the last five named gaps (schema 5) -----------------------------------------------
    // Same admission rule as schema 4: mechanic, typed call site, en/ru copy and serialization
    // first; the key last. All five default to the branch OSADA already took.

    /**
     * Whether aircraft are held to OG's operational fuel model (6.23). 0 = `forgiving`,
     * 1 = `og_operational`.
     *
     * Two rules, one key, because they are one model and neither is playable without the other:
     *
     *  - a sortie spends at least a third of the aircraft's full movement in fuel, however short
     *    the hop ([org.osada.rules.AirOperations.chargedMovePoints], read by
     *    `model/GameUnitActions.move` and previewed by `rules/MovementRules.getUnitMoveRange`);
     *  - an aircraft that ends its owner's turn with no fuel and no airfield or carrier within
     *    reach *"crashes and it is destroyed"* ([org.osada.rules.AirOperations.strandedAircraft],
     *    swept in `model/GameMap.endTurn`). OSADA alone merely stops it moving.
     *
     * Default `forgiving` by an owner decision recorded in `docs/og-fidelity-plan.md` B.3: the
     * crash rule is genuinely punitive and OSADA Default is deliberately the gentler game. That is
     * why this is a key and not a section-A correction.
     */
    AIR_FUEL("air_fuel", null, 0, 1),

    /**
     * Which inputs decide combat initiative (OG 6.10: *"determined by equipment, terrain and
     * experience of the units; it also it's adjusted by a random value, to simulate combat
     * uncertainty"*). 0 = `equipment_terrain`, 1 = `og_full`.
     *
     * OSADA reads equipment initiative, the attachment penalty and the terrain cap, and nothing
     * else. At 1 both of OG's further inputs join them: the crews' experience, one point per
     * completed bar, and a bounded random swing ([org.osada.rules.InitiativeModel]).
     *
     * **This is the only key in the catalogue that makes combat stochastic, and it is only safe
     * because of two things built alongside it.** The swing is drawn from
     * [org.osada.rules.GameRandomSource] — one seeded stream whose seed and cursor ride in the save
     * envelope, so both multiplayer peers roll identically even though multiplayer REPLAYS combat
     * rather than transmitting its result — and only on the COMMITTED path, so no preview, hover,
     * repaint or AI evaluation can move the cursor. With the key on the combat forecast stops being
     * an exact figure; with it off nothing draws and the forecast is exact, as it always was.
     *
     * **Default `og_full` since 2026-09-02, by owner decision.** It was `equipment_terrain` on the
     * `DEFERRED.md` §5.10 grounds -- experience re-tunes every imported campaign in the veteran's
     * favour, and both halves change what `First Strike` is worth, since that trait re-signs the
     * initiative difference rather than adding to it. Those consequences are real and unchanged;
     * what changed is which of them is preferred. The owner wants combat to carry OG's uncertainty,
     * so the forecast is now an estimate by default and the swing lands when the attack is made.
     *
     * The determinism this costs is only the PLAYER-FACING kind. Multiplayer still replays combat
     * on both peers rather than transmitting it, because the swing is drawn from the shared seeded
     * stream and only on the committed exchange -- see [org.osada.rules.InitiativeModel] for the
     * whole contract. Setting this back to `equipment_terrain` in a profile restores the exact
     * forecast for anyone who wants it.
     *
     * Call site: `rules/AttackCalculation.applyInitiativeBonus`.
     */
    INITIATIVE_MODEL("initiative_model", null, 0, 1),

    /**
     * Whether a hex a side has spotted stays spotted until that side's turn ends. 0 = `live`,
     * 1 = `until_turn_end`.
     *
     * OSADA's fog is a per-side REFERENCE COUNT that moves with the unit, so a recon element that
     * spots a hex and drives on takes the visibility with it. OG remembers it for the turn. The
     * memory is a separate per-hex layer beside the counters rather than an adjustment to them
     * ([org.osada.model.Hex.spotMemory]) -- the add/remove symmetry of those counters is the whole
     * reason the fog is ever correct, and a turn-scoped rule must not be able to strand one.
     * Call site: `rules/SpottingModel`, read through `model/Hex.isSpotted`.
     */
    SPOTTING_MEMORY("spotting_memory", null, 0, 1),

    /**
     * Whether owned cities, ports and airfields spot their own hex and its neighbours with no unit
     * present. 0 = `off`, 1 = `on`.
     *
     * Kept apart from [SPOTTING_MEMORY] on the precedent the four `weather_*` keys already set: a
     * different branch with a different call site, and a player who turns one off should not
     * silently lose the other. Recomputed wholesale per turn rather than reference-counted, because
     * an installation has no unit to cancel its visibility when the hex changes hands.
     * Call site: `rules/SpottingModel.recomputeInstallations`.
     */
    INSTALLATION_SPOTTING("installation_spotting", null, 0, 1),

    /**
     * How far the once-a-round automatic resupply of an idle GROUND formation reaches.
     * 0 = `city_only` (OSADA today), 1 = `og_anywhere`.
     *
     * OG 6.23 resupplies *"ground units that do nothing in the turn"* and states no terrain
     * condition; OSADA additionally requires a `CITY` hex with no adjacent enemies. That extra
     * condition is a BALANCE decision rather than a defect -- relaxing it hands every idle
     * formation in the field a free full refit -- so `docs/og-fidelity-plan.md` A.3 item 2 required
     * that it be relaxed behind a key if at all, never universally. This is that key; the adjacent-
     * enemy condition is untouched by it, because a unit being shot at is idle in neither game.
     * Call site: `rules/SupplyRules.computeResupplyValue`.
     */
    GROUND_AUTO_SUPPLY("ground_auto_supply", null, 0, 1),

    // ---- Open General optional rules, manual section 9 (schema 6) --------------------------
    // Three separate keys rather than one "optional rules" switch, on the precedent the four
    // weather keys set: they are three separate branches with three separate call sites, and OG
    // itself lists them as three independently selectable scenario options.

    /**
     * OG 9.4: artillery carrying `Counter Battery` answers enemy artillery that fires on a
     * friendly unit, once per turn. 0 = off (OSADA today), 1 = `og`.
     *
     * 818 shipped records carry the ability, and it had no call site at all before schema 6 --
     * `docs/og-fidelity-plan.md` section C mis-filed it inside the extended NAVAL set, which is a
     * different optional rule (9.6) and does not contain it. Call site:
     * `rules/CounterBatteryFire`, reached from `CombatApplication.resolveCombat`.
     */
    COUNTERBATTERY("counterbattery", null, 0, 1),

    /**
     * OG 9.5: closed terrain cuts line of sight, forests hide ground units from all but adjacent
     * observers, and forest/city hexes hide from aircraft beyond 2 hexes. 0 = off (OSADA today),
     * 1 = `og`.
     *
     * This is the key B.5 predicted (*"Extended LOS, if ever built, is a third key and not a value
     * of either"*) -- a third spotting key beside [SPOTTING_MEMORY] and [INSTALLATION_SPOTTING],
     * never a value of them. It also gives the two decoded-but-unread equipment bits
     * `Cut LOS` and `Allow LOF` their first reader. Call site: `rules/ExtendedLos`, read by
     * `SpottingModel` and by `AttackEligibility`'s line-of-fire test.
     */
    EXTENDED_LOS("extended_los", null, 0, 1),

    /**
     * OG 9.3: sappers build bridges, fortifications, airfields, ports and rail stations for
     * prestige and over several turns, and `Can Blow` units demolish bridges. 0 = off (OSADA
     * today), 1 = `og`.
     *
     * The strongest of the three on the argument section C.1 used to promote minefields: this is
     * **authored content OSADA discards**, not a hypothetical. `eqp-lxf`'s own `equip.cfg` ships
     * `build_cost=12,48,60,36,24`, `build_turn=2,3,3,3,2` and `repair_turn=1,2,2,2,1,1`, and LXF
     * backs four deployed campaigns. 1,298 shipped records carry `Build/Repair` and 5,047 carry
     * `Can Blow`. Call site: `rules/Engineering`.
     */
    BUILD_AND_REPAIR("build_and_repair", null, 0, 1),

    /**
     * OG 9.2: a unit with Bomber Size above zero shells a hex it cannot see. 0 = off (OSADA
     * today), 1 = `og`. **Schema 7**, and the fourth of section 9's optional rules to be built.
     *
     * Held back from schema 6 for one reason only, which no longer holds: §L.6 filed Barrage as
     * blocked because the `Can bombard/barrage` ability was *"not among the decoded 52"* special
     * bits. It is not a bit at all — it is the record's **Bomber Size** (`EquipmentData.bombsize`,
     * imported 2026-08-26), which is exactly what OG's own `tips1.txt` tells the player to look for
     * as the `'='` mark. 6,872 merged records carry it.
     *
     * **Authored content, twice over**: the scenario must also allow it, and 356 of the 457 shipped
     * scenarios whose source is readable do (`Scenario.barrageAllowed`, imported with the rest of
     * the option bitfield). Call site: `rules/Barrage`.
     */
    BARRAGE("barrage", null, 0, 1),

    /**
     * OG's `critical_hit`: a naval shot that sinks its target outright. **Schema 11.**
     *
     * > *"0 to disable, 1.. factor N in formula. Chance for critical hit.*
     * > *C(firing) = ( NA(Firing) * (1+bars(Firing)) * SP(Firing) * N - D(Fired) * (1+Bars(Fired)) *
     * > SP(Fired) * N ) / 30*
     * > *NA(Firing) is naval attack of unit firing. SP(Firing/Fired) is unit strength at start of
     * > combat. D(Fired) is GD or AD depending unit firing is Air/Gnd. Submarines always add 10%
     * > when firing (either attacking or defending). If C(firing) > 75 then C(firing)=75. If
     * > C(Firing) < Dice(1,100) then critical hit, fired unit is sunk"*
     * > — `EFILE_NOKORP/equip.cfg` and `OPENTXT_SAMPLE/equip.cfg`, identically
     *
     * **`eqp-lxf` sets `critical_hit = 2`.** It is the efile behind more shipped campaigns than any
     * other with an `equip.cfg`, and this formula has never run. That is why the key exists: the
     * mechanic is the author's, the factor is the efile's, and only the decision to let it loose on
     * an existing campaign is OSADA's.
     *
     * Off by default, which is a description of the shipped game rather than a judgement. A player
     * who wants it turns it on in a custom ruleset, and the efile's own factor then applies. Call
     * site: `rules/CriticalHit`.
     *
     * **`efileKey` is deliberately null**, on [AIR_ZOC]'s precedent: this switch does not carry the
     * value, it says *"honour the value the efile already wrote"*. `critical_hit` is a FACTOR
     * (`1..N`), not a flag, and folding a factor into a 0/1 key would either discard `eqp-lxf`'s
     * chosen 2 or hard-code it into a profile that is meant to work for every efile.
     */
    NAVAL_CRITICAL_HITS("naval_critical_hits", null, 0, 1),

    /**
     * OG's Depot supply — `supply_ex`, and the `Supply Unit` equipment special that marks a mobile
     * one. **Schema 12.**
     *
     * A Depot resupplies ADJACENT friendly land and naval formations on terms the field does not
     * offer: the terrain supply factor does not apply, enemy ZOC does not reduce it, and neither
     * party is disqualified by having moved or fired. `rules/DepotSupply` builds all four
     * `supply_ex` modes, including the one-ammo-per-turn cost and depot-to-depot supply.
     *
     * **Two content gates survive this key.** No shipped efile sets `supply_ex` and no shipped
     * record carries `Supply Unit`, so switching it on changes nothing until content authors one —
     * which is exactly right: the key says "run the mechanic", not "invent a depot".
     */
    DEPOT_SUPPLY("depot_supply", null, 0, 1),

    /**
     * OG's railway transport, and with it the `No Need Station` equipment special. **Schema 12.**
     *
     * A ground formation standing on a boarding point may be railed along connected track to
     * another one, spending a point of its player's `railtrans` pool. A station is needed at both
     * ends unless the formation carries `No Need Station`, which 11,003 records do
     * (`rules/RailTransport`).
     *
     * **The content gate survives this key**: a player whose scenario has no rail pool sees no
     * chip, because there is no train to board. Turning it on cannot create one.
     *
     * **This is not full OG fidelity, and must not be described as such.** The pool SIZE is imported
     * exactly from the scenario binary (player record `+21`, confirmed against OpenSuite's own
     * reports). How long a slot stays occupied is an **OSADA compression**: OG entrains a unit in a
     * real train that drives the map over as many turns as the journey takes, and OSADA relocates
     * the formation atomically and holds the slot for the turn. `rules/RailTransport` and
     * `model/TransportPools` carry the reasoning and the quotes that would change it.
     */
    RAIL_TRANSPORT("rail_transport", null, 0, 1),

    /**
     * OG's `Carrier Deploy` — *"permits deployment on carriers and dirt airfields"*. **Schema 12.**
     *
     * An aircraft carrying the attribute may be placed onto a friendly carrier during deployment,
     * which it otherwise may not be because a ship at sea is never in a deploy zone
     * (`rules/CarrierDeploy`). Purely additive: no hex that was already a legal target stops being
     * one, and 322 of 56,970 records carry the bit.
     */
    CARRIER_DEPLOY("carrier_deploy", null, 0, 1),

    /**
     * OG's **green replacements** — a second, cheap replacement action that costs veterancy.
     * **Schema 14.**
     *
     * `og-fidelity-plan.md` §Y.3 called this the largest unbuilt OG mechanic left. It adds an
     * action rather than changing one: the existing Reinforce is untouched, and
     * `rules/GreenReplacements` prices and dilutes the new one from the efile's own
     * `green_cost` / `green_exp` / `green_defexp` / `remove_leader`.
     *
     * **The content gate survives this key**: four of the eighteen shipped `equip.cfg` files set
     * `green = 1`, and turning the key on for an efile that does not cannot invent the action.
     * Additive and defaulted off — a cheaper way to rebuild a formation is a real change to a
     * campaign's economy, and the player should choose it.
     */
    GREEN_REPLACEMENTS("green_replacements", null, 0, 1),

    /**
     * OG's **carrier hangars** — aircraft carried inside a ship rather than parked on it.
     * **Schema 14**, and the last of §Y.3's four unbuilt mechanics.
     *
     * A contained aircraft is off the map: unspottable, unshootable, and not occupying the hex's
     * air slot. `rules/CarrierHangars` reads capacity from `hangarCap` (916 shipped records carry
     * one) and its permissions from the efile's `ground_carrier` bits.
     *
     * **The content gate survives this key**: a ship whose record gives it no hangar cannot hold
     * anything, and an efile that leaves `ground_carrier` at 0 has no hangars at all. Additive and
     * defaulted off — containment changes how a carrier fights, and the player should choose it.
     */
    CARRIER_HANGARS("carrier_hangars", null, 0, 1),

    /**
     * OG 9.6: the four extended naval rules, as one switch. 0 = off (OSADA today), 1 = `og`.
     * **Schema 9**, and the fifth of section 9's optional rules to be built.
     *
     * > *"Ships return fire to artillery and forts. Ships can only attack submarines at range 1.
     * > Destroyers can escort naval transports against submarine attacks, just like fighters escort
     * > bombers. Submarines need direct LOF to attack."*
     *
     * **One key rather than four**, which `docs/og-fidelity-plan.md` section C called correctly
     * from the start: OG treats them as one coherent optional set and the scenario bitfield carries
     * a single `extnaval` bit, so four keys would invent a granularity no author can express.
     *
     * **The largest single gap by content this project had.** 238 of the 457 scenarios whose source
     * is readable author it (`Scenario.extendedNaval`, imported with the rest of the option
     * bitfield in section O) and until schema 9 no rule read the switch at all.
     *
     * Off by default for a stronger reason than the other section-9 keys: two of its four bullets
     * are RESTRICTIONS. 4,129 of the 4,990 shipped ship records have a gun range above one and
     * would lose every long shot at a submarine. Call site: `rules/ExtendedNaval`.
     */
    EXTENDED_NAVAL("extended_naval", null, 0, 1),

    /**
     * OG 6.30's air exemption, made conditional: whether aircraft project a zone of control.
     * 0 = off (OSADA today, and OG's own default), 1 = `og_scenario`. **Schema 10.**
     *
     * > *"The six hexes around a unit are its zone of control... Air units USUALLY don't have a
     * > zone of control."*
     *
     * That one word is a scenario option, authored by 79 of the 457 scenarios whose source is
     * readable. It is the sharpest of the systems the fidelity profile named to the player as
     * missing, because unlike the others the switch WAS imported and simply had no reader.
     *
     * Not one of section 9's optional rules -- it is a section 6 core rule with an authored
     * exception -- so it does not change the count of those. Call site:
     * `UnitCapabilities.projectsZoneOfControl`, through `rules/AirZoneOfControl`.
     */
    AIR_ZOC("air_zoc", null, 0, 1),

    /**
     * **Shell craters — an OSADA rule, not an Open General one.** 0 = off, 1 = on. Schema 8.
     *
     * A barrage that lands on open ground (clear, snow or sand) with nothing to wreck digs craters
     * instead of doing nothing. They cost a movement point to enter, and they give the formation
     * standing in them a FLOOR of one entrenchment level — cover you did not have to dig.
     *
     * **Off in Open General Fidelity, deliberately and permanently.** No OG source grants a crater
     * cover of any kind; OG's own barrage takes entrenchment AWAY (`Barrage.ENTRENCHMENT_DAMAGE`).
     * A profile whose whole claim is "these are OG's rules" must not quietly carry one of ours —
     * `docs/design/ruleset-profiles.md` §2, and §0.2's rule against unverifiable fidelity claims.
     * It is reachable from OSADA Default and from custom rulesets, which is where an OSADA
     * invention belongs.
     *
     * **A floor rather than a bonus, and that is the anti-farm design.** If craters ADDED
     * entrenchment, shelling your own front line would be free fortification: artillery in the rear
     * preparing positions for the infantry, at the cost of ammunition nobody else was going to
     * spend. As a floor it can never beat digging in, so it is worth doing where you have no time
     * to dig and worthless where you have. Call site: `rules/Craters`.
     */
    CRATERS("craters", null, 0, 1),

    /**
     * Whether OG's per-record ability TOGGLES decide phased movement and overrun, or the unit's
     * class alone does. 0 = `class` (OSADA today), 1 = `og_record`.
     *
     * **This key exists because §I closed the wrong half of the problem, and a 2026-08-25 review
     * caught it.** That pass changed `UnitCapabilities.hasPhasedMovement` and `canOverrun` to
     * `classDefault xor bit` and called the RCN/OVR badge approximations closed. They were not:
     * both functions were read only by the BADGE and the equipment card. The rules themselves
     * kept testing the class directly (`GameUnitActions`' phased-movement branch,
     * `AttackCalculation.resolveOverrunAndExperienceGain`), so a record carrying either bit wore
     * a badge stating a rule the engine would not apply -- the exact failure §J.4 deleted
     * `isHeadquarters` to prevent, in two more places.
     *
     * **And the fix could not be universal, which is why this is a key rather than a one-line
     * correction.** Measured over the 56,970 shipped records: reading the toggles would hand
     * phased movement to **4,998 non-Recon records** and take overrun off **211 Tank records**.
     * That is not an approximation being made exact, it is the arithmetic of every one of the
     * 502 shipped scenarios changing at once -- the §5.10 hazard this whole catalogue exists to
     * keep out of the default profile.
     *
     * The badge follows the key rather than the other way round: with it off both helpers report
     * the class answer, so what a player is shown is what the engine will do in either profile.
     * Call sites: `UnitCapabilities.hasPhasedMovement` / `canOverrun`, and through them
     * `GameUnitActions.move` and `AttackCalculation.resolveOverrunAndExperienceGain`.
     */
    EQUIPMENT_TOGGLES("equipment_toggles", null, 0, 1),
    ;

    /** Editor-only bounds. Never applied to a value that came from content (§2). */
    fun clampForEditor(value: Int): Int = value.coerceIn(editorMin, editorMax)

    /** The rule this one is inert without, or `null` when it stands on its own. See [RULE_REQUIRES]. */
    val requires: RuleKey? get() = RULE_REQUIRES[this]

    companion object {
        fun byKey(key: String): RuleKey? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Rules that cannot do anything while another rule is off, and the rule each one needs.
 *
 * Only [RuleKey.CRATERS] so far: a crater is dug by `Barrage`'s own miss path and by nothing else
 * (`rules/Craters.dig` has exactly one caller), so with barrage fire off the switch is a promise
 * the engine will never keep -- reported by a player who found it offered as a live choice beside
 * a barrage rule that was switched off.
 *
 * This is a PRESENTATION fact, deliberately not an availability reduction: the effective value is
 * still what the profile asked for, so it stays in the hash, a save reproduces it exactly, and
 * turning barrage back on needs no second visit to this window. The Rules window greys the row and
 * the editor disables the control ([org.osada.ui.RulesText.inert]).
 */
internal val RULE_REQUIRES: Map<RuleKey, RuleKey> = mapOf(RuleKey.CRATERS to RuleKey.BARRAGE)

/**
 * Where an effective value came from. Lets the window explain a rule truthfully without teaching
 * the UI to read `equip.cfg` itself (§3).
 */
enum class RuleProvenance {
    /** The content's own `equip.cfg` names this key explicitly. */
    EFILE_EXPLICIT,

    /** The content has no opinion, so the documented call-site default stands. */
    EFILE_DEFAULT,

    /** OSADA's own documented baseline. */
    OSADA_DEFAULT,

    /** Author's Vision defers to the SCENARIO's own authored switch for this rule, so the master
     *  key is on and the scenario decides. Distinct from [EFILE_EXPLICIT] because the opinion is
     *  the scenario's rather than the efile's, and from [OSADA_DEFAULT] because OSADA is not the
     *  one choosing (`RulesetResolver.SCENARIO_AUTHORED`). */
    SCENARIO_AUTHORED,

    /** A custom profile asked for this value. */
    CUSTOM_OVERRIDE,

    /** The mechanic does not exist for this content, so the request could not be honoured. */
    CONTENT_UNAVAILABLE,
}

enum class RuleAvailability {
    AVAILABLE,

    /** "On" cannot invent what the content never defined -- KAISER has no attachment slots at all. */
    CONTENT_UNAVAILABLE,
}

/**
 * One rule after resolution. [requested] is what the profile asked for and [effective] is what the
 * engine will run; they differ only when the content cannot honour the request.
 */
data class ResolvedRule(
    val requested: Int,
    val effective: Int,
    val provenance: RuleProvenance,
    val availability: RuleAvailability = RuleAvailability.AVAILABLE,
) {
    val unavailable: Boolean get() = availability == RuleAvailability.CONTENT_UNAVAILABLE
}

/**
 * A reusable browser-local overlay. Omitted keys keep following the selected content, which is what
 * makes one profile meaningful across campaigns built on different efiles.
 *
 * [unknownKeys] preserves keys this build does not understand instead of dropping them (§3):
 * discarding them and then hashing as though both peers agreed would let two clients execute
 * different games. A profile carrying any is unsupported here and is shown disabled.
 */
data class RulesetProfile(
    val id: String,
    val name: String,
    val schemaVersion: Int = RULESET_SCHEMA_VERSION,
    val overrides: Map<RuleKey, Int> = emptyMap(),
    val source: RulesetSource = RulesetSource.CUSTOM,
    val unknownKeys: Set<String> = emptySet(),
) {
    /** A profile from a newer schema, or carrying keys this build cannot execute, is visible but
     *  cannot be selected (§3). */
    val supported: Boolean get() = schemaVersion <= RULESET_SCHEMA_VERSION && unknownKeys.isEmpty()

    companion object {
        const val AUTHORS_VISION_ID = "authors-vision"
        const val OSADA_DEFAULT_ID = "osada-default"
    }
}

enum class RulesetSource {
    /** The effective configuration loaded for the selected content. */
    AUTHORS_VISION,

    /** OSADA's single documented baseline, identical for every content. */
    OSADA_DEFAULT,

    /** A named profile the player saved. */
    CUSTOM,
}

/**
 * The complete, immutable resolution the engine executes. Rule code reads only [effective]; nothing
 * downstream ever re-derives a value from a profile.
 */
data class ResolvedRuleset(
    val id: String,
    val name: String,
    val source: RulesetSource,
    val schemaVersion: Int,
    val rules: Map<RuleKey, ResolvedRule>,
    val deterministicHash: String,
) {
    fun effective(rule: RuleKey): Int = rules[rule]?.effective ?: RulesetDefaults.OSADA.getValue(rule)

    fun flag(rule: RuleKey): Boolean = effective(rule) != 0

    fun rule(rule: RuleKey): ResolvedRule =
        rules[rule] ?: ResolvedRule(
            requested = RulesetDefaults.OSADA.getValue(rule),
            effective = RulesetDefaults.OSADA.getValue(rule),
            provenance = RuleProvenance.OSADA_DEFAULT,
        )

    /** Rules the content cannot honour, for the window's warnings (§7). */
    fun unavailable(): List<RuleKey> = RuleKey.entries.filter { rules[it]?.unavailable == true }
}

/**
 * OSADA's documented baseline, and simultaneously each rule's call-site default -- the value the
 * engine uses when content says nothing. Keeping one table means the window cannot claim a default
 * the engine does not actually apply.
 */
object RulesetDefaults {
    val OSADA: Map<RuleKey, Int> =
        mapOf(
            // Mode 0: concealed AA intercepts a plane flying through or finishing in range; spotted
            // AA never intercepts. `AAInterception`'s own documented default.
            RuleKey.AA_INTERCEPT_MODE to 0,
            // equip.cfg's own comment: "Default all flak-type actions are limited to range 1."
            RuleKey.FLAK_RANGE to 1,
            // `attach_on` absent really does mean off (`EfileConfig`'s trap-4 note).
            RuleKey.ATTACHMENTS to 0,
            // TerrainEx already prefers the efile's own factors and falls back per terrain id to
            // PM's flat formula, so "efile factors" is what OSADA runs today.
            RuleKey.SUPPLY_MODEL to 1,
            // Every weather rule ships on: these are Open General's own rules
            // (`tools/og-import/DEFERRED.md` §7.45), and each is already a no-op in Fair weather.
            RuleKey.WEATHER_GROUNDS_AIRCRAFT to 1,
            RuleKey.WEATHER_HALVES_AIR_GROUND to 1,
            RuleKey.WEATHER_DEFENSE_BONUS to 1,
            RuleKey.WEATHER_HALVES_SPOTTING to 1,
            // Ground: follow whatever each scenario authorises, and OG's own "several turns".
            RuleKey.GROUND_FOLLOWS_WEATHER to 1,
            RuleKey.GROUND_CHANGE_TURNS to 3,
            // On by owner decision (2026-08-18). This is the one default in this table that
            // deliberately CHANGES shipped behaviour rather than describing it: replacements
            // preserved experience completely before schema 3.
            RuleKey.REPLACEMENT_EXPERIENCE to 1,
            // The five schema-4 keys all default to what OSADA already did, so the shipped game is
            // unchanged until a profile asks otherwise. `minefields` additionally must stay off in
            // OSADA Default as a product decision, not merely a conservative one -- see its doc.
            RuleKey.HEAVY_MOVE_FIRE to 0,
            RuleKey.SNOW_FUEL to 0,
            RuleKey.SUPPORT_FIRE_FALLOFF to 0,
            RuleKey.DRY_UNIT_PENALTIES to 0,
            RuleKey.MINEFIELDS to 0,
            // The five schema-5 keys, on the same terms: each is what OSADA already did --
            // except `initiative_model`, which is the one entry in this table that is NOT a
            // description of the old behaviour. It is on by owner decision (2026-09-02): OG's
            // uncertainty is wanted in the default game, so combat is stochastic unless a profile
            // says otherwise. Both built-in profiles read this value, since the rule has no efile
            // key and no scenario switch for Author's Vision to defer to.
            RuleKey.AIR_FUEL to 0,
            RuleKey.INITIATIVE_MODEL to 1,
            RuleKey.SPOTTING_MEMORY to 0,
            RuleKey.INSTALLATION_SPOTTING to 0,
            RuleKey.GROUND_AUTO_SUPPLY to 0,
            // The three schema-6 keys, on the same terms again: OSADA runs none of these three
            // optional rules today, so off is a description of the shipped game, not a choice.
            RuleKey.COUNTERBATTERY to 0,
            RuleKey.EXTENDED_LOS to 0,
            RuleKey.BUILD_AND_REPAIR to 0,
            // Schema 7. Off for the same reason the schema-6 three are: OSADA does not shell
            // unseen hexes today, so off is a description of the shipped game.
            RuleKey.BARRAGE to 0,
            // Schema 12. Off is a description of the game before 2026-08-28: OSADA had no Depot
            // supply, no railway and no carrier deployment. Each keeps its own content gate on top,
            // so ON is a permission rather than an injection.
            RuleKey.DEPOT_SUPPLY to 0,
            RuleKey.RAIL_TRANSPORT to 0,
            RuleKey.CARRIER_DEPLOY to 0,
            RuleKey.GREEN_REPLACEMENTS to 0,
            RuleKey.CARRIER_HANGARS to 0,
            // Schema 11. `eqp-lxf` sets `critical_hit = 2`, so this is the one key whose OFF is a
            // divergence from a shipped efile's own instruction -- taken deliberately, because the
            // rule sinks ships outright and no existing campaign was balanced with it running.
            RuleKey.NAVAL_CRITICAL_HITS to 0,
            // OSADA's own rule (schema 8), off until a player asks for it.
            RuleKey.CRATERS to 0,
            // Schema 9. Off on the section-9 terms, and additionally because two of its four
            // bullets refuse shots the shipped scenarios currently allow.
            RuleKey.EXTENDED_NAVAL to 0,
            // Schema 10. Off is OG's OWN default here, not merely OSADA's: §6.30 says air units
            // "usually" have no zone of control, and the option is the exception.
            RuleKey.AIR_ZOC to 0,
            // Class defaults, which is what every shipped scenario has always run on.
            RuleKey.EQUIPMENT_TOGGLES to 0,
        )
}
