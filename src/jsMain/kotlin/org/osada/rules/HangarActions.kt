package org.osada.rules

import org.osada.multiplayer.client.OsadaMultiplayer

/**
 * The hangar chips of the unit strip ([UnitActionAvailability]).
 *
 *  - **Land in hangar** on an AIRCRAFT standing on or beside a friendly carrier or airbase. Landing
 *    is the rest of its turn; a hangar that is full keeps the chip and says so, rather than leaving
 *    the player to wonder why it vanished.
 *  - **Hangar** on the container itself, showing how many are aboard; it opens the hangar window,
 *    where a passenger is chosen and launched (`ui/HangarDialog`).
 *  - **Take cover** / **Garrison**, the same pair for land formations and fortifications.
 *  - **Aboard** on any other container that carries somebody (a ship with an authored passenger).
 *
 * **Not offered in a multiplayer room yet** -- the host has no hangar commands, so a local launch
 * would desynchronise the game (`docs/multiplayer/pending-features.md`).
 */
internal object HangarActions {
    fun land(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val near = if (offered()) HangarStowage.containersNear(context.map, unit) else emptyList()
        if (near.isEmpty()) return ActionAvailability.notApplicable(UnitActionId.LAND_IN_HANGAR)
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        if (HangarStowage.landingContainer(context.map, unit) == null) {
            reasons += ActionBlock(ActionBlockReason.HANGAR_FULL)
        }
        val effects =
            listOf(ActionEffect(ActionEffectKind.HANGAR_STOW), ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION))
        return ActionAvailability(UnitActionId.LAND_IN_HANGAR, true, reasons.isEmpty(), reasons, effects)
    }

    fun hangar(context: UnitActionContext): ActionAvailability {
        val container = context.unit
        val capacity = CarrierHangars.capacity(container)
        // Carriers and airbases, which aircraft can land in during play. Other containers answer
        // through Garrison (a fortification) or Aboard (anything else).
        val holds = CarrierHangars.holdsAircraft(container)
        if (!offered() || capacity <= 0 || !holds) return ActionAvailability.notApplicable(UnitActionId.HANGAR)
        // Always openable on the owner's own turn, empty or not: the window is also where the
        // player learns what the hangar is for.
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        val effects = listOf(ActionEffect(ActionEffectKind.HANGAR_OCCUPANCY, container.hangar.size, capacity))
        return ActionAvailability(UnitActionId.HANGAR, true, reasons.isEmpty(), reasons, effects)
    }

    /** A land formation beside a friendly fortification with room for a garrison (2026-10-07). */
    fun enterCover(context: UnitActionContext): ActionAvailability {
        val unit = context.unit
        val near = if (offered()) HangarStowage.coverNear(context.map, unit) else emptyList()
        if (near.isEmpty()) return ActionAvailability.notApplicable(UnitActionId.ENTER_COVER)
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        if (HangarStowage.coverContainer(context.map, unit) == null) {
            reasons += ActionBlock(ActionBlockReason.COVER_FULL)
        }
        val effects =
            listOf(ActionEffect(ActionEffectKind.COVER_STOW), ActionEffect(ActionEffectKind.ENDS_UNIT_ACTION))
        return ActionAvailability(UnitActionId.ENTER_COVER, true, reasons.isEmpty(), reasons, effects)
    }

    /** The fortification's own chip: who is inside, and the order to come out. */
    fun cover(context: UnitActionContext): ActionAvailability {
        val container = context.unit
        if (!offered() || !HangarStowage.isCover(container)) {
            return ActionAvailability.notApplicable(UnitActionId.COVER)
        }
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        val occupancy =
            ActionEffect(ActionEffectKind.HANGAR_OCCUPANCY, container.hangar.size, CarrierHangars.capacity(container))
        return ActionAvailability(UnitActionId.COVER, true, reasons.isEmpty(), reasons, listOf(occupancy))
    }

    /**
     * A container that is neither a hangar nor a garrison but carries somebody -- which only
     * authored data can bring about (`aboard="1"`, `bn5s04`'s commando in a submarine). Without the
     * window that passenger could never leave; with nobody aboard there is nothing to offer.
     */
    fun aboard(context: UnitActionContext): ActionAvailability {
        val container = context.unit
        val carries =
            container.hangar.isNotEmpty() &&
                !CarrierHangars.holdsAircraft(container) &&
                !HangarStowage.isCover(container)
        if (!offered() || !carries) return ActionAvailability.notApplicable(UnitActionId.ABOARD)
        val reasons = mutableListOf<ActionBlock>()
        UnitActionAvailability.addTurnBlock(context, reasons)
        val occupancy =
            ActionEffect(ActionEffectKind.HANGAR_OCCUPANCY, container.hangar.size, CarrierHangars.capacity(container))
        return ActionAvailability(UnitActionId.ABOARD, true, reasons.isEmpty(), reasons, listOf(occupancy))
    }

    private fun offered(): Boolean = CarrierHangars.active() && !OsadaMultiplayer.active
}
