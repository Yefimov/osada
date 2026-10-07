package org.osada

import kotlinx.browser.window
import org.osada.i18n.I18n
import org.osada.model.Equipment
import org.osada.model.Leaders
import org.osada.model.UnitDescriptions
import org.osada.rules.GameRules
import org.osada.save.ProfileWriterLock
import org.osada.ui.OtherTabNotice
import org.w3c.dom.events.Event

fun main() {
    console.log("[osada] bootstrap loaded")
    window.addEventListener("load", { _: Event ->
        console.log("[osada] window.load event")
        I18n.initialize {
            // The writer lock comes first: recovery and autosaves may only run in the tab that owns
            // the profile, or a second tab could roll back an import the first is still writing.
            // A write fenced out by another tab's claim reports the takeover even before the lock does.
            org.osada.storage.BrowserStorage.onProfileLost = OtherTabNotice::showLost
            ProfileWriterLock.acquire(
                onHolder = ::openProfile,
                onBusy = {
                    OtherTabNotice.showBusy {
                        ProfileWriterLock.takeOver(
                            ::openProfile,
                            OtherTabNotice::showLost,
                        )
                    }
                },
                onLost = OtherTabNotice::showLost,
            )
        }
    })
}

private var booted = false

/** Writer lock held: move the profile to its durable store, then recover and start the game. */
private fun openProfile() =
    org.osada.storage.BrowserStorage
        .openDurable(::bootGame)

private fun bootGame() {
    if (booted) return
    booted = true
    val importJournal = org.osada.save.LocalStorageBatch()
    val recovery = importJournal.recover()
    if (importJournal.mustBlockStartup(recovery)) {
        window.alert(I18n.t("save_load.import.recovery_failed"))
        return
    }
    if (!recovery.isSuccess) {
        console.warn("[osada] storage unreadable at startup; playing without saved data", recovery.message)
    }
    val game = Game()
    val globals = window.asDynamic()
    globals.game = game
    console.log("[osada] window.game set", game)
    globals.GameRules = GameRules
    globals.Equipment = Equipment
    globals.Leaders = Leaders
    globals.uiSettings = uiSettings
    globals.EventHandler = EventHandler
    globals.CombatLog = CombatLog
    globals.osadaProfileStorage = profileStorageProbe()
    globals.osadaCloudSync = cloudSyncProbe()
    UnitDescriptions.load()
    console.log("[osada] calling game.init()")
    org.osada.ui.music.MusicSession
        .initialize()
    org.osada.ui.music.MusicMiniPlayer
        .install()
    org.osada.ui.music.MusicLibraryPresenter
        .install()
    // A provider sign-in returns to `/#osada-account=...`; read it before anything rewrites the URL.
    org.osada.account.OnlineAccount
        .consumeRedirect()
    game.init()
    console.log("[osada] game.init() returned")
    org.osada.cloud.CloudSync
        .install()
    // Optional accounts never gate play: this only decides whether the Online saves row appears.
    org.osada.account.OnlineAccount.refresh {
        org.osada.ui.OnlineSavesPanel
            .refreshEntry()
        if (org.osada.account.OnlineAccount.pendingResult != null) {
            org.osada.ui.OnlineSavesPanel
                .open()
        }
    }
}

/** View for browser probes (writes obey the same lock/suspension rules): the profile no longer
 *  lives in `window.localStorage`. */
private fun profileStorageProbe(): dynamic {
    val storage = org.osada.storage.BrowserStorage
    val view: dynamic = js("({})")
    view.getItem = { key: String -> storage.getItem(key) }
    view.setItem = { key: String, value: String -> storage.setItem(key, value) }
    view.removeItem = { key: String -> storage.removeItem(key) }
    view.clear = { storage.clear() }
    view.keys = { (0 until storage.length).mapNotNull { storage.key(it) }.toTypedArray() }
    view.backend = { storage.profileBackend }
    view.whenDurable = { done: (Boolean) -> Unit -> storage.whenDurable(done) }
    return view
}

/** Read-only view of online-save sync for browser probes. */
private fun cloudSyncProbe(): dynamic {
    val sync = org.osada.cloud.CloudSync
    val view: dynamic = js("({})")
    view.status = { sync.status.name }
    view.decision = { sync.decision?.let { it::class.simpleName } }
    view.syncNow = { sync.reconcile(automatic = true) }
    view.capture = { done: (String?) -> Unit ->
        org.osada.cloud.LocalProfileCopy
            .capture { done(it?.body) }
    }
    return view
}
