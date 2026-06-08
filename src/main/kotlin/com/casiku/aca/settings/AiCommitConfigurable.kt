package com.casiku.aca.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AiCommitConfigurable : Configurable {
    private val settings = AiCommitSettingsState.getInstance()

    private var panel: JPanel? = null
    private val labels = mutableMapOf<String, JLabel>()
    private val customPromptComponents = mutableListOf<JComponent>()
    private val fetchedModelContextTokens = mutableMapOf<String, Int>()

    private val channelNameField = JTextField()
    private val channelButton = JButton()
    private val baseUrlField = JTextField()
    private val apiKeyField = JPasswordField()
    private val connectButton = JButton()
    private val modelBox = JComboBox<String>().apply { isEditable = true }
    private val languageBox = JComboBox(arrayOf("中文", "English"))
    private val promptStyleBox = JComboBox(PromptStyle.entries.toTypedArray())
    private val customPromptArea = JTextArea(8, 20)
    private val contextTokensSpinner = JSpinner(
        SpinnerNumberModel(
            ModelContextTokenAdvisor.DEFAULT_CONTEXT_TOKENS,
            ModelContextTokenAdvisor.MIN_CONTEXT_TOKENS,
            ModelContextTokenAdvisor.HARD_MAX_CONTEXT_TOKENS,
            1_000,
        )
    )
    private val autoContextTokensBox = JCheckBox()
    private val timeoutSecondsSpinner = JSpinner(SpinnerNumberModel(60, 5, 300, 5))
    private val streamingBox = JCheckBox()
    private val thinkingFilterBox = JCheckBox()

    override fun getDisplayName(): String = "AI Commit Assistant"

    override fun createComponent(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0

        fun addRow(key: String, component: JComponent): JLabel {
            val label = JLabel()
            val isCustomPrompt = key == "customPrompt"
            val labelConstraints = GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = if (isCustomPrompt) GridBagConstraints.NORTHWEST else GridBagConstraints.WEST
                insets = Insets(4, 0, 4, 12)
            }
            val fieldConstraints = GridBagConstraints().apply {
                gridx = 1
                gridy = row
                weightx = 1.0
                weighty = if (isCustomPrompt) 1.0 else 0.0
                fill = if (isCustomPrompt) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
                insets = Insets(4, 0, 4, 0)
            }
            makeHorizontallyShrinkable(component)
            labels[key] = label
            form.add(label, labelConstraints)
            form.add(component, fieldConstraints)
            row += 1
            return label
        }

        customPromptArea.lineWrap = true
        customPromptArea.wrapStyleWord = true
        promptStyleBox.renderer = PromptStyleListCellRenderer { isChinese() }

        val apiKeyPanel = JPanel(BorderLayout(8, 0)).apply {
            add(apiKeyField, BorderLayout.CENTER)
            add(connectButton, BorderLayout.EAST)
        }
        val customPromptScrollPane = JScrollPane(customPromptArea)

        val channelPanel = JPanel(BorderLayout(8, 0)).apply {
            add(channelNameField, BorderLayout.CENTER)
            add(channelButton, BorderLayout.EAST)
        }
        val contextTokensPanel = JPanel(BorderLayout(8, 0)).apply {
            add(contextTokensSpinner, BorderLayout.CENTER)
            add(autoContextTokensBox, BorderLayout.EAST)
        }

        addRow("channelName", channelPanel)
        addRow("baseUrl", baseUrlField)
        addRow("apiKey", apiKeyPanel)
        addRow("model", modelBox)
        addRow("language", languageBox)
        addRow("promptStyle", promptStyleBox)
        customPromptComponents += addRow("customPrompt", customPromptScrollPane)
        customPromptComponents += customPromptScrollPane
        addRow("contextTokens", contextTokensPanel)
        addRow("timeoutSeconds", timeoutSecondsSpinner)
        addRow("streaming", streamingBox)
        addRow("thinkingFilter", thinkingFilterBox)

        form.add(JPanel(), GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weightx = 1.0
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        })

        customPromptScrollPane.minimumSize = Dimension(0, 120)
        customPromptScrollPane.preferredSize = Dimension(0, 180)
        makeHorizontallyShrinkable(form)

        connectButton.addActionListener { connectAndLoadModels() }
        channelButton.addActionListener { showChannelMenu() }
        modelBox.addActionListener { updateContextTokensFromModel() }
        addModelEditorDocumentListener()
        languageBox.addActionListener { updateTexts() }
        promptStyleBox.addActionListener { updateCustomPromptVisibility() }
        autoContextTokensBox.addActionListener { updateContextTokensAutoState() }

        panel = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(0, 0)
            add(form, BorderLayout.CENTER)
        }
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        settings.ensureChannels()
        val state = settings.state
        val channel = settings.currentChannel()
        return channelNameField.text.trim() != channel.name ||
            baseUrlField.text.trim() != channel.baseUrl ||
            String(apiKeyField.password).trim() != (ApiKeyStore.getApiKey(channel.id) ?: "") ||
            selectedModel() != channel.model ||
            selectedLanguage() != state.language ||
            selectedPromptStyle().name != state.promptStyle ||
            customPromptArea.text != state.customPrompt ||
            effectiveFormContextTokens() != state.maxContextTokens ||
            autoContextTokensBox.isSelected != state.autoContextTokens ||
            timeoutSecondsSpinner.value as Int != state.timeoutSeconds ||
            streamingBox.isSelected != state.streaming ||
            thinkingFilterBox.isSelected != state.thinkingFilter
    }

    override fun apply() {
        val state = settings.state
        saveCurrentFormToChannel()
        state.language = selectedLanguage()
        state.promptStyle = selectedPromptStyle().name
        state.customPrompt = customPromptArea.text
        state.autoContextTokens = autoContextTokensBox.isSelected
        state.maxContextTokens = effectiveFormContextTokens()
        state.timeoutSeconds = timeoutSecondsSpinner.value as Int
        state.streaming = streamingBox.isSelected
        state.thinkingFilter = thinkingFilterBox.isSelected
    }

    override fun reset() {
        settings.ensureChannels()
        val state = settings.state
        autoContextTokensBox.isSelected = state.autoContextTokens
        loadChannelToForm(settings.currentChannel())
        languageBox.selectedItem = state.language.ifBlank { "中文" }
        promptStyleBox.selectedItem = PromptStyle.entries.firstOrNull { it.name == state.promptStyle } ?: PromptStyle.CONVENTIONAL
        customPromptArea.text = state.customPrompt
        contextTokensSpinner.value = if (state.autoContextTokens) modelContextRecommendation().contextTokens else state.maxContextTokens
        timeoutSecondsSpinner.value = state.timeoutSeconds
        streamingBox.isSelected = state.streaming
        thinkingFilterBox.isSelected = state.thinkingFilter
        updateTexts()
        updateContextTokensAutoState()
        updateCustomPromptVisibility()
    }

    override fun disposeUIResources() {
        panel = null
        labels.clear()
        customPromptComponents.clear()
    }

    private fun connectAndLoadModels() {
        val parent = panel ?: return
        val baseUrl = baseUrlField.text.trim()
        val apiKey = String(apiKeyField.password).trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            Messages.showWarningDialog(parent, text("请先填写 Base URL 和 API Key。", "Please enter Base URL and API key first."), text("连接失败", "Connection Failed"))
            return
        }

        connectButton.isEnabled = false
        connectButton.text = text("连接中...", "Connecting...")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = OpenAiModelFetcher.fetch(baseUrl, apiKey, timeoutSecondsSpinner.value as Int)
                SwingUtilities.invokeLater {
                    val successMessage = successMessage(result, baseUrl)
                    baseUrlField.text = result.resolvedBaseUrl
                    fetchedModelContextTokens.clear()
                    fetchedModelContextTokens.putAll(result.modelContextTokens)
                    val selected = selectedModel().ifBlank { result.models.firstOrNull().orEmpty() }
                    setModelItems(result.models, selected)
                    updateContextTokensAutoState()
                    Messages.showInfoMessage(parent, successMessage, text("连接成功", "Connected"))
                }
            } catch (error: Throwable) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(parent, errorMessage(error), text("连接失败", "Connection Failed"))
                }
            } finally {
                SwingUtilities.invokeLater {
                    connectButton.isEnabled = true
                    updateTexts()
                }
            }
        }
    }

    private fun showChannelMenu() {
        val popup = JPopupMenu()
        saveCurrentFormToChannel()

        settings.ensureChannels()
        settings.state.channels.forEach { channel ->
            val prefix = if (channel.id == settings.state.currentChannelId) "✓ " else ""
            popup.add(JMenuItem("$prefix${channel.name}").apply {
                addActionListener {
                    saveCurrentFormToChannel()
                    settings.state.currentChannelId = channel.id
                    settings.syncCurrentChannelToLegacyFields()
                    loadChannelToForm(channel)
                }
            })
        }

        popup.addSeparator()
        popup.add(JMenuItem(text("新增渠道", "Add Channel")).apply {
            addActionListener { addChannel() }
        })
        popup.add(JMenuItem(text("删除当前渠道", "Delete Current Channel")).apply {
            addActionListener { deleteCurrentChannel() }
        })
        popup.show(channelButton, 0, channelButton.height)
    }

    private fun addChannel() {
        val parent = panel ?: return
        val name = Messages.showInputDialog(
            parent,
            text("请输入新渠道名称：", "Enter new channel name:"),
            text("新增渠道", "Add Channel"),
            Messages.getQuestionIcon(),
            text("新渠道", "New Channel"),
            null,
        )?.trim() ?: return

        saveCurrentFormToChannel()
        val channel = settings.addChannel(name)
        loadChannelToForm(channel)
    }

    private fun deleteCurrentChannel() {
        val parent = panel ?: return
        settings.ensureChannels()
        if (settings.state.channels.size <= 1) {
            Messages.showWarningDialog(parent, text("至少需要保留一个渠道。", "At least one channel is required."), text("无法删除", "Cannot Delete"))
            return
        }

        val channel = settings.currentChannel()
        val confirmed = Messages.showYesNoDialog(
            parent,
            text("确定删除渠道“${channel.name}”吗？", "Delete channel \"${channel.name}\"?"),
            text("删除渠道", "Delete Channel"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) {
            return
        }

        ApiKeyStore.setApiKey(channel.id, "")
        settings.removeChannel(channel.id)
        loadChannelToForm(settings.currentChannel())
    }

    private fun saveCurrentFormToChannel() {
        settings.ensureChannels()
        val channel = settings.currentChannel()
        val model = selectedModel()
        settings.saveCurrentChannel(
            channelNameField.text.trim().ifBlank { channel.name.ifBlank { "OpenAI" } },
            baseUrlField.text.trim().ifBlank { "https://api.openai.com/v1" },
            model,
            selectedModelContextTokens(model) ?: 0,
        )
        ApiKeyStore.setApiKey(channel.id, String(apiKeyField.password))
    }

    private fun loadChannelToForm(channel: AiCommitSettingsState.ChannelData) {
        channelNameField.text = channel.name.ifBlank { "OpenAI" }
        baseUrlField.text = channel.baseUrl
        apiKeyField.text = ApiKeyStore.getApiKey(channel.id) ?: ""
        if (channel.modelContextTokens > 0) {
            fetchedModelContextTokens[channel.model] = channel.modelContextTokens
        }
        setModelItems(listOf(channel.model), channel.model)
        updateContextTokensAutoState()
        updateTexts()
    }

    private fun successMessage(result: ModelFetchResult, originalBaseUrl: String): String {
        val normalized = result.resolvedBaseUrl != originalBaseUrl.trim().trimEnd('/')
        val suffix = if (normalized) {
            text("\n已自动规范化 Base URL：${result.resolvedBaseUrl}", "\nBase URL normalized to: ${result.resolvedBaseUrl}")
        } else {
            ""
        }
        return text("连接成功，已获取 ${result.models.size} 个模型。$suffix", "Connected. Loaded ${result.models.size} models.$suffix")
    }

    private fun errorMessage(error: Throwable): String {
        val message = error.message ?: error.javaClass.simpleName
        val normalizedUrl = OpenAiEndpoint.normalizeBaseUrl(baseUrlField.text)
        val hint = if (normalizedUrl != baseUrlField.text.trim().trimEnd('/')) {
            "\n\n${text("提示：插件会自动补全 OpenAI 兼容接口版本路径：$normalizedUrl。", "Hint: The plugin auto-completes the OpenAI-compatible version path: $normalizedUrl.")}"
        } else {
            ""
        }
        return text("连接失败：\n$message$hint", "Connection failed:\n$message$hint")
    }

    private fun makeHorizontallyShrinkable(component: JComponent) {
        component.minimumSize = Dimension(0, component.minimumSize.height)
        component.preferredSize = Dimension(0, component.preferredSize.height)
    }

    private fun updateTexts() {
        labels["channelName"]?.text = text("渠道名称：", "Channel name:")
        labels["baseUrl"]?.text = text("Base URL：", "Base URL:")
        labels["apiKey"]?.text = text("API Key：", "API key:")
        labels["model"]?.text = text("模型：", "Model:")
        labels["language"]?.text = text("语言：", "Language:")
        labels["promptStyle"]?.text = text("提示词模板：", "Prompt style:")
        labels["contextTokens"]?.text = text("模型上下文 token 数：", "Model context tokens:")
        labels["timeoutSeconds"]?.text = text("超时时间（秒）：", "Timeout seconds:")
        labels["streaming"]?.text = text("流式输出：", "Streaming:")
        labels["thinkingFilter"]?.text = text("思考屏蔽：", "Thinking filter:")
        labels["customPrompt"]?.text = text("自定义提示词：", "Custom prompt:")
        autoContextTokensBox.text = text("根据当前模型自动获取", "Auto from selected model")
        streamingBox.text = text("生成结果流式写提交输入框", "Stream generated result into commit input")
        thinkingFilterBox.text = text("隐藏模型输出中的思考过程", "Hide reasoning from model output")
        connectButton.text = text("连接", "Connect")
        channelButton.text = text("渠道", "Channels")
        promptStyleBox.repaint()
        updateContextTokensTooltip()
        updateCustomPromptVisibility()
    }

    private fun addModelEditorDocumentListener() {
        val editor = modelBox.editor.editorComponent as? JTextField ?: return
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateContextTokensFromModel()
            override fun removeUpdate(e: DocumentEvent?) = updateContextTokensFromModel()
            override fun changedUpdate(e: DocumentEvent?) = updateContextTokensFromModel()
        })
    }

    private fun updateContextTokensAutoState() {
        contextTokensSpinner.isEnabled = !autoContextTokensBox.isSelected
        updateContextTokensFromModel()
    }

    private fun updateContextTokensFromModel() {
        if (autoContextTokensBox.isSelected) {
            contextTokensSpinner.value = modelContextRecommendation().contextTokens
        }
        updateContextTokensTooltip()
    }

    private fun updateContextTokensTooltip() {
        val recommendation = modelContextRecommendation()
        val sourceText = when (recommendation.source) {
            ModelContextRecommendationSource.PROVIDER_METADATA -> text("来自模型接口返回的上下文长度", "From model metadata")
            ModelContextRecommendationSource.MODELS_DEV -> text("来自 models.dev", "From models.dev")
            ModelContextRecommendationSource.MODEL_NAME -> text("根据模型名称估算", "Estimated by model name")
            ModelContextRecommendationSource.DEFAULT -> text("未识别模型，使用保守默认值", "Unknown model, using conservative default")
        }
        val tooltip = text(
            "这是当前模型最大上下文 token 数，用于计算可发送的 diff 预算；插件会尽量保留完整 diff，仅在超过上下文预算时截断。当前值：${formatNumber(recommendation.contextTokens)} tokens，$sourceText。",
            "This is the selected model's maximum context tokens, used to calculate the diff budget. The plugin keeps the full diff when possible and truncates only when it exceeds the context budget. Current value: ${formatNumber(recommendation.contextTokens)} tokens, $sourceText.",
        )
        contextTokensSpinner.toolTipText = tooltip
        autoContextTokensBox.toolTipText = tooltip
    }

    private fun effectiveFormContextTokens(): Int =
        if (autoContextTokensBox.isSelected) modelContextRecommendation().contextTokens else contextTokensSpinner.value as Int

    private fun modelContextRecommendation(): ModelContextRecommendation {
        val model = selectedModel()
        return ModelContextTokenAdvisor.recommend(model, selectedModelContextTokens(model))
    }

    private fun selectedModelContextTokens(model: String): Int? {
        fetchedModelContextTokens[model]?.let { return it }
        val channel = runCatching { settings.currentChannel() }.getOrNull()
        return channel
            ?.takeIf { it.model == model }
            ?.modelContextTokens
            ?.takeIf { it > 0 }
    }

    private fun formatNumber(value: Int): String = "%,d".format(value)

    private fun updateCustomPromptVisibility() {
        val visible = selectedPromptStyle() == PromptStyle.CUSTOM
        customPromptComponents.forEach { it.isVisible = visible }
        panel?.revalidate()
        panel?.repaint()
    }

    private fun setModelItems(models: List<String>, selected: String) {
        val items = (models + selected).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        modelBox.model = DefaultComboBoxModel(items.toTypedArray())
        modelBox.selectedItem = selected
        modelBox.editor.item = selected
    }

    private fun selectedModel(): String = modelBox.editor.item?.toString()?.trim().orEmpty()

    private fun selectedLanguage(): String = languageBox.selectedItem?.toString() ?: "中文"

    private fun selectedPromptStyle(): PromptStyle = promptStyleBox.selectedItem as? PromptStyle ?: PromptStyle.CONVENTIONAL

    private fun isChinese(): Boolean = selectedLanguage() == "中文"

    private fun text(chinese: String, english: String): String = if (isChinese()) chinese else english
}
