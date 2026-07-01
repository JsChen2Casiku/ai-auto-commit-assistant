package com.casiku.aca.diff

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import java.nio.file.Files

data class DiffContext(
    val fileSummary: String,
    val diffText: String,
    val changeCount: Int,
    val truncated: Boolean,
    val contextTokens: Int,
    val diffTokenBudget: Int,
)

object CommitDiffCollector {
    fun collect(
        project: Project,
        changes: List<Change>,
        unversionedFiles: List<FilePath>,
        contextTokens: Int,
        indicator: ProgressIndicator,
    ): DiffContext {
        indicator.checkCanceled()
        val fileSummary = buildFileSummary(changes, unversionedFiles)
        val diff = buildUnifiedDiff(project, changes, unversionedFiles, indicator)
        val safeContextTokens = contextTokens.coerceAtLeast(4_096)
        val diffTokenBudget = TokenBudgetCalculator.diffTokenBudget(safeContextTokens, fileSummary)
        val truncated = TextTokenEstimator.estimateTokens(diff) > diffTokenBudget
        val cappedDiff = if (truncated) {
            val capped = TextTokenEstimator.truncateToTokenBudget(diff, diffTokenBudget)
            "$capped\n\n[Diff truncated after about $diffTokenBudget tokens because the model context is $safeContextTokens tokens]"
        } else {
            diff
        }

        return DiffContext(
            fileSummary = fileSummary,
            diffText = cappedDiff,
            changeCount = changes.size + unversionedFiles.size,
            truncated = truncated,
            contextTokens = safeContextTokens,
            diffTokenBudget = diffTokenBudget,
        )
    }

    private fun buildUnifiedDiff(
        project: Project,
        changes: List<Change>,
        unversionedFiles: List<FilePath>,
        indicator: ProgressIndicator,
    ): String =
        buildString {
            changes.forEach { change ->
                indicator.checkCanceled()
                appendChange(change)
                append('\n')
            }
            unversionedFiles.forEach { file ->
                indicator.checkCanceled()
                appendUnversionedFile(project, file)
                append('\n')
            }
        }

    private fun buildFileSummary(changes: List<Change>, unversionedFiles: List<FilePath>): String =
        buildList {
            addAll(changes.map { change ->
                val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                "- ${change.type.name}: $path"
            })
            addAll(unversionedFiles.map { file ->
                "- UNVERSIONED: ${file.path}"
            })
        }.joinToString(separator = "\n")

    private fun StringBuilder.appendUnversionedFile(project: Project, file: FilePath) {
        val path = file.path
        appendLine("diff -- UNVERSIONED /dev/null -> $path")
        appendLine("--- /dev/null")
        appendLine("+++ $path")

        if (file.isDirectory) {
            appendLine("[Directory content is not included]")
            return
        }

        val content = safeFileContent(project, file)
        if (content == null) {
            appendLine("[Binary or unavailable content]")
            return
        }
        appendAdded(content)
    }

    private fun StringBuilder.appendChange(change: Change) {
        val beforePath = change.beforeRevision?.file?.path ?: "/dev/null"
        val afterPath = change.afterRevision?.file?.path ?: "/dev/null"
        appendLine("diff -- ${change.type.name} $beforePath -> $afterPath")
        appendLine("--- $beforePath")
        appendLine("+++ $afterPath")

        val before = safeContent(change.beforeRevision)
        val after = safeContent(change.afterRevision)
        if (before == null && after == null) {
            appendLine("[Binary or unavailable content]")
            return
        }

        when (change.type) {
            Change.Type.NEW -> appendAdded(after.orEmpty())
            Change.Type.DELETED -> appendRemoved(before.orEmpty())
            else -> appendModified(before.orEmpty(), after.orEmpty())
        }
    }

    private fun safeContent(revision: ContentRevision?): String? =
        try {
            revision?.content
        } catch (_: Throwable) {
            null
        }

    private fun safeFileContent(project: Project, file: FilePath): String? =
        try {
            val bytes = Files.readAllBytes(file.ioFile.toPath())
            if (bytes.containsZeroByte()) {
                null
            } else {
                String(bytes, file.getCharset(project))
            }
        } catch (_: Throwable) {
            null
        }

    private fun ByteArray.containsZeroByte(): Boolean =
        any { it.toInt() == 0 }

    private fun StringBuilder.appendAdded(content: String) {
        appendLimitedLines(content) { "+$it" }
    }

    private fun StringBuilder.appendRemoved(content: String) {
        appendLimitedLines(content) { "-$it" }
    }

    private fun StringBuilder.appendModified(before: String, after: String) {
        val beforeLines = before.lines()
        val afterLines = after.lines()
        val prefix = commonPrefixLength(beforeLines, afterLines)
        val suffix = commonSuffixLength(beforeLines, afterLines, prefix)

        beforeLines.subList(prefix, beforeLines.size - suffix)
            .forEach { appendLine("-$it") }

        afterLines.subList(prefix, afterLines.size - suffix)
            .forEach { appendLine("+$it") }
    }

    private fun StringBuilder.appendLimitedLines(content: String, transform: (String) -> String) {
        val lines = content.lines()
        lines.forEach { appendLine(transform(it)) }
    }

    private fun commonPrefixLength(before: List<String>, after: List<String>): Int {
        val limit = minOf(before.size, after.size)
        var index = 0
        while (index < limit && before[index] == after[index]) {
            index += 1
        }
        return index
    }

    private fun commonSuffixLength(before: List<String>, after: List<String>, prefix: Int): Int {
        var suffix = 0
        while (
            suffix < before.size - prefix &&
            suffix < after.size - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]
        ) {
            suffix += 1
        }
        return suffix
    }
}

private object TokenBudgetCalculator {
    fun diffTokenBudget(contextTokens: Int, fileSummary: String): Int {
        val outputReserve = when {
            contextTokens >= 200_000 -> 8_192
            contextTokens >= 64_000 -> 4_096
            else -> 2_048
        }
        val systemAndInstructionReserve = 1_200
        val safetyReserve = (contextTokens * 0.08).toInt().coerceIn(512, 16_384)
        val fileSummaryTokens = TextTokenEstimator.estimateTokens(fileSummary)
        return (contextTokens - outputReserve - systemAndInstructionReserve - safetyReserve - fileSummaryTokens)
            .coerceAtLeast(1_000)
    }
}

private object TextTokenEstimator {
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var tokens = 0.0
        text.forEach { char ->
            tokens += tokenCost(char)
        }
        return kotlin.math.ceil(tokens).toInt().coerceAtLeast(1)
    }

    fun truncateToTokenBudget(text: String, maxTokens: Int): String {
        if (estimateTokens(text) <= maxTokens) return text
        val builder = StringBuilder()
        var tokens = 0.0
        for (char in text) {
            val next = tokens + tokenCost(char)
            if (next > maxTokens) break
            builder.append(char)
            tokens = next
        }
        return builder.toString().trimEnd()
    }

    private fun tokenCost(char: Char): Double =
        when {
            char.isWhitespace() -> 0.12
            char.code <= 0x7F -> 0.28
            char.code <= 0x07FF -> 0.55
            else -> 0.85
        }
}
