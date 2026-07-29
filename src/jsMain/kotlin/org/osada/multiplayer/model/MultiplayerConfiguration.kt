@file:Suppress("UnusedParameter")

package org.osada.multiplayer.model

data class MultiplayerEndpointConfig(
    val environment: String,
    val httpBaseUrl: String,
    val webSocketBaseUrl: String,
)

data class ContentManifest(
    val manifestVersion: Int,
    val gameBuild: String,
    val rulesHash: String,
    val scenarioHash: String,
    val campaignHash: String,
    val equipmentHash: String,
)

data class ContentCompatibility(
    val compatible: Boolean,
    val mismatchedFields: Set<String>,
)

class ContentManifestService(
    private val rulesHash: String = "",
    private val campaignHash: String = "",
    private val equipmentHash: String = "",
) {
    fun build(
        contentRef: MultiplayerContentRef,
        gameBuild: String,
    ): ContentManifest =
        ContentManifest(
            manifestVersion = 1,
            gameBuild = gameBuild,
            rulesHash = rulesHash,
            scenarioHash = if (contentRef.kind == MultiplayerContentKind.SCENARIO) contentRef.contentId else "",
            campaignHash =
                if (contentRef.kind == MultiplayerContentKind.CAMPAIGN) {
                    contentRef.contentId
                } else {
                    campaignHash
                },
            equipmentHash = equipmentHash,
        )

    fun compare(
        local: ContentManifest,
        required: ContentManifest,
    ): ContentCompatibility {
        val mismatches = mutableSetOf<String>()
        if (local.manifestVersion != required.manifestVersion) mismatches += "manifestVersion"
        if (local.gameBuild != required.gameBuild) mismatches += "gameBuild"
        if (local.rulesHash != required.rulesHash) mismatches += "rulesHash"
        if (local.scenarioHash != required.scenarioHash) mismatches += "scenarioHash"
        if (local.campaignHash != required.campaignHash) mismatches += "campaignHash"
        if (local.equipmentHash != required.equipmentHash) mismatches += "equipmentHash"
        return ContentCompatibility(mismatches.isEmpty(), mismatches)
    }
}
