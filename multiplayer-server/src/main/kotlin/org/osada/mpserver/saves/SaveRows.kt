package org.osada.mpserver.saves

import org.osada.mpserver.account.Tx
import org.osada.mpserver.account.longOrNull
import java.sql.ResultSet

// Row-level statements behind [SaveRepository]. Every one filters by account id.

internal fun Tx.quotaRefusal(
    accountId: String,
    releasedBytes: Long,
    addedBytes: Long,
): WriteOutcome? =
    single(
        "SELECT used_bytes, quota_bytes FROM accounts WHERE id = ?",
        accountId,
    ) { it.getLong("used_bytes") to it.getLong("quota_bytes") }
        ?.takeIf { (used, quota) -> used - releasedBytes + addedBytes > quota }
        ?.let { (used, quota) -> WriteOutcome.QuotaExceeded(used, quota) }

internal fun Tx.upsertRun(
    accountId: String,
    write: SnapshotWrite,
    revision: Long,
    parentRevision: Long,
    now: Long,
) {
    update(
        """INSERT INTO runs(account_id, run_instance_id, campaign_file, data_epoch, revision, parent_revision,
               content_hash, state, summary, client_played_at, server_saved_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(account_id, run_instance_id) DO UPDATE SET
               revision = excluded.revision, parent_revision = excluded.parent_revision,
               content_hash = excluded.content_hash, state = excluded.state, summary = excluded.summary,
               client_played_at = excluded.client_played_at, server_saved_at = excluded.server_saved_at""",
        accountId,
        write.runInstanceId,
        write.campaignFile,
        write.dataEpoch,
        revision,
        parentRevision,
        write.contentHash,
        if (write.completed) SaveRepository.COMPLETED else SaveRepository.LIVE,
        write.summary,
        write.clientPlayedAt,
        now,
    )
}

internal fun Tx.insertSnapshot(
    accountId: String,
    write: SnapshotWrite,
    role: String,
    revision: Long,
    now: Long,
) {
    update(
        """INSERT INTO snapshots(account_id, run_instance_id, role, revision, content_hash, mission_key, payload,
               decoded_bytes, stored_bytes, client_played_at, server_saved_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        accountId,
        write.runInstanceId,
        role,
        revision,
        write.contentHash,
        write.missionKey,
        write.compressedPayload,
        write.decodedBytes,
        write.compressedPayload.size.toLong(),
        write.clientPlayedAt,
        now,
    )
}

internal fun Tx.storedBytes(
    accountId: String,
    runInstanceId: String,
    role: String,
): Long =
    single(
        "SELECT stored_bytes FROM snapshots WHERE account_id = ? AND run_instance_id = ? AND role = ?",
        accountId,
        runInstanceId,
        role,
    ) { it.getLong("stored_bytes") } ?: 0L

internal fun Tx.recountUsage(accountId: String) {
    update(
        "UPDATE accounts SET used_bytes = " +
            "(SELECT COALESCE(SUM(stored_bytes), 0) FROM snapshots WHERE account_id = ?) WHERE id = ?",
        accountId,
        accountId,
    )
}

internal fun Tx.findRun(
    accountId: String,
    runInstanceId: String,
): RunRecord? =
    single(
        "SELECT * FROM runs WHERE account_id = ? AND run_instance_id = ?",
        accountId,
        runInstanceId,
        map = ::runRecord,
    )

internal fun Tx.deleteRole(
    accountId: String,
    runInstanceId: String,
    role: String,
) {
    update(
        "DELETE FROM snapshots WHERE account_id = ? AND run_instance_id = ? AND role = ?",
        accountId,
        runInstanceId,
        role,
    )
}

internal fun runRecord(row: ResultSet): RunRecord =
    RunRecord(
        runInstanceId = row.getString("run_instance_id"),
        campaignFile = row.getString("campaign_file"),
        revision = row.getLong("revision"),
        parentRevision = row.getLong("parent_revision"),
        contentHash = row.getString("content_hash"),
        state = row.getString("state"),
        summary = row.getString("summary"),
        clientPlayedAt = row.longOrNull("client_played_at"),
        serverSavedAt = row.getLong("server_saved_at"),
    )
