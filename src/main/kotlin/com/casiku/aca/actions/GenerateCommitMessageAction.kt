package com.casiku.aca.actions

import com.casiku.aca.ai.OpenAiCompatibleProvider
import com.casiku.aca.ai.CommitMessageSanitizer
import com.casiku.aca.diff.CommitDiffCollector
import com.casiku.aca.notification.AiCommitNotifier
import com.casiku.aca.prompt.PromptBuilder
import com.casiku.aca.settings.AiCommitSettingsState
import com.casiku.aca.settings.ApiKeyStore
import com.casiku.aca.ui.AcaIcons
import com.casiku.aca.ui.CommitMessageGenerationOverlay
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcs.commit.CommitWorkflowUi

class GenerateCommitMessageAction : DumbAwareAction(
    "AI Commit Assistant",
    "Generate commit message with AI Commit Assistant",
    AcaIcons.AI_COMMIT,
) {
    init {
        isEnabledInModalContext = true
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        e.presentation.isVisible = project != null && workflowUi != null
        e.presentation.isEnabled = project != null && workflowUi != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val workflowUi = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
        if (workflowUi == null) {
            AiCommitNotifier.warn(project, "无法读取 IDEA Commit 窗口上下文。")
            return
        }

        val settingsService = AiCommitSettingsState.getInstance()
        settingsService.ensureChannels()
        val settings = settingsService.state
        val apiKey = ApiKeyStore.getApiKey(settings.currentChannelId)
        if (apiKey.isNullOrBlank() || settings.model.isBlank()) {
            AiCommitNotifier.warn(project, "请先配置 API key 和 model。")
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "AI Commit Assistant")
            return
        }

        val changes = workflowUi.getIncludedChanges()
        if (changes.isEmpty()) {
            AiCommitNotifier.warn(project, "没有可用于生成提交信息的已选变更。")
            return
        }

        val commitMessageUi = workflowUi.commitMessageUi
        val originalMessage = commitMessageUi.text.orEmpty()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating AI Commit Message", true) {
                override fun run(indicator: ProgressIndicator) {
                    generate(project, changes, commitMessageUi, originalMessage, settings, settingsService.effectiveContextTokens(), apiKey, indicator)
                }
            }
        )
    }

    private fun generate(
        project: Project,
        changes: List<Change>,
        commitMessageUi: CommitMessageUi,
        originalMessage: String,
        settings: AiCommitSettingsState.StateData,
        contextTokens: Int,
        apiKey: String,
        indicator: ProgressIndicator,
    ) {
        val buffer = StringBuilder()
        val loadingOverlay = CommitMessageGenerationOverlay(commitMessageUi)
        try {
            indicator.text = "Collecting selected changes"
            val diffContext = CommitDiffCollector.collect(project, changes, contextTokens, indicator)
            val prompt = PromptBuilder.build(settings, diffContext)
            val provider = OpenAiCompatibleProvider(settings, apiKey)

            updateCommitMessage(commitMessageUi) {
                setText("")
            }
            loadingOverlay.startGenerating()

            indicator.text = "Generating commit message"
            val finalMessage = provider.generate(prompt, indicator) { token ->
                buffer.append(token)
                val preview = if (settings.thinkingFilter) {
                    CommitMessageSanitizer.sanitizePreview(buffer.toString())
                } else {
                    buffer.toString()
                }
                if (settings.thinkingFilter && CommitMessageSanitizer.containsReasoning(buffer.toString())) {
                    loadingOverlay.showThinking()
                }
                if (preview.isNotBlank()) {
                    loadingOverlay.stop()
                    updateCommitMessage(commitMessageUi) {
                        setText(preview)
                    }
                }
            }.let {
                if (settings.thinkingFilter) CommitMessageSanitizer.sanitize(it) else it.trim()
            }

            loadingOverlay.stop()
            updateCommitMessage(commitMessageUi) {
                setText(finalMessage)
                focus()
            }
            AiCommitNotifier.info(project, "已生成 Commit Message。")
        } catch (canceled: ProcessCanceledException) {
            loadingOverlay.stop()
            restore(project, commitMessageUi, originalMessage, "已取消生成 Commit Message。")
            throw canceled
        } catch (error: Throwable) {
            loadingOverlay.stop()
            restore(project, commitMessageUi, originalMessage, "生成 Commit Message 失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun restore(project: Project, commitMessageUi: CommitMessageUi, originalMessage: String, message: String) {
        updateCommitMessage(commitMessageUi) {
            setText(originalMessage)
            focus()
        }
        AiCommitNotifier.error(project, message)
    }

    private fun updateCommitMessage(commitMessageUi: CommitMessageUi, update: CommitMessageUi.() -> Unit) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            commitMessageUi.update()
        }
    }
}
