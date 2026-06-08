package com.casiku.aca.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

@State(
    name = "AiCommitAssistantSettings",
    storages = [Storage("aiCommitAssistant.xml")]
)
class AiCommitSettingsState : PersistentStateComponent<AiCommitSettingsState.StateData> {
    private var state = StateData()
    private var migrationChecked = false

    override fun getState(): StateData = state

    override fun loadState(state: StateData) {
        XmlSerializerUtil.copyBean(state, this.state)
        ensureChannels()
    }

    fun ensureChannels() {
        migrateLegacySettingsIfNeeded()
        if (state.channels.isEmpty()) {
            state.channels.add(
                ChannelData(
                    id = "default",
                    name = state.channelName.ifBlank { "OpenAI" },
                    baseUrl = state.baseUrl.ifBlank { "https://api.openai.com/v1" },
                    model = state.model.ifBlank { "gpt-4o-mini" },
                )
            )
        }
        state.channels.forEach { channel ->
            if (channel.id.isBlank()) {
                channel.id = UUID.randomUUID().toString()
            }
            if (channel.name.isBlank()) {
                channel.name = "OpenAI"
            }
        }
        if (state.currentChannelId.isBlank() || state.channels.none { it.id == state.currentChannelId }) {
            state.currentChannelId = state.channels.first().id
        }
        syncCurrentChannelToLegacyFields()
    }

    private fun migrateLegacySettingsIfNeeded() {
        if (migrationChecked || state.migrationVersion >= 1) return
        migrationChecked = true

        val legacy = LegacySettingsImporter.load()
        if (legacy == null) {
            state.migrationVersion = 1
            return
        }

        val targetChannelId = state.currentChannelId.ifBlank { legacy.currentChannelId.orEmpty().ifBlank { "default" } }
        ApiKeyStore.migrateApiKeyIfMissing(targetChannelId, legacy.apiKey)
        if (hasMeaningfulSettings()) {
            state.migrationVersion = 1
            return
        }

        state.channels.clear()
        if (legacy.channels.isNotEmpty()) {
            state.channels.addAll(legacy.channels)
        } else {
            state.channels.add(
                ChannelData(
                    id = legacy.currentChannelId.orEmpty().ifBlank { "default" },
                    name = legacy.channelName.orEmpty().ifBlank { "OpenAI" },
                    baseUrl = legacy.baseUrl.orEmpty().ifBlank { "https://api.openai.com/v1" },
                    model = legacy.model.orEmpty().ifBlank { "gpt-4o-mini" },
                    modelContextTokens = legacy.maxContextTokens ?: 0,
                )
            )
        }
        state.currentChannelId = legacy.currentChannelId
            ?.takeIf { id -> state.channels.any { it.id == id } }
            ?: state.channels.first().id
        legacy.language?.let { state.language = it }
        legacy.promptStyle?.let { state.promptStyle = it }
        legacy.customPrompt?.let { state.customPrompt = it }
        legacy.maxContextTokens?.let {
            state.maxContextTokens = it
            state.autoContextTokens = false
        }
        legacy.timeoutSeconds?.let { state.timeoutSeconds = it }
        legacy.streaming?.let { state.streaming = it }
        legacy.thinkingFilter?.let { state.thinkingFilter = it }
        state.migrationVersion = 1
    }

    private fun hasMeaningfulSettings(): Boolean =
        state.channels.any { channel ->
            channel.baseUrl != "https://api.openai.com/v1" ||
                channel.model != "gpt-4o-mini" ||
                channel.name != "OpenAI"
        } ||
            state.baseUrl != "https://api.openai.com/v1" ||
            state.model != "gpt-4o-mini" ||
            state.channelName != "OpenAI" ||
            state.customPrompt.isNotBlank()

    fun currentChannel(): ChannelData {
        ensureChannels()
        return state.channels.first { it.id == state.currentChannelId }
    }

    fun stateForCurrentChannel(): StateData {
        ensureChannels()
        val channel = currentChannel()
        return state.copy(
            channelName = channel.name,
            baseUrl = channel.baseUrl,
            model = channel.model,
        )
    }

    fun syncCurrentChannelToLegacyFields() {
        val channel = state.channels.firstOrNull { it.id == state.currentChannelId } ?: return
        state.channelName = channel.name
        state.baseUrl = channel.baseUrl
        state.model = channel.model
    }

    fun saveCurrentChannel(name: String, baseUrl: String, model: String, modelContextTokens: Int = 0) {
        val channel = currentChannel()
        channel.name = name.ifBlank { "OpenAI" }
        channel.baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" }
        channel.model = model
        channel.modelContextTokens = modelContextTokens.coerceAtLeast(0)
        syncCurrentChannelToLegacyFields()
    }

    fun addChannel(name: String): ChannelData {
        val channel = ChannelData(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "New Channel" },
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
        )
        state.channels.add(channel)
        state.currentChannelId = channel.id
        syncCurrentChannelToLegacyFields()
        return channel
    }

    fun effectiveContextTokens(): Int {
        ensureChannels()
        if (!state.autoContextTokens) {
            return state.maxContextTokens
        }
        val channel = currentChannel()
        return ModelContextTokenAdvisor.recommend(channel.model, channel.modelContextTokens).contextTokens
    }

    fun removeChannel(channelId: String) {
        ensureChannels()
        if (state.channels.size <= 1) {
            return
        }
        state.channels.removeIf { it.id == channelId }
        if (state.currentChannelId == channelId || state.channels.none { it.id == state.currentChannelId }) {
            state.currentChannelId = state.channels.first().id
        }
        syncCurrentChannelToLegacyFields()
    }

    data class StateData(
        var currentChannelId: String = "",
        var channels: MutableList<ChannelData> = mutableListOf(),
        var channelName: String = "OpenAI",
        var baseUrl: String = "https://api.openai.com/v1",
        var model: String = "gpt-4o-mini",
        var language: String = "中文",
        var promptStyle: String = PromptStyle.CONVENTIONAL.name,
        var customPrompt: String = "",
        var maxDiffChars: Int = 20_000,
        var maxContextTokens: Int = ModelContextTokenAdvisor.DEFAULT_CONTEXT_TOKENS,
        var autoMaxDiffChars: Boolean = true,
        var autoContextTokens: Boolean = true,
        var timeoutSeconds: Int = 60,
        var streaming: Boolean = true,
        var thinkingFilter: Boolean = true,
        var migrationVersion: Int = 0,
    )

    data class ChannelData(
        var id: String = "",
        var name: String = "OpenAI",
        var baseUrl: String = "https://api.openai.com/v1",
        var model: String = "gpt-4o-mini",
        var modelContextTokens: Int = 0,
    )

    companion object {
        fun getInstance(): AiCommitSettingsState =
            ApplicationManager.getApplication().getService(AiCommitSettingsState::class.java)
    }
}

enum class PromptStyle(val displayName: String) {
    CONVENTIONAL("Conventional Commits"),
    SIMPLE("Simple"),
    DETAILED("Detailed"),
    CUSTOM("Custom"),
}
