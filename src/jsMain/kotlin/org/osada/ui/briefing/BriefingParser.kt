package org.osada.ui.briefing

/**
 * Converts loose campaign JavaScript objects into typed, display-safe briefing data. Dialogue
 * parsing lives in [DialogueParser]; shared dynamic-value reading primitives live in
 * [BriefingParsingUtils].
 */
internal object BriefingParser {
    /** Never throws: malformed campaign data logs a warning and degrades to an empty briefing
     *  (title/act/location only) rather than crashing the scenario launch. */
    @Suppress("TooGenericExceptionCaught")
    fun parse(
        scenarioTitle: String,
        rawData: dynamic,
        textResolver: BriefingTextResolver = BriefingLocalization.sourceTextResolver(),
    ): ScenarioBriefing =
        try {
            parseUnsafe(scenarioTitle, rawData, textResolver)
        } catch (e: Throwable) {
            console.warn("[OSADA] briefing parse failed, falling back to minimal briefing", e)
            ScenarioBriefing(
                title = scenarioTitle,
                actLabel = "CAMPAIGN OPERATION",
                locationLabel = scenarioTitle,
                background = null,
                dialogue = emptyList(),
                player = parsePlayer(null, textResolver),
                orders = BriefingOrders(),
            )
        }

    private fun parseUnsafe(
        scenarioTitle: String,
        rawData: dynamic,
        textResolver: BriefingTextResolver,
    ): ScenarioBriefing {
        val utils = BriefingParsingUtils
        val root = utils.unwrapBriefing(rawData)
        val isObject = utils.isObject(root)
        val dialogue = DialogueParser.parseDialogue(resolveDialogueSource(root), textResolver)

        return ScenarioBriefing(
            title = scenarioTitle,
            actLabel =
                textResolver.resolve(
                    "header.act",
                    if (isObject) resolveActLabel(root) else "CAMPAIGN OPERATION",
                ),
            locationLabel =
                textResolver.resolve(
                    "header.location",
                    if (isObject) resolveLocationLabel(root, scenarioTitle) else scenarioTitle,
                ),
            background = if (isObject) utils.readAssetPath(root.background) else null,
            dialogue = dialogue,
            player = parsePlayer(if (isObject) root.player ?: root.commander ?: root.responder else null, textResolver),
            orders = parseOrders(resolveOrderSource(root)),
        )
    }

    private fun resolveDialogueSource(root: dynamic): dynamic {
        val utils = BriefingParsingUtils
        return when {
            utils.isArray(root) -> root
            utils.isPresent(root?.dialogue) -> root.dialogue
            utils.isPresent(root?.lines) -> root.lines
            else -> null
        }
    }

    private fun resolveOrderSource(root: dynamic): dynamic {
        val utils = BriefingParsingUtils
        return when {
            root == null || root == undefined || utils.isArray(root) -> null
            utils.isPresent(root.orders) -> root.orders
            else -> root
        }
    }

    private fun resolveActLabel(root: dynamic): String =
        BriefingParsingUtils.readString(root.act)?.trim()
            ?: BriefingParsingUtils.readString(root.chapter)?.trim()
            ?: "CAMPAIGN OPERATION"

    private fun resolveLocationLabel(
        root: dynamic,
        scenarioTitle: String,
    ): String = BriefingParsingUtils.readString(root.location)?.trim()?.takeIf { it.isNotBlank() } ?: scenarioTitle

    private fun parsePlayer(
        value: dynamic,
        textResolver: BriefingTextResolver,
    ): BriefingParticipant {
        val utils = BriefingParsingUtils
        val isObject = utils.isObject(value)
        val speaker =
            if (isObject) {
                utils.readFirstString(value.speaker, value.name, value.character) ?: "Commander"
            } else {
                "Commander"
            }.trim().ifBlank { "Commander" }

        val role =
            if (isObject) {
                utils.readFirstString(value.role, value.rank, value.title) ?: "Field Commander"
            } else {
                "Field Commander"
            }.trim()

        val side = if (isObject && utils.readString(value.side)?.lowercase() == "right") "right" else "left"
        val localizedSpeaker = textResolver.resolve("player.speaker", speaker)
        return BriefingParticipant(
            speaker = localizedSpeaker,
            role = textResolver.resolve("player.role", role),
            portrait = if (isObject) utils.readAssetPath(value.portrait ?: value.image) else null,
            side = side,
            initials = utils.initialsFor(localizedSpeaker),
        )
    }

    private fun parseOrders(value: dynamic): BriefingOrders {
        val utils = BriefingParsingUtils
        if (!utils.isObject(value)) return BriefingOrders()
        return BriefingOrders(
            situation = utils.readString(value.situation).orEmpty().trim(),
            mission = utils.readString(value.mission).orEmpty().trim(),
            primaryObjectives = utils.readStringList(value.primaryObjectives ?: value.objectives ?: value.primary),
            secondaryObjectives = utils.readStringList(value.secondaryObjectives ?: value.secondary),
            enemyIntelligence =
                utils
                    .readFirstString(value.enemyIntelligence, value.intelligence, value.enemy)
                    .orEmpty()
                    .trim(),
            availableSupport =
                utils
                    .readFirstString(value.availableSupport, value.friendlyForces, value.support)
                    .orEmpty()
                    .trim(),
            notes =
                utils
                    .readFirstString(value.notes, value.additionalNotes, value.briefingText)
                    .orEmpty()
                    .trim(),
        )
    }
}
