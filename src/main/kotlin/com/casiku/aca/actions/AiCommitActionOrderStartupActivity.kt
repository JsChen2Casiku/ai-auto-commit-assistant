package com.casiku.aca.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class AiCommitActionOrderStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val actionManager = ActionManager.getInstance()
            val group = actionManager.getAction("Vcs.MessageActionGroup") as? DefaultActionGroup ?: return@invokeLater
            val action = actionManager.getAction("AiCommitAssistant.GenerateCommitMessage") ?: return@invokeLater

            group.remove(action)
            group.addAction(action, Constraints.LAST)
        }
    }
}
