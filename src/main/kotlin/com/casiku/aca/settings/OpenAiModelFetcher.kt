package com.casiku.aca.settings

import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class ModelFetchResult(
    val models: List<String>,
    val resolvedBaseUrl: String,
    val modelContextTokens: Map<String, Int> = emptyMap(),
)

object OpenAiModelFetcher {
    fun fetch(baseUrl: String, apiKey: String, timeoutSeconds: Int): ModelFetchResult {
        val resolvedBaseUrl = OpenAiEndpoint.normalizeBaseUrl(baseUrl)
        val endpointText = OpenAiEndpoint.modelsUrl(resolvedBaseUrl)
        val endpoint = URI.create(endpointText)
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw ModelFetchException(response.statusCode(), endpointText, response.body())
        }

        val root = JsonParser.parseString(response.body()).asJsonObject
        val data = root.getAsJsonArray("data") ?: return ModelFetchResult(emptyList(), resolvedBaseUrl)
        val modelContextTokens = mutableMapOf<String, Int>()
        val models = data.mapNotNull { item ->
            val modelObject = item.asJsonObject
            val model = modelObject.get("id") ?: return@mapNotNull null
            if (model.isJsonNull) {
                null
            } else {
                val modelId = model.asString
                val contextTokens = extractContextTokens(modelObject)
                if (contextTokens != null) {
                    modelContextTokens[modelId] = contextTokens
                }
                modelId
            }
        }.distinct().sorted()

        val modelsWithoutProviderContext = models.filter { !modelContextTokens.containsKey(it) }
        val modelsDevContextTokens = runCatching {
            modelsWithoutProviderContext.associateWith { model ->
                ModelsDevClient.findContextTokens(model, resolvedBaseUrl, timeoutSeconds)
            }.mapValues { it.value ?: 0 }
        }.getOrDefault(emptyMap())

        modelsDevContextTokens.forEach { (model, contextTokens) ->
            if (contextTokens > 0) {
                modelContextTokens.putIfAbsent(model, contextTokens)
            }
        }

        return ModelFetchResult(models, resolvedBaseUrl, modelContextTokens)
    }

    private fun extractContextTokens(modelObject: JsonObject): Int? {
        val direct = readContextTokenField(modelObject)
        if (direct != null) return direct

        return listOf("metadata", "capabilities", "limits", "parameters")
            .asSequence()
            .mapNotNull { key -> modelObject.get(key)?.takeIf { it.isJsonObject }?.asJsonObject }
            .mapNotNull { readContextTokenField(it) }
            .firstOrNull()
    }

    private fun readContextTokenField(modelObject: JsonObject): Int? {
        val fieldNames = listOf(
            "context_length",
            "context_window",
            "max_context_length",
            "max_context_window",
            "max_model_len",
            "max_sequence_length",
            "max_input_tokens",
            "input_token_limit",
        )
        return fieldNames
            .asSequence()
            .mapNotNull { fieldName -> modelObject.get(fieldName)?.let { parseTokenCount(it) } }
            .firstOrNull()
    }

    private fun parseTokenCount(element: JsonElement): Int? {
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            return element.asInt.takeIf { it > 0 }
        }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return parseTokenCountText(element.asString)
        }
        return null
    }

    private fun parseTokenCountText(text: String): Int? {
        val normalized = text.lowercase().replace(",", "").trim()
        val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([km]?)""").find(normalized) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "k" -> 1_000
            "m" -> 1_000_000
            else -> 1
        }
        return (number * multiplier).toInt().takeIf { it > 0 }
    }
}
