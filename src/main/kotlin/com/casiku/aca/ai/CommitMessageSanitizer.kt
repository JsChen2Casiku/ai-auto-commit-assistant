package com.casiku.aca.ai

object CommitMessageSanitizer {
    private val thinkOpenTags = listOf("<think>", "<thinking>", "<reasoning>")
    private val thinkCloseTags = listOf("</think>", "</thinking>", "</reasoning>")
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

        return text
            .replace(Regex("(?im)^\\s*(analysis|reasoning|thought process|思考过程|分析)\\s*[:：].*$"), "")
            .replace(Regex("(?im)^\\s*(the user wants me to|let me analyze|i need to|we need to).*$"), "")
            .trim()
    }

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
}
