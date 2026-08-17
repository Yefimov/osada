package org.osada.save

/**
 * Shape-only validation shared by the store (before it commits/reads back a generation) and by
 * import (before a file is even offered as a preview). Deliberately domain-agnostic: it has no
 * dependency on the game model, so [LocalStorageSaveSnapshotStore] and its tests do not need one
 * either. Deeper, phase-aware validation (does this payload actually resolve to a legal roster for
 * its declared phase?) is `org.osada.SavePhaseValidation`, which does depend on the game model and
 * runs one layer up, in `GameStatePersistence`.
 */
@Suppress("TooGenericExceptionCaught")
object SaveValidation {
    fun isWellFormedSnapshotJson(raw: String): Boolean =
        try {
            val d = JSON.parse<dynamic>(raw)
            val hasCore =
                d.id != null &&
                    d.id != undefined &&
                    d.campaignRunId != null &&
                    d.campaignRunId != undefined &&
                    d.payload != null &&
                    d.payload != undefined
            // The payload itself must also be parseable JSON with a `scenario` key -- catches a
            // truncated write (e.g. quota hit mid-JSON.stringify) that still looks like valid JSON
            // at the wrapper level.
            hasCore && payloadLooksLikeASave(d.payload as? String)
        } catch (e: Throwable) {
            console.warn("[osada] snapshot shape check: not parseable JSON", e)
            false
        }

    private fun payloadLooksLikeASave(payload: String?): Boolean {
        if (payload == null) return false
        return try {
            val p = JSON.parse<dynamic>(payload)
            p.scenario != null && p.scenario != undefined
        } catch (e: Throwable) {
            console.warn("[osada] snapshot payload shape check: not parseable JSON", e)
            false
        }
    }
}
