package org.osada.model

import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.LeaderOnUpgrade
import org.osada.rules.isTransportable

@JsExport
@JsName("Unit")
class GameUnit(
    var eqid: Int,
) {
    companion object {
        internal const val STALIN_REGIME_MULTIPLIER = 10

        /** OG's own full strength, and what a formation with no authored Basic Strength gets. */
        const val DEFAULT_BASIC_STRENGTH = 10
    }

    var id: Int = -1
    var owner: Int = -1
    var flag: Int = owner

    var isCore: Boolean = false
    var isDeployed: Boolean = false
    var isSurprised: Boolean = false
    var isMounted: Boolean = false
    var hasOverstrength: Boolean = false
    var hasResupplied: Boolean = false
    var hasFired: Boolean = false
    var hasMoved: Boolean = false
    var strength: Int = 10
    var facing: Int = 2
    var destroyed: Boolean = false

    /**
     * Set alongside [destroyed] when the unit was lost by SURRENDER (a forced retreat with no legal
     * destination) rather than by damage, so the two stay distinguishable in the log and dossier
     * even though both remove the unit.
     *
     * Deliberately NOT serialised: a surrendered unit is swept by `updateUnitList()` in the same
     * combat step it is set, so it never survives to a save.
     */
    var surrendered: Boolean = false
    var transport: Transport? = null
    var player: Player? = null
    var carrier: Int = 0
    var moveLeft: Int = Equipment.equipment[this.eqid]?.movpoints ?: 0
    var ammo: Int = Equipment.equipment[this.eqid]?.ammo ?: 0
    var fuel: Int = Equipment.equipment[this.eqid]?.fuel ?: 0
    var hasAnimation: Boolean = false
    var entrenchment: Int = 0
    var entrenchTicks: Int = 0
    var experience: Int = 0
    var hits: Int = 0
    var leader: Int = -1

    /**
     * OG's **class-attribute override** -- the Suite's *"According unit's class"* leader selector,
     * `.xscn` unit `@37`, deployed as `ldrclasstrait`.
     *
     * OG gives a led formation TWO attributes and they are not interchangeable: `@36` is picked per
     * formation and lands in [leader], while `@37` is the one an author fixes BY CLASS -- across the
     * corpus it takes essentially one value per equipment class (weighted 92%, and 100% across all
     * 79 artillery records) against `@36`'s 10-22 values per class.
     *
     * OSADA derives that second attribute rather than storing it: [org.osada.model.Leaders.getUnitClassLeader]
     * returns the first entry of the unit's class list. This field is the author's override of that
     * derivation, and **-1 means "no override", which is OG's own default** (`@37 == 0`, *"According
     * unit's class"*) -- so every formation without one keeps the derived attribute exactly.
     *
     * Only meaningful while [leader] is set: OG's second attribute belongs to a leader, and
     * `getUnitClassLeader` already refuses a formation with none.
     */
    var leaderClassTrait: Int = -1

    /**
     * The scenario author's AI ORDERS for this formation -- OpenSuite's "Unit settings" panel,
     * cracked 2026-09-01 by a 27-save controlled series on `BN9S00`.
     *
     * They constrain `org/osada/ai`'s planner and nothing else. **A human commanding this side keeps
     * every button**: an anchored formation under human control moves normally, and none of these
     * fields reaches the combat resolver, the retreat rules or the supply pass. `rules/AiOrders` is
     * the single reader and its KDoc carries the decision.
     *
     * * [aiAnchored] -- `@45` bit 5, *"unit is fixed in place"*. 19,259 records.
     * * [aiHoldUntilTurn] -- `@56`, a turn number; 0 is "not held". 13,345 records.
     * * [aiFearless] -- `@50` bit 0. **Not immunity to rout**: OG's own description is that the AI
     *   discards this formation's expected OWN casualties when valuing an attack. 22,878 records.
     * * [aiObjectiveCol]/[aiObjectiveRow] -- `@58`/`@59`, the hex this formation is ordered to take;
     *   -1 for none. 13,067 records.
     * * [aiFreeObjectiveDistance] -- `@64`, *"free OH when closer than N"*. 4,116 records.
     * * [aiObjectiveFromOrdinal] -- `@62`, the per-player [aiOrdinal] of the formation whose
     *   objective this one inherits; 0 for none. 799 records, with [aiFollowsObjectiveUnit]
     *   (`@50` bit 5) as its companion on 534.
     * * [aiOrdinal] -- `@46`, the per-player ordinal the two fields above address a formation by.
     *
     * Two decoded fields are deliberately absent. **AI stance** (`@57`) is blocked by
     * `docs/og-fidelity-plan.md` §0, which forbids shipping stances before the P3 benchmark exists;
     * **avoid auto hold** (`@50` bit 4) suppresses an automatic hold behaviour OSADA's planner does
     * not have, so importing it would be a field with nothing to switch off.
     */
    var aiAnchored: Boolean = false
    var aiHoldUntilTurn: Int = 0
    var aiFearless: Boolean = false
    var aiObjectiveCol: Int = -1
    var aiObjectiveRow: Int = -1
    var aiFreeObjectiveDistance: Int = 0
    var aiObjectiveFromOrdinal: Int = 0
    var aiFollowsObjectiveUnit: Boolean = false
    var aiOrdinal: Int = 0

    /**
     * The attachments the SCENARIO AUTHOR fitted to this formation -- `.xscn` unit `@40` and `@41`,
     * each an `attach_N` id from **that efile's** `equip.cfg`. 12,555 records carry one.
     *
     * Distinct from the purchased attachments in `CoreFormation.attachmentIds`, and stored here
     * rather than there for a reason `rules/Attachments` sets out in full: an auxiliary or
     * scenario-only formation has no `CoreFormation` at all, so an authored slot copied into one
     * would have nowhere to live for most of the units that carry it.
     *
     * **Never priced.** An attachment the author fitted is part of the formation they wrote; it
     * costs the player nothing and is not refundable.
     */
    var authoredAttachmentIds: List<Int> = emptyList()

    /**
     * OG's *"disable attachments"* (`.xscn` unit `@50` bit 3) -- the author forbidding this
     * formation any attachment at all.
     *
     * **2 records corpus-wide.** Imported because it is one condition on a rule that already exists,
     * and recorded as the curiosity it is so nobody mistakes it for live content.
     */
    var attachmentsForbidden: Boolean = false
    var tempSpotted: Boolean = false
    var nodossier: Boolean = false

    /**
     * Whether the SCENARIO DESIGNER designated this placed formation an Open General **Depot**.
     *
     * OG has two ways to make a Depot and this is the older one — the other is the May 2024
     * `Supply Unit` equipment special, which [org.osada.rules.DepotSupply.isDepot] already read.
     * OpenSuite spells this one *"Can supply units on ZOC"* in a unit's right-click Misc. panel;
     * `DEFERRED.md` §2.10 quotes *"enemy ZOC does not reduce it"* as one of the three clauses that
     * define what a Depot does.
     *
     * **Recovered 2026-08-29** from `.xscn` unit record `@50` bit 1, by controlled OpenSuite diff.
     * It defeated `depot_flag_hunt.py`'s 328,638-record correlation sweep because that sweep
     * looked for a bit EXCLUSIVE to the depot-capable classes, and this one sits on five classes —
     * 21.7% of class 7 down to 0.02% of class 17. Eight of the 502 deployed scenarios carry one
     * (the Hungarian Soviet Republic campaign), imported by `tools/og-import/add_depots.py`.
     *
     * Authored scenario data, so it is parsed and saved unconditionally; whether it does anything
     * is [org.osada.rules.DepotSupply]'s question, and that rule has its own two gates.
     */
    var isScenarioDepot: Boolean = false

    /**
     * OG's **Basic Strength** — *"the maximum strength of the unit when you assign it
     * replacements"* (`Manual_OSuite-Scenario.pdf` p.9), scenario unit record `@23`.
     *
     * Distinct from [strength], which is OG's CURRENT strength at `@24`. A formation authored
     * `10/5` is at full strength now and rebuilds only to 5 once it takes losses — which is how a
     * designer says *"this unit will not be made whole again"*.
     *
     * ### The option that decides which of the two you start on
     *
     * `opt_use_basic_strength`, OG's *"Use current/basic strength as defined"*, and the local
     * OpenSuite report spells out the half the manual leaves implicit:
     *
     * > *"Use current / basic strength as defined (**so no reset current to basic**)"*
     *
     * So the option ON keeps both as authored, and OFF resets `current := basic` at load. **OFF is
     * the harsher setting, not ON** — a 10/5 formation starts at 5 there. That inversion is why
     * this was held back on 2026-08-30 pending an answer: with the plain reading it looked as if
     * turning the option ON would cap most of an army below its own strength, and the measurement
     * (244,437 of 395,657 corpus units have basic below current) made that look catastrophic. It is
     * the unauthored case that moves, and it moves to what OG does.
     *
     * **Defaults to 10, so nothing without an authored value changes.** A bought formation, a
     * campaign core unit and every scenario that does not author `@23` all keep the old behaviour
     * exactly, because 10 is what [SupplyRules] hardcoded before this field existed.
     */
    var basicStrength: Int = DEFAULT_BASIC_STRENGTH

    /**
     * Whether the scenario designer marked this formation a **Must-Survive Unit** (OG manual
     * §3.7.1) — one of the units a side has to keep alive, or lose the scenario.
     *
     * > *"you must define any number of units as Must Survive Units (MSU) ... and type the number
     * > of the MSU that need to survive not to lose the scenario."*
     *
     * **Recovered 2026-08-30** from `.xscn` unit record `@43` bit 0, by controlled OpenSuite diff:
     * one placed BA-20 was ticked and exactly that bit moved on exactly that record.
     *
     * `@43` is one of the near-always-nonzero bytes `SCENARIO_FORMAT_NOTES.md` had written off as
     * *"OG's cached copy of the equipment record"*, which is exactly why correlation was never
     * going to find it — like [isScenarioDepot] it shares a byte with something common, so no bit
     * is ever exclusive to a unit class. 2,996 records across 385 scenarios, on every class.
     *
     * Read by [org.osada.rules.ExtendedVictory]; the per-side quota of how many must live is
     * [org.osada.scenario.Scenario.mustSurvivePerSide].
     */
    var mustSurvive: Boolean = false

    /**
     * Formations currently INSIDE this unit's container (`rules/CarrierHangars`, OG's
     * `ground_carrier` / `hangarCap`).
     *
     * **Not only aircraft, and not only carriers.** `hangarCap` is on 916 shipped records across
     * **13 unit classes** — battleships and cruisers, but also 57 tanks (the IFVs: `M2 Bradley`
     * and its marks), 54 of class 6, and eight infantry records. OG's own key is called
     * `ground_carrier` and its bit 8 reads *"allow **land units** to enter naval-class carriers out
     * of port"*, so a landing ship with a battalion below decks and a bunker with a garrison are
     * the mechanic, not an extension of it.
     *
     * A contained formation is off the map entirely — not in `GameMap.units`, not on a hex, not
     * spottable and not occupying either of the hex's unit slots. That containment is the whole
     * difference between a container and parking an aircraft on top of a ship, which is what
     * `Hex.airunit` has always done and still does.
     *
     * Empty for every unit with no capacity, and for every container under a ruleset that leaves
     * `carrier_hangars` off — so it serializes to nothing in an ordinary game.
     */
    @JsExport.Ignore
    var hangar: MutableList<GameUnit> = mutableListOf()

    /**
     * The container this formation is riding inside, or null when it is on the map.
     *
     * The back half of [hangar], maintained by `rules/CarrierHangars` and restored by
     * `GameStateDeserializer` from the hangar it was read out of. **Derived, never serialized** —
     * the hangar list is the stored form, and a second stored copy could disagree with it.
     *
     * It exists because OG's `ground_carrier` bit 2 lets a contained formation support the battle
     * from where its container stands: answering that needs the passenger to name its container,
     * and scanning every unit's hangar for every support test would be quadratic.
     */
    @JsExport.Ignore
    var containedIn: GameUnit? = null

    /**
     * The turn this formation last boarded a container, or -1.
     *
     * OG does not let a passenger leave again on the turn it came aboard; without this a container
     * would be a free within-turn teleport. Serialized, because a save taken between boarding and
     * the next turn must not hand the move back.
     */
    var landedTurn: Int = -1

    /**
     * Whether mutable resource pools have been converted to Stalin Regime scale. Persisting this
     * prevents a loaded unit's remaining movement, ammo and fuel from being multiplied twice.
     */
    var stalinRegimeBoosted: Boolean = false
    private val boostedDataCache: MutableMap<EquipmentData, EquipmentData> = mutableMapOf()

    /** Set when this AA unit has already intercepted a moving aircraft this turn under
     *  `g2a_intercept_mode` bit 1 ("disables air-defense after interception") -- checked by
     *  [org.osada.rules.CombatResolver]'s support-fire eligibility so the same unit cannot both
     *  intercept and then air-defend a friendly unit in the same turn. Reset every turn in
     *  [unitEndTurn]. Deliberately not serialised -- it never survives past the turn it is set. */
    var hasInterceptedThisTurn: Boolean = false

    /**
     * Whether this formation has already fired in SUPPORT of a neighbour this turn.
     *
     * Read only for a record carrying OG's `SingleFireSup.` (`attrEx` bit 15) — see
     * [org.osada.rules.UnitCapabilities.supportsOnlyOncePerTurn]. Everything else may support as
     * often as it is called on, which is what OSADA has always done and what OG's §6.24 describes
     * for an ordinary battery.
     *
     * **Turn-scoped and NOT serialized**, exactly like [hasInterceptedThisTurn] beside it: both are
     * cleared by `GameUnit.unitEndTurn`, and a save taken mid-turn restores a formation that has
     * not yet spent its support. That is a known and deliberate limitation of both flags rather
     * than an oversight of this one.
     */
    var hasSupportedThisTurn: Boolean = false

    /**
     * Whether this formation has been SABOTAGED — OG's `Saboteur` (`rules/Sabotage`, 2026-08-27).
     *
     * OG's penalties: −2 attack and −2 defence, the next move and the next attack lost, and no
     * reinforcing, resupplying or evading. Two further clauses — it cannot act as a Depot or a
     * Healer — are inert here because OSADA has neither, and they are recorded rather than
     * approximated (`DEFERRED.md` §2.10 is the Depot's own blocker).
     *
     * Unlike [hasInterceptedThisTurn] and [hasSupportedThisTurn] beside it this is NOT turn-scoped
     * and IS serialized: OG describes sabotage as a state a unit is put into, not a flag that
     * clears at the end of the round, and losing it to a reload would hand the sabotaged unit its
     * turn back.
     */
    var sabotaged: Boolean = false

    /**
     * How many attacks this formation has RESOLVED AS THE ATTACKER since its last [unitEndTurn].
     *
     * `hasFired` alone cannot express OG's `Devastating Fire` ("the unit may fire twice in a turn"),
     * because it is a one-bit answer to a question that now has three states: not yet fired, fired
     * once and allowed one more, spent. Ordinary formations still spend their attack on shot 1 --
     * [org.osada.model.fire] sets `hasFired` unless the count says another shot is owed -- so the
     * counter changes nothing for a unit without the trait.
     *
     * Serialized only when non-zero, so a save of a formation that has not attacked keeps its exact
     * previous layout.
     */
    var shotsThisTurn: Int = 0

    /**
     * Half an ammunition point already paid for but not yet spent, under OG's `Fire Discipline`
     * ("the unit will expend only one-half of an ammunition point each time it attacks").
     *
     * Ammunition is an integer everywhere else in the engine and must stay one -- the equipment
     * card, the resupply arithmetic and the save format all assume it. So the half-point is carried
     * here instead: the first attack debits a whole point and sets this, the next attack spends the
     * unpaid half and clears it. Over any even number of attacks the formation has paid exactly half
     * a point per shot, which is the rule as written.
     *
     * Survives the turn deliberately -- a half point already bought is not forfeited at midnight.
     */
    var halfShotPending: Boolean = false

    /**
     * Suppression points on this formation that survive ONE round-wrap clear, under OG's
     * `Shock Tactics` ("suppression inflicted lasts the entire player turn").
     *
     * OSADA's suppression is [hits], cleared for every unit at once when the turn wraps back to
     * player 0 (`GameMap.endTurn`) -- see `docs/og-fidelity-plan.md` 0.1.1 for why that timing is a
     * faithful port and is not being changed. Lasting suppression is therefore expressed the only
     * way it can be here, and the way that document prescribes: as points that survive the victim's
     * next [unitEndTurn] rather than as a second suppression pool with its own units.
     */
    var lastingHits: Int = 0

    /**
     * Explicit hero-system opt-out for a unit loaned only for the current battle.
     * Campaign-player control, not [isCore], is otherwise the participation rule.
     */
    var isTemporaryBorrowed: Boolean = false

    /** Player-given unit name (Stage 3.5, Task 2), or null to display the equipment name.
     *  Serialized into saves only when set — unrenamed units keep the exact pre-rename
     *  save layout (see GameStateSerializer's byte-stability doc). */
    var customName: String? = null

    /**
     * Stable identity of the persistent CORE FORMATION this unit is the current instance of, or
     * null for scenario-only units (see `org.osada.hero.FormationId`).
     *
     * Set once by `Player.addCoreUnit` and then never reassigned — it must survive [upgrade]
     * (which mutates [eqid] in place, so the id rides along for free) and the scenario transition
     * (where it is carried by `serializeCoreUnit` / `CoreUnitListOperations`). This is what lets
     * a hero, a service record and a formation history outlive the equipment they were earned on.
     *
     * Same optional-key serialization rule as [customName]: emitted only when set, so saves of
     * non-core units keep their exact previous layout.
     */
    var formationId: String? = null

    internal var hex: Hex? = null

    fun getHex(): Hex? = hex

    fun setHex(hex: Hex?) {
        this.hex = hex
        if (hex != null) isDeployed = true
    }

    fun getPos(): Cell? = hex?.getPos()

    fun getEqid(useReal: Boolean = false): Int =
        when {
            carrier > 0 && !useReal -> carrier
            isMounted && transport != null && !useReal -> transport!!.eqid
            else -> eqid
        }

    fun unitData(useReal: Boolean = false): EquipmentData {
        // Keep the receiver concrete: calling a Kotlin extension on `dynamic` emits a runtime
        // `.withStatMultiplier(...)` method call, which EquipmentData does not actually expose.
        val data: EquipmentData = Equipment.getEquipment(getEqid(useReal)) ?: EquipmentData()
        return if (stalinRegimeBoosted) {
            boostedDataCache.getOrPut(data) { data.withStatMultiplier(STALIN_REGIME_MULTIPLIER) }
        } else {
            data
        }
    }

    fun getMovesLeft(): Int =
        when {
            carrier > 0 -> Equipment.equipment[carrier]?.movpoints ?: 0
            isMounted && transport != null -> Equipment.equipment[transport!!.eqid]?.movpoints ?: 0
            hasMoved -> 0
            else -> moveLeft
        }

    fun getAmmo(): Int = if (isMounted && transport != null) transport!!.ammo else ammo

    fun getFuel(): Int = if (isMounted && transport != null) transport!!.fuel else fuel

    fun upgrade(
        newEqid: Int,
        transportEqid: Int,
    ): Boolean {
        var targetEqid = newEqid
        if (targetEqid <= 0) targetEqid = eqid
        val oldClass = Equipment.equipment[eqid]?.uclass ?: 0
        var newClass = Equipment.equipment[targetEqid]?.uclass ?: 0
        if (oldClass == UnitClass.FLAK.value && newClass == UnitClass.AIR_DEFENCE.value) {
            newClass = UnitClass.AIR_DEFENCE.value
        }
        if (oldClass != newClass) return false
        this.eqid = targetEqid
        if (GameRules.isTransportable(this.eqid) && transportEqid > 0) {
            setTransport(transportEqid)
        } else {
            transport = null
        }
        refillAmmoFuel()
        // OG's `upgrade_ldr`: a leader drawn for the OLD equipment's class may be useless on the
        // new one, and the efile decides whether to keep, reroll or drop them
        // (`rules/LeaderOnUpgrade`). A no-op under `eqp-lxf`'s own 0, which is every shipped efile.
        LeaderOnUpgrade.afterUpgrade(this)
        entrenchment = 0
        if (isDeployed) {
            hasMoved = true
            hasFired = true
            hasOverstrength = true
            hasResupplied = true
        }
        return true
    }

    fun getIcon(): String = unitData().icon

    fun copy(other: GameUnit) {
        eqid = if (Equipment.hasEquipment(other.eqid)) other.eqid else Equipment.firstEqid() ?: 0
        id = other.id
        owner = other.owner
        hasMoved = other.hasMoved
        hasFired = other.hasFired
        hasOverstrength = other.hasOverstrength
        hasResupplied = other.hasResupplied
        isMounted = other.isMounted
        isSurprised = other.isSurprised
        isDeployed = other.isDeployed
        isCore = other.isCore
        carrier = other.carrier
        moveLeft = other.moveLeft
        ammo = other.ammo
        fuel = other.fuel
        strength = other.strength
        facing = other.facing
        flag = other.flag
        destroyed = other.destroyed
        hits = other.hits
        lastingHits = other.lastingHits
        shotsThisTurn = other.shotsThisTurn
        halfShotPending = other.halfShotPending
        experience = other.experience
        entrenchment = other.entrenchment
        entrenchTicks = other.entrenchTicks
        leader = other.leader
        leaderClassTrait = other.leaderClassTrait
        aiAnchored = other.aiAnchored
        aiHoldUntilTurn = other.aiHoldUntilTurn
        aiFearless = other.aiFearless
        aiObjectiveCol = other.aiObjectiveCol
        aiObjectiveRow = other.aiObjectiveRow
        aiFreeObjectiveDistance = other.aiFreeObjectiveDistance
        aiObjectiveFromOrdinal = other.aiObjectiveFromOrdinal
        aiFollowsObjectiveUnit = other.aiFollowsObjectiveUnit
        aiOrdinal = other.aiOrdinal
        authoredAttachmentIds = other.authoredAttachmentIds
        attachmentsForbidden = other.attachmentsForbidden
        nodossier = other.nodossier
        isScenarioDepot = other.isScenarioDepot
        basicStrength = other.basicStrength
        mustSurvive = other.mustSurvive
        landedTurn = other.landedTurn
        stalinRegimeBoosted = other.stalinRegimeBoosted
        isTemporaryBorrowed = other.isTemporaryBorrowed
        formationId = other.formationId
        player = Player().apply { copy(other.player ?: return@apply) }
        if (other.transport != null) {
            transport = Transport(other.transport!!.eqid).apply { copy(other.transport!!) }
        }
    }
}
