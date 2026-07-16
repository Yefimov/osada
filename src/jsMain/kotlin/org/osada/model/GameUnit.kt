package org.osada.model

import org.osada.*
import org.osada.rules.GameRules
import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
@JsName("Unit")
class GameUnit(var eqid: Int) {
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

    private var hex: Hex? = null

    fun getHex(): Hex? = hex
    fun setHex(hex: Hex?) {
        this.hex = hex
        if (hex != null) isDeployed = true
    }

    fun getPos(): Cell? = hex?.getPos()

    fun getEqid(useReal: Boolean = false): Int {
        return when {
            carrier > 0 && !useReal -> carrier
            isMounted && transport != null && !useReal -> transport!!.eqid
            else -> eqid
        }
    }

    fun unitData(useReal: Boolean = false): EquipmentData {
        return Equipment.equipment[getEqid(useReal)] ?: EquipmentData()
    }

    fun getMovesLeft(): Int {
        return when {
            carrier > 0 -> Equipment.equipment[carrier]?.movpoints ?: 0
            isMounted && transport != null -> Equipment.equipment[transport!!.eqid]?.movpoints ?: 0
            hasMoved -> 0
            else -> moveLeft
        }
    }

    fun getAmmo(): Int {
        return if (isMounted && transport != null) transport!!.ammo else ammo
    }

    fun getFuel(): Int {
        return if (isMounted && transport != null) transport!!.fuel else fuel
    }

    fun hit(damage: Int) {
        strength -= damage
        hits++
        if (entrenchment > 0) entrenchment--
        if (strength <= 0) destroyed = true
    }

    fun fire(usedOverstrength: Boolean) {
        tempSpotted = true
        ammo--
        if (usedOverstrength) {
            hasFired = true
            hasOverstrength = true
        }
    }

    fun move(cost: Int) {
        entrenchment = 0
        // JS: `254 <= a ? a/254 + a%254 >> 0 : a`. The `>> 0` is an integer
        // truncation (no-op), NOT a shift-by-one — `shr 1` here halved the cost.
        val realCost = if (cost >= 254) (cost / 254 + cost % 254) else cost
        if (isMounted && transport != null) {
            hasFired = true
            if (GameRules.unitUsesFuel(transport!!)) {
                transport!!.fuel -= realCost
            }
            moveLeft = 0
        } else {
            if (GameRules.unitUsesFuel(this) && carrier == 0) {
                fuel -= realCost
            }
            moveLeft -= realCost
        }
        if (unitData().uclass != UnitClass.RECON.value || moveLeft <= 0) {
            hasMoved = true
            hasOverstrength = true
        }
        if (carrier < 0) carrier = 0
    }

    fun upgrade(newEqid: Int, transportEqid: Int): Boolean {
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

    fun resupply(supply: Supply) {
        ammo += supply.ammo
        fuel += supply.fuel
        transport?.let {
            it.ammo += supply.transportAmmo
            it.fuel += supply.transportFuel
        }
        hasMoved = true
        hasFired = true
        hasResupplied = true
    }

    fun reinforce(amount: Int, overStrength: Boolean) {
        strength += amount
        hasMoved = true
        hasFired = true
        hasResupplied = true
        if (overStrength) hasOverstrength = true
    }

    fun setTransport(eqid: Int) {
        transport = Transport(eqid)
    }

    fun mount() {
        isMounted = true
    }

    fun unmount() {
        isMounted = false
    }

    fun embark(carrierClass: UnitClass): Boolean {
        val eqid = Equipment.getCountryEquipmentByClass(carrierClass, (player?.country ?: 0) + 1).firstOrNull()
            ?: return false
        carrier = eqid
        return true
    }

    fun entrench(): Boolean {
        if (!GameRules.canEntrench(this)) return false
        val hex = this.hex ?: return false
        val terrainEnt = terrainEntrenchment[hex.terrain]
        val unitClass = unitData().uclass
        if (entrenchment >= terrainEnt) {
            var extra = entrenchment - terrainEnt
            var limit = 9 * extra + 4
            entrenchTicks += (experience / 100 + (terrainEnt + 1) * unitEntrenchRate[unitClass]).toInt()
            while (entrenchTicks >= limit && entrenchment < terrainEnt + 5) {
                entrenchTicks -= limit
                entrenchment++
                extra++
                limit = 9 * extra + 4
            }
        } else {
            entrenchment = terrainEnt
            entrenchTicks = 0
        }
        return true
    }

    fun toggleEmbark() {
        carrier = -carrier
    }

    fun getIcon(): String = unitData().icon

    fun refillAmmoFuel() {
        Equipment.getEquipment(eqid)?.let {
            ammo = it.ammo
            fuel = it.fuel
        }
        transport?.let { tr ->
            Equipment.getEquipment(tr.eqid)?.let {
                tr.ammo = it.ammo
                tr.fuel = it.fuel
            }
        }
    }

    fun unitEndTurn(spotSide: Int) {
        entrench()
        moveLeft = Equipment.equipment[eqid]?.movpoints ?: 0
        hasMoved = false
        hasFired = false
        hasOverstrength = false
        hasResupplied = false
        isSurprised = false
        hits = 0
        if (unitData().uclass != UnitClass.FORTIFICATION.value) {
            val hex = this.hex
            if (hex == null || !hex.isSpotted(spotSide)) {
                tempSpotted = false
            }
        }
    }

    fun cleanup() {
        // nothing to cleanup explicitly in Kotlin
    }

    fun copy(other: GameUnit) {
        if (other == null) return
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
