package org.osada.ui

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.Promise

/**
 * Renders a stored layered portrait (design brief §15) into a container by fetching each v2 layer
 * SVG and stacking them inline. Inlining (rather than `<img>`) lets the layers share one coordinate
 * space and pick up the palette variables set here, so skin and hair recolor per hero without
 * touching the assets.
 *
 * Async and best-effort: layers load in the background and replace whatever placeholder the caller
 * drew; any fetch error simply leaves that placeholder in place. Fetched text is cached, so opening
 * several dossiers does not re-download shared layers.
 */
internal object PortraitRenderer {
    private val cache = mutableMapOf<String, String>()
    private val SKINS = listOf("#e7c39c", "#dcae86", "#c99a70", "#b07d55")
    private val HAIRS = listOf("#33281d", "#4a3a2b", "#5f4a30", "#7a5c38")
    private const val GRAY = "#b9b2a3"

    fun render(
        container: HTMLElement?,
        paths: List<String>,
        seed: Int,
        gray: Boolean = false,
        artPath: String? = null,
    ) {
        if (container == null) return
        when {
            artPath != null -> renderPainted(container, artPath, gray)
            paths.isNotEmpty() -> renderStack(container, paths, seed, gray)
        }
    }

    private fun renderStack(
        container: HTMLElement,
        paths: List<String>,
        seed: Int,
        gray: Boolean,
    ) {
        val skin = SKINS[posMod(seed, SKINS.size)]
        container.classList.add("osada-portrait-stack")
        container.style.setProperty("--skin", skin)
        container.style.setProperty("--skin-shadow", shade(skin, if (gray) GRAY_SHADE else NORMAL_SHADE))
        container.style.setProperty("--hair", if (gray) GRAY else HAIRS[posMod(seed shr 2, HAIRS.size)])
        // Portrait recipes are already rooted at `portraits/...`. Unlike gameplay assets they
        // are copied to the distribution root, not under `/resources`, so prefixing that segment
        // produced a successful HTTP response containing Express's "Cannot GET" page and then
        // inlined that text into the portrait.
        val urls = paths
        Promise
            .all(urls.map(::fetchSvg).toTypedArray())
            .then { texts ->
                container.innerHTML =
                    texts.joinToString("") { "<span class=\"osada-portrait-layer\">$it</span>" }
            }.catch { /* keep the caller's placeholder on any fetch failure */ }
    }

    /**
     * An authored hero's painted portrait (§6.6/6a). Set as a background rather than fetched and
     * inlined: it is one raster asset, so there is nothing to recolor, no layer order to honour and
     * no partial-stack failure mode — if the file 404s the caller's monogram is simply never covered.
     */
    private fun renderPainted(
        container: HTMLElement,
        artPath: String,
        gray: Boolean,
    ) {
        container.textContent = ""
        container.classList.add("osada-portrait-photo")
        container.classList.toggle("osada-portrait-photo--memoriam", gray)
        container.style.backgroundImage = "url('$artPath')"
    }

    private fun fetchSvg(url: String): Promise<String> {
        val cached = cache[url]
        if (cached != null) return Promise { resolve, _ -> resolve(cached) }
        return window
            .fetch(url)
            .then { response ->
                if (!response.ok) throw IllegalStateException("Portrait layer unavailable: $url")
                response.text()
            }.then { text ->
                cache[url] = text
                text
            }
    }

    private fun posMod(
        a: Int,
        m: Int,
    ): Int = ((a % m) + m) % m

    private fun shade(
        hex: String,
        factor: Double,
    ): String {
        val n = hex.removePrefix("#").toInt(HEX_RADIX)
        val r = (((n shr RED_SHIFT) and CHANNEL) * factor).toInt()
        val g = (((n shr GREEN_SHIFT) and CHANNEL) * factor).toInt()
        val b = ((n and CHANNEL) * factor).toInt()
        return "#" + ((1 shl COLOR_BITS) + (r shl RED_SHIFT) + (g shl GREEN_SHIFT) + b).toString(HEX_RADIX).substring(1)
    }

    private const val GRAY_SHADE = 0.92
    private const val NORMAL_SHADE = 0.85
    private const val HEX_RADIX = 16
    private const val CHANNEL = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val COLOR_BITS = 24
}
