package org.osada.model

/**
 * Restores the optional properties that belong to a placed scenario unit rather than to its
 * equipment record. Kept in the model package so both ordinary map-unit deserialization and the
 * separate campaign-core restore path use the same defaults.
 */
internal fun GameUnit.applySerializedScenarioProperties(data: dynamic) {
    isScenarioDepot = data.depot as? Boolean ?: false
    mustSurvive = data.msu as? Boolean ?: false
    leaderClassTrait = data.ldrclass as? Int ?: -1
    basicStrength = data.basicStrength as? Int ?: GameUnit.DEFAULT_BASIC_STRENGTH
    landedTurn = data.landedTurn as? Int ?: -1
    applySerializedAiOrders(data)
}

/**
 * The scenario author's AI orders, restored alongside the other placed-unit properties.
 *
 * Every key is optional and its absence is "no order" -- which is what a pre-2026-09 save means and
 * what the great majority of formations carry (`rules/AiOrders`).
 */
private fun GameUnit.applySerializedAiOrders(data: dynamic) {
    aiAnchored = data.aiAnchored as? Boolean ?: false
    aiHoldUntilTurn = data.aiHoldUntil as? Int ?: 0
    aiFearless = data.aiFearless as? Boolean ?: false
    aiObjectiveCol = data.aiObjCol as? Int ?: -1
    aiObjectiveRow = data.aiObjRow as? Int ?: -1
    aiFreeObjectiveDistance = data.aiFreeOh as? Int ?: 0
    aiObjectiveFromOrdinal = data.aiObjFrom as? Int ?: 0
    aiFollowsObjectiveUnit = data.aiFollowPos as? Boolean ?: false
    aiOrdinal = data.aiOrdinal as? Int ?: 0
    authoredAttachmentIds = readIntArray(data.authoredAttachments)
    attachmentsForbidden = data.noAttachments as? Boolean ?: false
}

/** A saved `Int` array, or an empty list for the key being absent. */
private fun readIntArray(data: dynamic): List<Int> {
    val length = if (data == null || data == undefined) null else data.length as? Int
    return (0 until (length ?: 0)).mapNotNull { data[it] as? Int }
}
