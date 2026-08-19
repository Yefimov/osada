package org.osada.model

import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.isTransportable

@JsExport
@JsName("Unit")
class GameUnit(
    var eqid: Int,
) {
    companion object {
        internal const val STALIN_REGIME_MULTIPLIER = 10
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
    var tempSpotted: Boolean = false
    var nodossier: Boolean = false

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
        nodossier = other.nodossier
        stalinRegimeBoosted = other.stalinRegimeBoosted
        isTemporaryBorrowed = other.isTemporaryBorrowed
        formationId = other.formationId
        player = Player().apply { copy(other.player ?: return@apply) }
        if (other.transport != null) {
            transport = Transport(other.transport!!.eqid).apply { copy(other.transport!!) }
        }
    }
}
