package org.osada.save

import kotlinx.browser.localStorage
import org.osada.VERSION
import org.osada.hero.CampaignHeroArchive
import org.osada.hero.HeroArchive
import org.osada.hero.HeroArchiveCodec

/**
 * Narrow persistence collaborator for the profile-level hero archive
 * (`docs/design/hero-desk-and-profile-archive.md` §7).
 *
 * Deliberately its own store rather than another responsibility on `HallOfFame` or
 * `GameStatePersistence`: the archive has a different lifetime from every campaign save (it
 * survives clearing a run) and from the Hall of Fame (it holds complete careers, not summaries).
 * Keeping it behind this interface is also what lets the desk's projection logic be unit-tested
 * against an in-memory adapter with no browser storage at all.
 */
internal interface HeroArchiveStore {
    fun read(): HeroArchive

    /** Upserts one campaign's roster as a COMPLETE replacement, so retrying a mission transition is
     *  idempotent and cannot duplicate medals or service events (§4). */
    fun replaceCampaign(archive: CampaignHeroArchive): SaveResult

    fun deleteCampaign(campaignRunId: String): SaveResult

    fun replaceAll(archive: HeroArchive): SaveResult
}

/**
 * `localStorage`-backed [HeroArchiveStore], sharing the save repository's major-version key
 * namespace so a schema break rotates both together.
 *
 * Quota behaviour matches §7: the store never deletes the archive to make room for anything else —
 * quota recovery drops previous-good campaign generations, which are reproducible, while the
 * archive is the only copy of a finished career there is.
 */
@Suppress("TooGenericExceptionCaught")
internal class LocalStorageHeroArchiveStore : HeroArchiveStore {
    private val majorVersion = VERSION.split(".").take(2).joinToString(".")
    private val key = "osada-hero-archive-$majorVersion"

    override fun read(): HeroArchive =
        try {
            HeroArchiveCodec.parseString(localStorage.getItem(key))
        } catch (e: Throwable) {
            // A corrupt or unavailable archive must not take the campaign register with it (§8):
            // valid campaign slots still populate the desk from their own live saves.
            console.warn("[osada] hero archive unavailable", e)
            HeroArchive.EMPTY
        }

    override fun replaceCampaign(archive: CampaignHeroArchive): SaveResult {
        val current = read()
        return write(current.copy(campaigns = current.campaigns + (archive.campaignRunId to archive)))
    }

    override fun deleteCampaign(campaignRunId: String): SaveResult {
        val current = read()
        if (campaignRunId !in current.campaigns) return SaveResult(SaveResultKind.NOT_FOUND, campaignRunId)
        return write(current.copy(campaigns = current.campaigns - campaignRunId))
    }

    override fun replaceAll(archive: HeroArchive): SaveResult = write(archive)

    private fun write(archive: HeroArchive): SaveResult =
        try {
            localStorage.setItem(key, HeroArchiveCodec.stringify(archive))
            SaveResult.success()
        } catch (e: Throwable) {
            val name = e.asDynamic()?.name as? String
            val kind =
                if (name == "QuotaExceededError") SaveResultKind.QUOTA_EXCEEDED else SaveResultKind.STORAGE_UNAVAILABLE
            console.error("[osada] hero archive write FAILED ($kind)", e)
            SaveResult(kind, e.message)
        }
}

/** In-memory [HeroArchiveStore] for tests and for a browser with no usable storage. */
internal class InMemoryHeroArchiveStore(
    private var archive: HeroArchive = HeroArchive.EMPTY,
) : HeroArchiveStore {
    override fun read(): HeroArchive = archive

    override fun replaceCampaign(archive: CampaignHeroArchive): SaveResult {
        this.archive = this.archive.copy(campaigns = this.archive.campaigns + (archive.campaignRunId to archive))
        return SaveResult.success()
    }

    override fun deleteCampaign(campaignRunId: String): SaveResult {
        if (campaignRunId !in archive.campaigns) return SaveResult(SaveResultKind.NOT_FOUND, campaignRunId)
        archive = archive.copy(campaigns = archive.campaigns - campaignRunId)
        return SaveResult.success()
    }

    override fun replaceAll(archive: HeroArchive): SaveResult {
        this.archive = archive
        return SaveResult.success()
    }
}
