package org.osada.model

import org.osada.UnitClass

/**
 * The lifetime of OG's non-organic transport pools — air, naval and rail.
 *
 * ### What the pool number means, and where that comes from
 *
 * The author's own Features page states it for the pool that was added last, and states it as the
 * shared rule for all of them:
 *
 * > *"Trains are a non-organic transport and so it requires to be configured at design time **how
 * > many trains can be used at any time** (trains pool), similar to air/naval/helo transports."*
 * > — `luis-guzman.com/OpenGen_Features.html`, "Train Transport / Railways"
 *
 * **"At any time" makes the number a CONCURRENT CAPACITY, not an allowance of embarkations.** A
 * transport is committed while it carries something and free again once it does not. The changelog
 * says the same thing from the other side, as a bug that had to be fixed:
 *
 * > *"If after deploying paratrooper using Air Transport, that unit was un-deployed, the transport
 * > was **not returned to the pool** when Pg2Mode is not set."* — 0.91.1.0, 12-Jan-2018
 *
 * Until 2026-08-29 OSADA spent a pool point permanently on embarkation and never gave it back,
 * which is the OTHER reading and the wrong one. It was invisible for as long as it was: every
 * shipped scenario carried `airtrans="0" navaltrans="0"`, so no unit could embark at all.
 * `tools/og-import/add_transport_pools.py` is what makes the difference observable, which is why
 * the lifetime had to be settled before that patcher runs.
 *
 * ### Air and naval: released when the cargo is ashore
 *
 * [GameUnit.carrier] is positive while embarked, negative for the landing leg, and zeroed by the
 * move that completes the disembarkation (`GameUnitActions`). That last transition is the release
 * point, and it is the only one: cancelling a pending disembarkation flips the sign back and is
 * correctly free under either reading.
 *
 * ### Rail: the same lifetime, since 2026-09-28
 *
 * OG's railway is CONTAINMENT — *"Units embarked in RTP, will look for the closer free station to
 * disembark"* (0.92.0.0) — and OSADA now builds it that way (`rules/RailTransport`): a slot is taken
 * when a formation boards a train and returned when it steps off. Until then the railway was an
 * atomic relocation, the slot was held for one turn and refreshed as its owner's turn opened; the
 * turn-start pass is now a RECOUNT against the trains actually carrying something
 * (`RailTransport.recountPool`), which also brings a save from that era back to the right number.
 */
fun Player.takeTransportFromPool(uclass: Int) {
    when (uclass) {
        UnitClass.AIR_TRANSPORT.value -> airTransports = (airTransports - 1).coerceAtLeast(0)
        UnitClass.NAVAL_TRANSPORT.value -> navalTransports = (navalTransports - 1).coerceAtLeast(0)
    }
}

/**
 * Gives one transport of [uclass] back to its pool, never past the size the scenario authored.
 *
 * The ceiling is not defensive tidiness: a scenario may deploy a unit that is already embarked, and
 * that unit spent nothing to get there. Without the clamp its first disembarkation would hand the
 * player a transport the author never granted.
 */
fun Player.returnTransportToPool(uclass: Int) {
    when (uclass) {
        UnitClass.AIR_TRANSPORT.value -> if (airTransports < airTransportsMax) airTransports++
        UnitClass.NAVAL_TRANSPORT.value -> if (navalTransports < navalTransportsMax) navalTransports++
    }
}
