package org.osada.ui

/**
 * The small modal pickers that open over the map or the equipment window -- the attachment picker
 * and the hangar window. One entry for the Escape stack and the keyboard scope, so adding a picker
 * does not grow either of those already-long decisions.
 */
internal object ModalPickers {
    fun isOpen(): Boolean = AttachmentPickerPresenter.isOpen() || HangarDialog.isOpen()

    /** Closes the topmost one. */
    fun close() {
        if (HangarDialog.isOpen()) HangarDialog.close() else AttachmentPickerPresenter.close()
    }
}
