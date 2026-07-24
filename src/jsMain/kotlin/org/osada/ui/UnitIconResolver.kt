package org.osada.ui

import org.osada.GameHolder
import kotlin.js.jsTypeOf

/**
 * Resolves an equipment's stable/default icon to the scenario-climate variant generated from OG's
 * SnowIcon/DesertIcon/JungleIcon columns. The manifest is loaded before `osada.js`; absent entries
 * deliberately fall back to the original icon, preserving stock PM equipment and incomplete OG
 * variant sets.
 */
internal object UnitIconResolver {
    fun forCurrentScenario(baseIcon: String): String = resolve(baseIcon, GameHolder.instance?.scenario?.iconset ?: 0)

    fun resolve(
        baseIcon: String,
        iconset: Int,
    ): String {
        var selected: String? = null
        if (iconset in SNOW_ICONSET..JUNGLE_ICONSET) {
            val registry: dynamic = js("typeof window !== 'undefined' ? window.seasonalUnitIcons : undefined")
            if (registry != null && jsTypeOf(registry) != "undefined") {
                val variants: dynamic = registry[baseIcon]
                if (variants != null && jsTypeOf(variants) != "undefined") {
                    selected = variants[iconset.toString()] as? String
                }
            }
        }
        return chooseSeasonalUnitIcon(baseIcon, selected)
    }

    private const val SNOW_ICONSET = 1
    private const val JUNGLE_ICONSET = 3
}

internal fun chooseSeasonalUnitIcon(
    baseIcon: String,
    selectedVariant: String?,
): String = selectedVariant?.takeIf { it.isNotBlank() } ?: baseIcon
