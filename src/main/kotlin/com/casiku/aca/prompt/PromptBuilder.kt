package com.casiku.aca.prompt

import com.casiku.aca.diff.DiffContext
import com.casiku.aca.settings.AiCommitSettingsState
import com.casiku.aca.settings.PromptStyle

data class CommitPrompt(
    val system: String,
    val user: String,
)

object PromptBuilder {
    fun build(settings: AiCommitSettingsState.StateData, diffContext: DiffContext): CommitPrompt {
        val style = PromptStyle.entries.firstOrNull { it.name == settings.promptStyle } ?: PromptStyle.CONVENTIONAL
        val language = settings.language.ifBlank { "中文" }
        val customPrompt = settings.customPrompt.trim()

        val styleInstruction = when (style) {
            PromptStyle.CONVENTIONAL -> "Use Conventional Commits format, such as feat:, fix:, refactor:, docs:, test:, chore:. Keep the subject concise."
            PromptStyle.SIMPLE -> "Write one concise commit message subject. Do not include explanations."
            PromptStyle.DETAILED -> "Write a concise subject and 2-4 bullet points explaining the main changes."
            PromptStyle.CUSTOM -> customPrompt.ifBlank {
                "Write a clear commit message. Output only the commit message."
            }
        }

        val thinkingRule = if (settings.thinkingFilter) {
            """
            Never output hidden reasoning, analysis, thought process, or <think> blocks.
            Never include phrases like "Let me analyze" or "The user wants me to".
            """.trimIndent()
        } else {
            ""
        }

        val outputContract = if (settings.thinkingFilter) {
            "Output contract: return only the commit message text. No reasoning. No explanations. No <think> tags."
        } else {
            "Output contract: return the generated commit message."
        }

        val system = """
            You generate Git commit messages from code diffs.
            Output only the final commit message.
            $thinkingRule
            Do not wrap the answer in Markdown.
            Do not invent changes that are not present in the diff.
        """.trimIndent()

        val user = """
            Language: $language
            Style: $styleInstruction
            $outputContract
            Number of selected changes: ${diffContext.changeCount}
            Diff truncated: ${diffContext.truncated}
            Model context tokens: ${diffContext.contextTokens}
            Diff token budget: ${diffContext.diffTokenBudget}

            Changed files:
            ${diffContext.fileSummary}

            Unified diff:
            ${diffContext.diffText}
        """.trimIndent()

        return CommitPrompt(system = system, user = user)
    }
}
