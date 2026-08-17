package org.osada

import org.osada.OSGlue.diskloadHTML

object OSGlue {
    var diskloadHTML: String = ""

    /** Just the hidden `<input type=file>` (desktop) or empty (mobile, where load goes through
     *  the native `osada://` scheme on click). Lets the Save/Load screen compose its own
     *  button markup instead of embedding the input mid-text like [diskloadHTML] does — that
     *  inline input was what broke the button's text flow ("LOAD FROM" / "DISK" fragments). */
    var diskloadInputHTML: String = ""
    var canvasErrorMsg: String = ""

    init {
        if (NATIVE_PLATFORM == "ios" || NATIVE_PLATFORM == "android") {
            diskloadHTML = "Load from Disk"
            diskloadInputHTML = ""
            canvasErrorMsg =
                "<b>Couldn't create game surface.</b> <br/>This usually means that your device doesn't " +
                "allow game surface to be created with dimensions over a certain limit."
        } else {
            diskloadHTML = "Load from Disk <input id='diskloadfile' type='file'/>"
            diskloadInputHTML = "<input id='diskloadfile' type='file'/>"
            canvasErrorMsg = "Couldn't load map image !"
        }
    }

    fun diskloadEvent(
        element: dynamic,
        callback: () -> Unit,
    ) {
        if (NATIVE_PLATFORM == "ios" || NATIVE_PLATFORM == "android") {
            element.addEventListener("click", callback)
        } else {
            element.addEventListener("change", callback)
        }
    }

    fun diskload() {
        if (NATIVE_PLATFORM == "ios" || NATIVE_PLATFORM == "android") {
            js("window.location = 'osada://loadfromdisk'")
        }
    }

    fun diskload(
        onSuccess: () -> Unit,
        onError: () -> Unit,
    ) {
        if (NATIVE_PLATFORM == "ios" || NATIVE_PLATFORM == "android") {
            diskload()
        } else {
            val files = js("document.getElementById('diskloadfile').files")
            if (files.length > 0) {
                GameHolder.instance?.state?.restoreFromFile(files[0], onSuccess, onError)
                js("document.getElementById('diskloadfile').value = ''")
            }
        }
    }

    fun disksave(fileName: String) {
        if (NATIVE_PLATFORM == "ios" || NATIVE_PLATFORM == "android") {
            js("window.location = 'osada://savetodisk/' + fileName")
        } else {
            val data = GameHolder.instance?.state?.exportGameState() ?: ""
            val element = js("document.getElementById('savedata')")
            element.download = fileName
            element.href = "data:application/force-download," + encodeURIComponent(data)
            element.click()
            js("document.getElementById('disksaveupdate').innerHTML = fileName")
        }
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER", "EmptyFunctionBlock")
    fun reportScore(score: Int) {
        // No-op stub on every NATIVE_PLATFORM in the legacy JS too (openpanzer.js's own
        // `ios`/`generic` OSGlue variants both define this as empty `function(a){}`) -- kept
        // with its parameter so the Game.kt call site (which mirrors the original hook) stays
        // meaningful if a real platform integration is ever added.
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER", "EmptyFunctionBlock")
    fun reportAchievement(achievement: String) {
        // No-op stub on every NATIVE_PLATFORM in the legacy JS too (openpanzer.js's own
        // `ios`/`generic` OSGlue variants both define this as empty `function(a){}`) -- kept
        // with its parameter so the Game.kt call site (which mirrors the original hook) stays
        // meaningful if a real platform integration is ever added.
    }
}

// A real function parameter (not a bare identifier inside a js("...") string) so this doesn't
// depend on the Kotlin/JS compiler preserving a local variable's exact name in the emitted JS.
private fun encodeURIComponent(value: String): String = js("encodeURIComponent")(value) as String
