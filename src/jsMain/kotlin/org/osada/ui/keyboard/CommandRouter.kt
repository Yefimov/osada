package org.osada.ui.keyboard

import kotlinx.browser.document
import org.osada.ui.AttachmentPickerPresenter
import org.osada.ui.CommanderRosterPresenter
import org.osada.ui.ConfirmCard
import org.osada.ui.HeroDeskPresenter
import org.osada.ui.HeroPromotionPresenter
import org.osada.ui.UI
import org.osada.ui.UIBuilder
import org.osada.ui.isScenarioBriefingVisible
import org.osada.ui.isVisible
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The single document-level keyboard router
 * (`docs/design/keyboard-shortcuts-and-help.md` §5). Installed once from `UI.init`.
 *
 * It owns Escape too. There must be exactly one document-level gameplay listener: two independent
 * ones both fire on the same press, which is how Escape once closed a window *underneath* a modal
 * (`DEFERRED.md` §4.13). Escape's ordered modal stack still lives in
 * `MainMenuButtonHandler.handleGlobalEscape`; this router only decides whether Escape reaches it.
 */
internal object CommandRouter {
    /** How much of the catalog is live right now. */
    enum class Scope {
        /** A modal owes the player a decision, or a component owns the keyboard: route nothing. */
        NONE,

        /** Escape only -- a blocking modal is up. */
        CLOSE_ONLY,

        /** Panel toggles and help; gameplay commands are suspended. */
        PANEL,

        /** The whole catalog. */
        GAMEPLAY,
    }

    private var installed = false

    fun install(ui: UI) {
        if (installed) return
        installed = true
        document.addEventListener("keydown", { event -> handle(event, ui) })
    }

    internal fun handle(
        event: Event,
        ui: UI,
    ) {
        val command = resolve(event) ?: return
        if (!allowed(command, scope())) return
        if (dispatch(command, ui)) event.preventDefault()
    }

    /** The catalog entry this press means, or `null` when the press is not ours: a text control or
     *  an IME owns it, no binding matches, or it is an auto-repeat of a non-repeatable command. */
    internal fun resolve(event: Event): GameCommand? {
        val raw = event.asDynamic()
        val suppressed = raw.isComposing == true || raw.keyCode == IME_KEY_CODE || isTextEntry(event.target)
        if (suppressed) return null
        val command =
            CommandCatalog.match(
                code = raw.code as? String,
                key = raw.key as? String,
                ctrl = raw.ctrlKey == true || raw.metaKey == true,
                alt = raw.altKey == true,
            )
        return command?.takeUnless { raw.repeat == true && !it.repeatable }
    }

    private fun dispatch(
        command: GameCommand,
        ui: UI,
    ): Boolean =
        when (command.id) {
            CommandCatalog.CLOSE -> closeTopmost(ui)
            CommandCatalog.HELP -> {
                ControlsCard.toggle()
                true
            }

            else -> CommandExecutor.run(command.id, ui)
        }

    /** The confirmation card carries its own Escape listener for the start-menu screens, where no
     *  gameplay router exists. Matching it here first stops the fall-through branch from opening the
     *  pause menu behind a dialog that is still on screen. */
    private fun closeTopmost(ui: UI): Boolean {
        when {
            ControlsCard.isOpen() -> ControlsCard.close()
            ConfirmCard.isOpen() -> ConfirmCard.close()
            else -> ui.mainMenuButtonHandler.handleGlobalEscape()
        }
        return true
    }

    internal fun allowed(
        command: GameCommand,
        scope: Scope,
    ): Boolean =
        when (scope) {
            Scope.NONE -> false
            Scope.CLOSE_ONLY -> command.id == CommandCatalog.CLOSE
            Scope.PANEL -> command.id in CommandCatalog.panelScopeIds
            Scope.GAMEPLAY -> true
        }

    /**
     * Layer precedence, highest first. The briefing and the promotion dialog own the keyboard
     * outright: the briefing runs its own focused navigation, and the promotion dialog has no
     * cancel path, so neither may leak a gameplay command or an Escape.
     */
    internal fun scope(): Scope =
        when {
            UIBuilder.isScenarioBriefingVisible() -> Scope.NONE
            HeroPromotionPresenter.isOpen() -> Scope.NONE
            ControlsCard.isOpen() -> Scope.CLOSE_ONLY
            ConfirmCard.isOpen() -> Scope.CLOSE_ONLY
            isVisible("ui-message") -> Scope.CLOSE_ONLY
            CommanderRosterPresenter.isTransferPickerOpen() -> Scope.CLOSE_ONLY
            AttachmentPickerPresenter.isOpen() -> Scope.CLOSE_ONLY
            CommanderRosterPresenter.isOpen() -> Scope.CLOSE_ONLY
            HeroDeskPresenter.isOpen() -> Scope.CLOSE_ONLY
            isVisible("startmenu") -> Scope.CLOSE_ONLY
            isVisible("equipment") -> Scope.PANEL
            else -> Scope.GAMEPLAY
        }

    /** Renaming inputs, the multiplayer room fields and any future text control keep their keys. */
    private fun isTextEntry(target: Any?): Boolean {
        val element = target as? HTMLElement ?: return false
        val tag = element.tagName.uppercase()
        return tag == "INPUT" ||
            tag == "TEXTAREA" ||
            tag == "SELECT" ||
            element.asDynamic().isContentEditable == true
    }

    /** `229` is the "processing key" every browser reports while an IME is mid-composition. */
    private const val IME_KEY_CODE = 229
}
