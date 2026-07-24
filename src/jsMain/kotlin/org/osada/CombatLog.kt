package org.osada

import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.Leaders

object CombatLog {
    @JsName("log")
    var log: dynamic = makeEmptyLog()
        private set

    private var combatRound: dynamic = js("{}")
    private var uniqueID: Int = 0

    private fun makeEmptyLog(): dynamic {
        val l = js("{}")
        l.combat = js("{}")
        l.reinforce = js("[]")
        l.leaders = js("[]")
        l.resupply = js("{}")
        l.objectives = js("[]")
        // Enemy units taken by surrender rather than damage — reported as their own Turn Report
        // group so encirclement reads as a distinct result, not just another kill.
        l.surrenders = js("[]")
        return l
    }

    fun reset() {
        log = makeEmptyLog()
        combatRound = js("{}")
        uniqueID = 0
    }

    fun addCombatStart(
        attacker: GameUnit,
        defender: GameUnit,
        turn: Int,
    ): Int {
        uniqueID++
        val round = js("{}")
        val a = js("{}")
        a.id = attacker.id
        a.eqid = attacker.eqid
        a.side = attacker.player?.side ?: -1
        a.str = attacker.strength
        a.xp = attacker.experience
        a.entrench = attacker.entrenchment
        a.pos = attacker.getPos()
        a.isMounted = attacker.isMounted
        a.isSurprised = attacker.isSurprised

        val d = js("{}")
        d.id = defender.id
        d.eqid = defender.eqid
        d.side = defender.player?.side ?: -1
        d.str = defender.strength
        d.xp = defender.experience
        d.entrench = defender.entrenchment
        d.pos = defender.getPos()
        d.isMounted = defender.isMounted

        round.attacker = a
        round.defender = d
        round.turn = turn
        combatRound[uniqueID] = round
        return uniqueID
    }

    fun addCombatEnd(
        attacker: GameUnit,
        defender: GameUnit,
        roundId: Int,
        isSupport: Boolean,
    ): Boolean {
        val round = combatRound[roundId]
        if (round == null || round == undefined) return false
        round.isSupport = isSupport

        val a = round.attacker
        val d = round.defender
        val attackerId = a.id as Int
        val defenderId = d.id as Int

        var g = log.combat[attackerId]
        if (g == null || g == undefined) {
            g = newCombatUnitInfo()
            log.combat[attackerId] = g
        }
        var m = log.combat[defenderId]
        if (m == null || m == undefined) {
            m = newCombatUnitInfo()
            log.combat[defenderId] = m
        }

        g.id = attackerId
        g.eqid = a.eqid
        g.side = a.side
        g.losses = (g.losses as Int) + (a.str as Int) - attacker.strength
        g.str = attacker.strength
        g.xp = (g.xp as Int) + attacker.experience - (a.xp as Int)
        g.ammo = attacker.ammo
        g.entrenchLost = (g.entrenchLost as Int) + (a.entrench as Int) - attacker.entrenchment
        g.entrench = attacker.entrenchment
        g.pos = a.pos
        g.isSurprised = a.isSurprised
        g.isCore = attacker.isCore
        g.isSupport = round.isSupport
        if (isSupport) g.supports = (g.supports as Int) + 1 else g.assaults = (g.assaults as Int) + 1
        pushTo(g.unitCombatList, defenderId)

        m.id = defenderId
        m.eqid = d.eqid
        m.side = d.side
        m.losses = (m.losses as Int) + (d.str as Int) - defender.strength
        m.str = defender.strength
        m.xp = (m.xp as Int) + defender.experience - (d.xp as Int)
        m.ammo = defender.ammo
        m.entrenchLost = (m.entrenchLost as Int) + (d.entrench as Int) - defender.entrenchment
        m.entrench = defender.entrenchment
        m.pos = d.pos
        m.isCore = defender.isCore
        m.defends = (m.defends as Int) + 1
        pushTo(m.unitCombatList, attackerId)

        g.kills = m.losses
        m.kills = g.losses

        deleteKey(combatRound, roundId)
        return true
    }

    fun addReinforcement(unit: GameUnit) {
        val player = unit.player ?: return
        val entry = js("{}")
        entry.eqid = unit.eqid
        entry.pos = unit.getPos()
        entry.side = player.side
        pushTo(log.reinforce, entry)
    }

    fun addLeader(unit: GameUnit) {
        val player = unit.player ?: return
        val entry = js("{}")
        entry.id = unit.id
        entry.eqid = unit.eqid
        entry.isCore = unit.isCore
        entry.pos = unit.getPos()
        entry.leader = unit.leader
        entry.classLeader = Leaders.getUnitClassLeader(unit)
        entry.side = player.side
        pushTo(log.leaders, entry)
    }

    private fun newCombatUnitInfo(): dynamic {
        val o = js("{}")
        o.eqid = 0
        o.id = 0
        o.side = -1
        o.ammo = 0
        o.entrench = 0
        o.entrenchLost = 0
        o.xp = 0
        o.kills = 0
        o.losses = 0
        o.str = 0
        o.pos = Cell(0, 0)
        o.supports = 0
        o.defends = 0
        o.assaults = 0
        o.isCore = false
        o.isSurprised = false
        o.isMounted = false
        o.unitCombatList = js("[]")
        return o
    }

    // Internal (not private): CombatLogQueries.kt's addResupply/addObjectiveCapture extensions
    // call this from another file.
    internal fun pushTo(
        arr: dynamic,
        value: dynamic,
    ) {
        arr.push(value)
    }

    private fun deleteKey(
        obj: dynamic,
        key: Int,
    ) {
        js("(function(o,k){ delete o[k]; })")(obj, key)
    }
}
