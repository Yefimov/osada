@file:Suppress("UnusedParameter")

package org.osada.multiplayer.model

import org.osada.PlayerType
import org.osada.model.Player

fun Player.isAiControlled(controlPlan: PlayerControlPlan? = null): Boolean =
    controlPlan?.executionByPlayerId?.get(id)?.let {
        it == PlayerExecution.AUTHORITY_AI || it == PlayerExecution.REMOTE_AI
    } ?: (type == PlayerType.AI_LOCAL || type == PlayerType.AI_SERVER || type == PlayerType.AI_SCRIPTED)

fun Player.isLocallyActionable(session: MultiplayerSession?): Boolean =
    session?.isPlayerLocallyActionable(id) ?: (type == PlayerType.HUMAN_LOCAL)

fun Player.isHumanControlled(controlPlan: PlayerControlPlan? = null): Boolean =
    controlPlan?.executionByPlayerId?.get(id)?.let {
        it == PlayerExecution.LOCAL_HUMAN || it == PlayerExecution.REMOTE_HUMAN
    } ?: (type == PlayerType.HUMAN_LOCAL || type == PlayerType.HUMAN_NETWORK)
