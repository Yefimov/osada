package org.osada.ui.briefing

import org.osada.campaign.CampaignConditionParser
import org.osada.campaign.CampaignEffectParser

/**
 * Parses the briefing dialogue tree (lines + branching choices). Split from [BriefingParser]
 * purely to keep that object within the project's function-count/complexity limits.
 */
internal object DialogueParser {
    fun parseDialogue(
        value: dynamic,
        textResolver: BriefingTextResolver = BriefingLocalization.sourceTextResolver(),
    ): List<BriefingLine> {
        val utils = BriefingParsingUtils
        if (!utils.isPresent(value) || !utils.isArray(value)) return emptyList()

        val result = mutableListOf<BriefingLine>()
        val usedIds = mutableSetOf<String>()
        val length = (value.length as? Int) ?: 0
        for (index in 0 until length) {
            val line = parseDialogueLine(value[index], index, usedIds, textResolver) ?: continue
            result += line
        }
        return result
    }

    private fun parseDialogueLine(
        item: dynamic,
        index: Int,
        usedIds: MutableSet<String>,
        textResolver: BriefingTextResolver,
    ): BriefingLine? {
        val utils = BriefingParsingUtils
        val isObject = utils.isObject(item)
        val sourceSpeaker =
            if (isObject) utils.readFirstString(item.speaker, item.name, item.character)?.trim().orEmpty() else ""
        val sourceText =
            if (isObject) utils.readFirstString(item.text, item.message, item.body)?.trim().orEmpty() else ""
        if (!isObject || sourceSpeaker.isBlank() || sourceText.isBlank()) return null

        val requestedId = utils.readString(item.id)?.trim()?.takeIf { it.isNotBlank() }
        val id = resolveLineId(requestedId, index, usedIds)
        val side = utils.readString(item.side)?.lowercase().let { if (it == "right") "right" else "left" }
        val speaker = textResolver.resolve("line.$id.speaker", sourceSpeaker)
        val role = utils.readFirstString(item.role, item.rank, item.title)?.trim().orEmpty()

        return BriefingLine(
            id = id,
            speaker = speaker,
            role = textResolver.resolve("line.$id.role", role),
            text = textResolver.resolve("line.$id.text", sourceText),
            portrait = utils.readAssetPath(item.portrait ?: item.image),
            side = side,
            initials = utils.initialsFor(speaker),
            next = utils.readString(item.next ?: item.nextId)?.trim()?.takeIf { it.isNotBlank() },
            choices = parseChoices(item.choices ?: item.responses ?: item.options, id, textResolver),
            condition = CampaignConditionParser.parse(item.conditions ?: item.condition),
        )
    }

    private fun resolveLineId(
        requestedId: String?,
        index: Int,
        usedIds: MutableSet<String>,
    ): String {
        val fallback = "line-${index + 1}"
        var id = requestedId ?: fallback
        if (!usedIds.add(id)) {
            id = fallback
            usedIds.add(id)
        }
        return id
    }

    private fun parseChoices(
        value: dynamic,
        lineId: String,
        textResolver: BriefingTextResolver,
    ): List<BriefingChoice> {
        val utils = BriefingParsingUtils
        if (!utils.isPresent(value) || !utils.isArray(value)) return emptyList()

        val result = mutableListOf<BriefingChoice>()
        val length = (value.length as? Int) ?: 0
        for (index in 0 until length) {
            val choice = parseChoiceItem(value[index], lineId, index, textResolver) ?: continue
            result += choice
        }
        return result
    }

    private fun parseChoiceItem(
        item: dynamic,
        lineId: String,
        index: Int,
        textResolver: BriefingTextResolver,
    ): BriefingChoice? {
        val utils = BriefingParsingUtils
        val isObject = utils.isObject(item)
        val text =
            (
                utils.readString(item)
                    ?: if (isObject) utils.readFirstString(item.text, item.label, item.response) else null
            )?.trim().orEmpty()
        if (text.isBlank()) return null

        val id =
            (if (isObject) utils.readString(item.id)?.trim()?.takeIf { it.isNotBlank() } else null)
                ?: "$lineId-choice-${index + 1}"
        val next =
            if (isObject) {
                utils
                    .readString(
                        item.next ?: item.nextId,
                    )?.trim()
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        val effects = if (isObject) CampaignEffectParser.parseList(item.effects) else emptyList()
        val hint = (if (isObject) utils.readString(item.hint)?.trim() else null).orEmpty()
        val prefix = "line.$lineId.choice.$id"
        return BriefingChoice(
            id = id,
            text = textResolver.resolve("$prefix.text", text),
            next = next,
            effects = effects,
            hint = textResolver.resolve("$prefix.hint", hint),
        )
    }
}
