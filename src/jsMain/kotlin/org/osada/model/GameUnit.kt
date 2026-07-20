package org.osada.model

import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.isTransportable

@JsExport
@JsName("Unit")
class GameUnit(
    var eqid: Int,
) {
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

    /** Player-given unit name (Stage 3.5, Task 2), or null to display the equipment name.
     *  Serialized into saves only when set — unrenamed units keep the exact pre-rename
     *  save layout (see GameStateSerializer's byte-stability doc). */
    var customName: String? = null

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

    fun unitData(useReal: Boolean = false): EquipmentData = Equipment.equipment[getEqid(useReal)] ?: EquipmentData()

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
        experience = other.experience
        entrenchment = other.entrenchment
        entrenchTicks = other.entrenchTicks
        leader = other.leader
        player = Player().apply { copy(other.player ?: return@apply) }
        if (other.transport != null) {
            transport = Transport(other.transport!!.eqid).apply { copy(other.transport!!) }
        }
    }
}
