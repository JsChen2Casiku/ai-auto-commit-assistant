package com.casiku.aca.ai

object CommitMessageSanitizer {
    private val thinkOpenTags = listOf("<think>", "<thinking>", "<reasoning>")
    private val thinkCloseTags = listOf("</think>", "</thinking>", "</reasoning>")
    private val gitmojiShortcodes = mapOf(
        ":art:" to "🎨",
        ":zap:" to "⚡️",
        ":fire:" to "🔥",
        ":bug:" to "🐛",
        ":ambulance:" to "🚑️",
        ":sparkles:" to "✨",
        ":memo:" to "📝",
        ":rocket:" to "🚀",
        ":lipstick:" to "💄",
        ":tada:" to "🎉",
        ":white_check_mark:" to "✅",
        ":lock:" to "🔒️",
        ":closed_lock_with_key:" to "🔐",
        ":bookmark:" to "🔖",
        ":rotating_light:" to "🚨",
        ":construction:" to "🚧",
        ":green_heart:" to "💚",
        ":arrow_down:" to "⬇️",
        ":arrow_up:" to "⬆️",
        ":pushpin:" to "📌",
        ":construction_worker:" to "👷",
        ":chart_with_upwards_trend:" to "📈",
        ":recycle:" to "♻️",
        ":heavy_plus_sign:" to "➕",
        ":heavy_minus_sign:" to "➖",
        ":wrench:" to "🔧",
        ":hammer:" to "🔨",
        ":globe_with_meridians:" to "🌐",
        ":pencil2:" to "✏️",
        ":poop:" to "💩",
        ":rewind:" to "⏪️",
        ":twisted_rightwards_arrows:" to "🔀",
        ":package:" to "📦️",
        ":alien:" to "👽️",
        ":truck:" to "🚚",
        ":page_facing_up:" to "📄",
        ":boom:" to "💥",
        ":bento:" to "🍱",
        ":wheelchair:" to "♿️",
        ":bulb:" to "💡",
        ":beers:" to "🍻",
        ":speech_balloon:" to "💬",
        ":card_file_box:" to "🗃️",
        ":loud_sound:" to "🔊",
        ":mute:" to "🔇",
        ":busts_in_silhouette:" to "👥",
        ":children_crossing:" to "🚸",
        ":building_construction:" to "🏗️",
        ":iphone:" to "📱",
        ":clown_face:" to "🤡",
        ":egg:" to "🥚",
        ":see_no_evil:" to "🙈",
        ":camera_flash:" to "📸",
        ":alembic:" to "⚗️",
        ":mag:" to "🔍️",
        ":label:" to "🏷️",
        ":seedling:" to "🌱",
        ":triangular_flag_on_post:" to "🚩",
        ":goal_net:" to "🥅",
        ":dizzy:" to "💫",
        ":wastebasket:" to "🗑️",
        ":passport_control:" to "🛂",
        ":adhesive_bandage:" to "🩹",
        ":monocle_face:" to "🧐",
        ":coffin:" to "⚰️",
        ":test_tube:" to "🧪",
        ":necktie:" to "👔",
        ":stethoscope:" to "🩺",
        ":bricks:" to "🧱",
        ":technologist:" to "🧑‍💻",
        ":money_with_wings:" to "💸",
        ":thread:" to "🧵",
        ":safety_vest:" to "🦺",
    )
    private val partialThinkTagPrefixes = buildSet {
        (thinkOpenTags + thinkCloseTags).forEach { tag ->
            for (length in 1 until tag.length) {
                add(tag.take(length))
            }
        }
    }.sortedByDescending { it.length }
    private val reasoningStartRegex = Regex(
        "(?im)^\\s*(the user wants me to|let me analyze|i need to|we need to|analysis\\s*[:：]|reasoning\\s*[:：]|thought process\\s*[:：]|思考过程\\s*[:：]|分析\\s*[:：])"
    )

    fun sanitize(raw: String): String {
        var text = raw
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<thinking>.*?</thinking>"), "")
            .replace(Regex("(?is)<reasoning>.*?</reasoning>"), "")

        text = removeUnclosedTag(text, "<think>")
        text = removeUnclosedTag(text, "<thinking>")
        text = removeUnclosedTag(text, "<reasoning>")

        return normalizeGitmojiShortcodes(text)
            .replace(Regex("(?im)^\\s*(analysis|reasoning|thought process|思考过程|分析)\\s*[:：].*$"), "")
            .replace(Regex("(?im)^\\s*(the user wants me to|let me analyze|i need to|we need to).*$"), "")
            .trim()
    }

    fun normalize(raw: String): String =
        normalizeGitmojiShortcodes(raw).trim()

    fun sanitizePreview(raw: String): String {
        val safeRaw = removeTrailingPartialThinkTag(raw)
        val lower = safeRaw.lowercase()
        val openThinkIndex = thinkOpenTags
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        val closeThinkIndex = thinkCloseTags
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()

        if (openThinkIndex != null && (closeThinkIndex == null || closeThinkIndex < openThinkIndex)) {
            return sanitize(safeRaw.take(openThinkIndex))
        }
        val reasoningStart = reasoningStartRegex.find(safeRaw)?.range?.first
        if (reasoningStart != null) {
            return sanitize(safeRaw.take(reasoningStart))
        }
        return sanitize(safeRaw)
    }

    fun containsReasoning(raw: String): Boolean {
        val lower = raw.lowercase()
        return thinkOpenTags.any { lower.contains(it) } ||
            partialThinkTagPrefixes.any { lower.endsWith(it) } ||
            reasoningStartRegex.containsMatchIn(raw)
    }

    private fun removeUnclosedTag(text: String, tag: String): String {
        val index = text.lowercase().indexOf(tag)
        return if (index >= 0) text.take(index) else text
    }

    private fun removeTrailingPartialThinkTag(text: String): String {
        val lower = text.lowercase()
        val partial = partialThinkTagPrefixes.firstOrNull { lower.endsWith(it) }
        return if (partial == null) text else text.dropLast(partial.length)
    }

    private fun normalizeGitmojiShortcodes(raw: String): String {
        var text = raw
        gitmojiShortcodes.forEach { (shortcode, emoji) ->
            text = text.replace(shortcode, emoji, ignoreCase = true)
        }
        return text
    }
}
