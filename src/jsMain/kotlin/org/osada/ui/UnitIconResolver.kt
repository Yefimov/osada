package org.osada.ui

import org.osada.GameHolder
import kotlin.js.jsTypeOf

/**
 * Resolves an equipment ID and its default icon to the scenario-season variant generated from
 * OG's SnowIcon/DesertIcon/JungleIcon columns. Equipment ID is essential because unrelated OG
 * units can share one default icon while authoring different seasonal variants.
 */
internal object UnitIconResolver {
    fun forCurrentScenario(
        eqid: Int,
        baseIcon: String,
    ): String = resolve(eqid, baseIcon, GameHolder.instance?.scenario?.effectiveIconset ?: 0)

    fun resolve(
        eqid: Int,
        baseIcon: String,
        iconset: Int,
    ): String {
        var selected: String? = null
        if (iconset in SNOW_ICONSET..JUNGLE_ICONSET) {
            val registry: dynamic = js("typeof window !== 'undefined' ? window.seasonalUnitIcons : undefined")
            if (registry != null && jsTypeOf(registry) != "undefined") {
                val variants: dynamic = registry[eqid.toString()]
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
