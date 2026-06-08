package com.casiku.aca.settings

import com.intellij.openapi.application.PathManager
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

data class LegacySettingsData(
    val channels: List<AiCommitSettingsState.ChannelData> = emptyList(),
    val currentChannelId: String? = null,
    val channelName: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val language: String? = null,
    val promptStyle: String? = null,
    val customPrompt: String? = null,
    val maxContextTokens: Int? = null,
    val timeoutSeconds: Int? = null,
    val streaming: Boolean? = null,
    val thinkingFilter: Boolean? = null,
    val apiKey: String? = null,
) {
    fun hasUsefulSettings(): Boolean =
        channels.isNotEmpty() ||
            !baseUrl.isNullOrBlank() ||
            !model.isNullOrBlank() ||
            !apiKey.isNullOrBlank() ||
            !customPrompt.isNullOrBlank()
}

object LegacySettingsImporter {
    private val knownFileNames = setOf(
        "aiCommitAssistant.xml",
        "aiCommitSettings.xml",
        "aiCommitAssistantSettings.xml",
        "ai-commit-assistant.xml",
        "aiCommitHelper.xml",
        "aiCommitMessage.xml",
        "aiCommit.xml",
        "commitAi.xml",
        "commitAI.xml",
        "aicommitassistant.xml",
        "com.github.casiku.aicommitassistant.xml",
        "com.github.casiku.aicommitassistant.settings.xml",
        "com.casiku.aca.xml",
    )
    private val legacyComponentHints = listOf(
        "aicommit",
        "ai commit",
        "commitassistant",
        "commithelper",
        "commitmessage",
        "com.github.casiku",
    )

    fun load(): LegacySettingsData? {
        val optionsPath = Path.of(PathManager.getOptionsPath())
        if (!Files.isDirectory(optionsPath)) return null

        val files = Files.list(optionsPath).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".xml", ignoreCase = true) }
                .filter { knownFileNames.contains(it.fileName.toString()) || mayContainLegacyComponent(it) }
                .toList()
        }

        return files
            .mapNotNull { parseFile(it) }
            .firstOrNull { it.hasUsefulSettings() }
    }

    private fun mayContainLegacyComponent(file: Path): Boolean {
        val fileName = file.fileName.toString().lowercase()
        if (fileName.contains("commit") && fileName.contains("ai")) return true

        val head = runCatching {
            Files.newBufferedReader(file).use { reader ->
                buildString {
                    repeat(40) {
                        val line = reader.readLine() ?: return@buildString
                        append(line)
                        append('\n')
                    }
                }
            }
        }.getOrDefault("")
        val normalized = head.lowercase()
        return legacyComponentHints.any { normalized.contains(it) }
    }

    private fun parseFile(file: Path): LegacySettingsData? =
        runCatching {
            val document = DocumentBuilderFactory.newInstance().apply {
                isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }.newDocumentBuilder().parse(file.toFile())

            val root = document.documentElement ?: return@runCatching null
            val options = collectOptions(root)
            val channels = collectChannels(root)
            val data = LegacySettingsData(
                channels = channels,
                currentChannelId = option(options, "currentChannelId", "selectedChannelId", "activeChannelId"),
                channelName = option(options, "channelName", "providerName", "channel", "name"),
                baseUrl = option(options, "baseUrl", "apiBaseUrl", "openAiBaseUrl", "openaiBaseUrl", "endpoint", "apiUrl", "url"),
                model = option(options, "model", "modelName", "modelId"),
                language = normalizeLanguage(option(options, "language", "locale")),
                promptStyle = normalizePromptStyle(option(options, "promptStyle", "template", "promptTemplate")),
                customPrompt = option(options, "customPrompt", "prompt", "customTemplate"),
                maxContextTokens = intOption(options, "maxContextTokens", "contextTokens", "modelContextTokens"),
                timeoutSeconds = intOption(options, "timeoutSeconds", "timeout", "requestTimeoutSeconds"),
                streaming = boolOption(options, "streaming", "streamOutput", "enableStreaming"),
                thinkingFilter = boolOption(options, "thinkingFilter", "hideThinking", "reasoningFilter"),
                apiKey = option(options, "apiKey", "apikey", "openAiApiKey", "openaiApiKey", "token"),
            )
            data.takeIf { it.hasUsefulSettings() }
        }.getOrNull()

    private fun collectOptions(root: Element): Map<String, String> {
        val options = linkedMapOf<String, String>()
        val nodes = root.getElementsByTagName("option")
        for (index in 0 until nodes.length) {
            val option = nodes.item(index) as? Element ?: continue
            val name = option.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val value = when {
                option.hasAttribute("value") -> option.getAttribute("value")
                option.hasAttribute("text") -> option.getAttribute("text")
                else -> option.textContent?.trim().orEmpty()
            }
            if (value.isNotBlank()) {
                options.putIfAbsent(name.lowercase(), value)
            }
        }
        return options
    }

    private fun collectChannels(root: Element): List<AiCommitSettingsState.ChannelData> {
        val channels = mutableListOf<AiCommitSettingsState.ChannelData>()
        val nodes = root.getElementsByTagName("*")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val options = collectDirectOptions(element)
            val baseUrl = option(options, "baseUrl", "apiBaseUrl", "openAiBaseUrl", "openaiBaseUrl") ?: continue
            val model = option(options, "model", "modelName", "modelId").orEmpty()
            val id = option(options, "id", "channelId").orEmpty()
            val name = option(options, "name", "channelName", "providerName").orEmpty()
            channels.add(
                AiCommitSettingsState.ChannelData(
                    id = id,
                    name = name.ifBlank { "OpenAI" },
                    baseUrl = baseUrl,
                    model = model,
                    modelContextTokens = intOption(options, "modelContextTokens", "contextTokens") ?: 0,
                )
            )
        }
        return channels.distinctBy { "${it.name}|${it.baseUrl}|${it.model}" }
    }

    private fun collectDirectOptions(element: Element): Map<String, String> {
        val options = linkedMapOf<String, String>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val option = children.item(index) as? Element ?: continue
            if (option.tagName != "option") continue
            val name = option.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
            val value = when {
                option.hasAttribute("value") -> option.getAttribute("value")
                option.hasAttribute("text") -> option.getAttribute("text")
                else -> option.textContent?.trim().orEmpty()
            }
            if (value.isNotBlank()) {
                options.putIfAbsent(name.lowercase(), value)
            }
        }
        return options
    }

    private fun option(options: Map<String, String>, vararg names: String): String? =
        names.asSequence()
            .map { it.lowercase() }
            .mapNotNull { options[it] }
            .firstOrNull { it.isNotBlank() }

    private fun intOption(options: Map<String, String>, vararg names: String): Int? =
        option(options, *names)?.replace(",", "")?.toIntOrNull()?.takeIf { it > 0 }

    private fun boolOption(options: Map<String, String>, vararg names: String): Boolean? =
        option(options, *names)?.lowercase()?.let {
            when (it) {
                "true", "yes", "1", "on" -> true
                "false", "no", "0", "off" -> false
                else -> null
            }
        }

    private fun normalizeLanguage(language: String?): String? =
        when (language?.lowercase()) {
            "zh", "zh-cn", "chinese", "中文" -> "中文"
            "en", "en-us", "english" -> "English"
            else -> language
        }

    private fun normalizePromptStyle(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val normalized = style.uppercase().replace("-", "_").replace(" ", "_")
        return PromptStyle.entries.firstOrNull { it.name == normalized }?.name
            ?: PromptStyle.entries.firstOrNull { it.displayName.equals(style, ignoreCase = true) }?.name
    }
}
