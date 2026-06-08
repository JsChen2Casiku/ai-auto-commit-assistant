package com.casiku.aca.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object ModelsDevClient {
    private const val API_URL = "https://models.dev/api.json"

    @Volatile
    private var cachedIndex: ModelsDevIndex? = null

    fun findContextTokens(modelName: String, baseUrl: String, timeoutSeconds: Int): Int? {
        if (modelName.isBlank()) return null
        val index = loadIndex(timeoutSeconds)
        return index.findContextTokens(modelName, baseUrl)
    }

    private fun loadIndex(timeoutSeconds: Int): ModelsDevIndex {
        cachedIndex?.let { return it }

        synchronized(this) {
            cachedIndex?.let { return it }

            val timeout = Duration.ofSeconds(timeoutSeconds.coerceIn(5, 60).toLong())
            val request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build()
            val body = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body()
            val root = JsonParser.parseString(body).asJsonObject
            val index = ModelsDevIndex(parseEntries(root))
            cachedIndex = index
            return index
        }
    }

    private fun parseEntries(root: JsonObject): List<ModelEntry> {
        val entries = mutableListOf<ModelEntry>()
        root.entrySet().forEach { providerEntry ->
            val provider = providerEntry.value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val providerId = provider.get("id")?.takeIf { !it.isJsonNull }?.asString ?: providerEntry.key
            val providerApi = provider.get("api")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val providerHost = hostOf(providerApi)
            val models = provider.get("models")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach

            models.entrySet().forEach { modelEntry ->
                val model = modelEntry.value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val id = model.get("id")?.takeIf { !it.isJsonNull }?.asString ?: modelEntry.key
                val name = model.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val context = model.get("limit")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("context")
                    ?.takeIf { !it.isJsonNull }
                    ?.asInt
                    ?: return@forEach

                entries.add(
                    ModelEntry(
                        providerId = providerId,
                        providerHost = providerHost,
                        modelKey = modelEntry.key,
                        modelId = id,
                        modelName = name,
                        contextTokens = context,
                    )
                )
            }
        }
        return entries
    }

    private fun hostOf(url: String): String =
        runCatching { URI.create(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    private data class ModelsDevIndex(
        private val entries: List<ModelEntry>,
    ) {
        fun findContextTokens(modelName: String, baseUrl: String): Int? {
            val normalizedModel = normalize(modelName)
            if (normalizedModel.isBlank()) return null

            val baseHost = hostOf(baseUrl)
            return entries
                .mapNotNull { entry ->
                    val score = entry.matchScore(normalizedModel, baseHost)
                    if (score <= 0) null else entry to score
                }
                .maxWithOrNull(compareBy<Pair<ModelEntry, Int>> { it.second }.thenBy { it.first.contextTokens })
                ?.first
                ?.contextTokens
        }
    }

    private data class ModelEntry(
        val providerId: String,
        val providerHost: String,
        val modelKey: String,
        val modelId: String,
        val modelName: String,
        val contextTokens: Int,
    ) {
        fun matchScore(normalizedModel: String, baseHost: String): Int {
            val candidates = listOf(modelKey, modelId, modelName)
            val exact = candidates.any { normalize(it) == normalizedModel }
            val suffix = candidates.any { normalize(it).endsWith(normalizedModel) }
            if (!exact && !suffix) return 0

            val providerScore = when {
                baseHost.isBlank() || providerHost.isBlank() -> 0
                baseHost == providerHost -> 100
                baseHost.endsWith(".$providerHost") || providerHost.endsWith(".$baseHost") -> 80
                else -> 0
            }
            val matchScore = if (exact) 60 else 40
            return providerScore + matchScore
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
