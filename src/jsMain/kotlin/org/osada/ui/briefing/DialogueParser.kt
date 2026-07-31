package org.osada.ui.briefing

import org.osada.campaign.CampaignConditionParser
import org.osada.campaign.CampaignEffectParser

/**
 * Parses the briefing dialogue tree (lines + branching choices). Split from [BriefingParser]
 * purely to keep that object within the project's function-count/complexity limits.
 */
internal object DialogueParser {
    fun parseDialogue(value: dynamic): List<BriefingLine> {
        val utils = BriefingParsingUtils
        if (!utils.isPresent(value) || !utils.isArray(value)) return emptyList()

        val result = mutableListOf<BriefingLine>()
        val usedIds = mutableSetOf<String>()
        val length = (value.length as? Int) ?: 0
        for (index in 0 until length) {
            val line = parseDialogueLine(value[index], index, usedIds) ?: continue
            result += line
        }
        return result
    }

    private fun parseDialogueLine(
        item: dynamic,
        index: Int,
        usedIds: MutableSet<String>,
    ): BriefingLine? {
        val utils = BriefingParsingUtils
        val isObject = utils.isObject(item)
        val speaker =
            if (isObject) utils.readFirstString(item.speaker, item.name, item.character)?.trim().orEmpty() else ""
        val text = if (isObject) utils.readFirstString(item.text, item.message, item.body)?.trim().orEmpty() else ""
        if (!isObject || speaker.isBlank() || text.isBlank()) return null

        val requestedId = utils.readString(item.id)?.trim()?.takeIf { it.isNotBlank() }
        val id = resolveLineId(requestedId, index, usedIds)
        val side = utils.readString(item.side)?.lowercase().let { if (it == "right") "right" else "left" }

        return BriefingLine(
            id = id,
            speaker = speaker,
            role = utils.readFirstString(item.role, item.rank, item.title)?.trim().orEmpty(),
            text = text,
            portrait = utils.readAssetPath(item.portrait ?: item.image),
            side = side,
            initials = utils.initialsFor(speaker),
            next = utils.readString(item.next ?: item.nextId)?.trim()?.takeIf { it.isNotBlank() },
            choices = parseChoices(item.choices ?: item.responses ?: item.options, id),
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
    ): List<BriefingChoice> {
        val utils = BriefingParsingUtils
        if (!utils.isPresent(value) || !utils.isArray(value)) return emptyList()

        val result = mutableListOf<BriefingChoice>()
        val length = (value.length as? Int) ?: 0
        for (index in 0 until length) {
            val choice = parseChoiceItem(value[index], lineId, index) ?: continue
            result += choice
        }
        return result
    }

    private fun parseChoiceItem(
        item: dynamic,
        lineId: String,
        index: Int,
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
        return BriefingChoice(id = id, text = text, next = next, effects = effects, hint = hint)
    }
}
