package org.osada.mpserver.saves

import org.osada.mpserver.account.AccountDatabase
import org.osada.mpserver.account.Tx

/** One cloud campaign as the manifest describes it (§4). */
class RunRecord(
    val runInstanceId: String,
    val campaignFile: String,
    val revision: Long,
    val parentRevision: Long,
    val contentHash: String,
    val state: String,
    val summary: String,
    val clientPlayedAt: Long?,
    val serverSavedAt: Long,
)

class Manifest(
    val dataEpoch: Long,
    val usedBytes: Long,
    val quotaBytes: Long,
    val runs: List<RunRecord>,
)

class StoredSnapshot(
    val run: RunRecord,
    val role: String,
    val revision: Long,
    val contentHash: String,
    val missionKey: String?,
    val compressedPayload: ByteArray,
)

/** An upload, already size-checked, hash-verified and compressed by the route. */
@Suppress("ArrayInDataClass") // Never compared; a data class only to carry the many fields.
data class SnapshotWrite(
    val runInstanceId: String,
    val campaignFile: String,
    /** The revision this upload descends from; null creates the run (`If-None-Match: *`). */
    val baseRevision: Long?,
    val dataEpoch: Long,
    val contentHash: String,
    val compressedPayload: ByteArray,
    val decodedBytes: Long,
    val summary: String,
    val clientPlayedAt: Long?,
    val completed: Boolean,
    /** Set for an immutable mission-start upload; null for the ordinary latest-resume channel. */
    val missionKey: String? = null,
)

sealed interface WriteOutcome {
    /** [unchanged]: the cloud already held exactly this content, so no revision was spent (§8 case 4). */
    class Accepted(
        val run: RunRecord,
        val unchanged: Boolean,
    ) : WriteOutcome

    /** 412: the cloud moved on (or exists already); the client must reconcile, never overwrite. */
    class Stale(
        val current: RunRecord?,
    ) : WriteOutcome

    /** 410: the account's data epoch advanced (expiry) or the run was deliberately deleted. */
    class Gone(
        val reason: String,
        val dataEpoch: Long,
    ) : WriteOutcome

    /** 507: the account budget cannot hold this write; earlier saves are kept untouched. */
    class QuotaExceeded(
        val usedBytes: Long,
        val quotaBytes: Long,
    ) : WriteOutcome

    /** 409: a different payload for a mission start that is already recorded. */
    object MissionStartImmutable : WriteOutcome
}

/**
 * The save database rules (§4–§5.2): ancestry-checked writes, retained generations, quotas and
 * tombstones. Every read and write carries the account id from the session, and every statement
 * filters by it, so guessing another account's run id reveals nothing.
 */
class SaveRepository(
    private val database: AccountDatabase,
) {
    suspend fun manifest(accountId: String): Manifest? =
        database.transaction {
            val runs =
                query(
                    "SELECT * FROM runs WHERE account_id = ? ORDER BY server_saved_at DESC",
                    accountId,
                    map = ::runRecord,
                )
            single("SELECT data_epoch, used_bytes, quota_bytes FROM accounts WHERE id = ?", accountId) {
                Manifest(it.getLong("data_epoch"), it.getLong("used_bytes"), it.getLong("quota_bytes"), runs)
            }
        }

    suspend fun snapshot(
        accountId: String,
        runInstanceId: String,
        role: String,
    ): StoredSnapshot? =
        database.transaction {
            val run = findRun(accountId, runInstanceId) ?: return@transaction null
            single(
                "SELECT * FROM snapshots WHERE account_id = ? AND run_instance_id = ? AND role = ?",
                accountId,
                runInstanceId,
                role,
            ) {
                StoredSnapshot(
                    run = run,
                    role = role,
                    revision = it.getLong("revision"),
                    contentHash = it.getString("content_hash"),
                    missionKey = it.getString("mission_key"),
                    compressedPayload = it.getBytes("payload"),
                )
            }
        }

    /**
     * Applies [write] atomically, replaying [receipt] instead when the same request id was already
     * accepted. [render] turns the outcome into the stored response inside the same transaction, so
     * a lost reply followed by a retry returns the original receipt, never a second revision (§4).
     */
    suspend fun write(
        accountId: String,
        write: SnapshotWrite,
        receipt: ReceiptKey,
        now: Long,
        render: (WriteOutcome) -> RenderedReply,
    ): RenderedReply =
        database.transaction {
            val previous = Receipts.find(this, accountId, receipt)
            previous ?: run {
                val outcome =
                    when (write.missionKey) {
                        null -> writeLatest(accountId, write, now)
                        else -> writeMissionStart(accountId, write, now)
                    }
                render(outcome).also { reply ->
                    if (outcome is WriteOutcome.Accepted) {
                        Receipts.store(this, accountId, receipt, reply, now)
                        update(
                            "UPDATE accounts SET last_active_at = ? WHERE id = ? AND last_active_at <= ?",
                            now,
                            accountId,
                            now - ACTIVITY_COALESCE_MILLIS,
                        )
                    }
                }
            }
        }

    /** A deliberate deletion leaves a payload-free tombstone that blocks silent resurrection (§5.1). */
    suspend fun delete(
        accountId: String,
        runInstanceId: String,
        baseRevision: Long,
        now: Long,
    ): WriteOutcome =
        database.transaction {
            val run = findRun(accountId, runInstanceId)
            when {
                run == null || run.state == DELETED || run.revision != baseRevision -> WriteOutcome.Stale(run)
                else -> {
                    update(
                        "DELETE FROM snapshots WHERE account_id = ? AND run_instance_id = ?",
                        accountId,
                        runInstanceId,
                    )
                    update(
                        "UPDATE runs SET state = ?, revision = revision + 1, parent_revision = revision, " +
                            "server_saved_at = ? WHERE account_id = ? AND run_instance_id = ?",
                        DELETED,
                        now,
                        accountId,
                        runInstanceId,
                    )
                    recountUsage(accountId)
                    WriteOutcome.Accepted(checkNotNull(findRun(accountId, runInstanceId)), unchanged = false)
                }
            }
        }

    suspend fun sweepReceipts(now: Long): Int =
        database.transaction {
            update("DELETE FROM receipts WHERE expires_at <= ?", now)
        }

    @Suppress("ReturnCount") // Each guard is one row of the §4 reconciliation table, checked in order.
    private fun Tx.writeLatest(
        accountId: String,
        write: SnapshotWrite,
        now: Long,
    ): WriteOutcome {
        gate(accountId, write)?.let { return it }
        val run = findRun(accountId, write.runInstanceId)
        // Identical content on both sides is not a conflict and costs no revision.
        if (run != null && run.contentHash == write.contentHash) return WriteOutcome.Accepted(run, unchanged = true)
        if (run?.revision != write.baseRevision) return WriteOutcome.Stale(run)
        // The old latest becomes previous-good; the old previous is the only payload released.
        val released = storedBytes(accountId, write.runInstanceId, PREVIOUS)
        quotaRefusal(accountId, released, write.compressedPayload.size.toLong())?.let { return it }

        deleteRole(accountId, write.runInstanceId, PREVIOUS)
        update(
            "UPDATE snapshots SET role = ? WHERE account_id = ? AND run_instance_id = ? AND role = ?",
            PREVIOUS,
            accountId,
            write.runInstanceId,
            LATEST,
        )
        val revision = (run?.revision ?: 0L) + 1
        upsertRun(accountId, write, revision, run?.revision ?: 0L, now)
        insertSnapshot(accountId, write, LATEST, revision, now)
        recountUsage(accountId)
        return WriteOutcome.Accepted(checkNotNull(findRun(accountId, write.runInstanceId)), unchanged = false)
    }

    /** The opening of a mission is written once per mission key and never replaced (§2). */
    @Suppress("ReturnCount") // Guards in the order a client needs them explained.
    private fun Tx.writeMissionStart(
        accountId: String,
        write: SnapshotWrite,
        now: Long,
    ): WriteOutcome {
        gate(accountId, write)?.let { return it }
        val run = findRun(accountId, write.runInstanceId) ?: return WriteOutcome.Stale(null)
        val existing =
            single(
                "SELECT * FROM snapshots WHERE account_id = ? AND run_instance_id = ? AND role = ?",
                accountId,
                write.runInstanceId,
                MISSION_START,
            ) { Triple(it.getString("mission_key"), it.getString("content_hash"), it.getLong("stored_bytes")) }
        if (existing != null && existing.first == write.missionKey) {
            return if (existing.second == write.contentHash) {
                WriteOutcome.Accepted(run, unchanged = true)
            } else {
                WriteOutcome.MissionStartImmutable
            }
        }
        quotaRefusal(accountId, existing?.third ?: 0L, write.compressedPayload.size.toLong())?.let { return it }
        deleteRole(accountId, write.runInstanceId, MISSION_START)
        insertSnapshot(accountId, write, MISSION_START, run.revision, now)
        recountUsage(accountId)
        return WriteOutcome.Accepted(run, unchanged = false)
    }

    /** Epoch and tombstone checks shared by every write. */
    private fun Tx.gate(
        accountId: String,
        write: SnapshotWrite,
    ): WriteOutcome? {
        val epoch = single("SELECT data_epoch FROM accounts WHERE id = ?", accountId) { it.getLong("data_epoch") } ?: 0L
        val run = findRun(accountId, write.runInstanceId)
        return when {
            write.dataEpoch != epoch -> WriteOutcome.Gone("epoch-expired", epoch)
            run?.state == DELETED -> WriteOutcome.Gone("run-deleted", epoch)
            else -> null
        }
    }

    companion object {
        const val LATEST = "latest"
        const val PREVIOUS = "previous"
        const val MISSION_START = "mission-start"
        const val LIVE = "live"
        const val COMPLETED = "completed"
        const val DELETED = "deleted"
        private const val ACTIVITY_COALESCE_MILLIS = 86_400_000L
    }
}
