package com.casiku.aca.ai

import com.casiku.aca.prompt.CommitPrompt
import com.casiku.aca.settings.AiCommitSettingsState
import com.casiku.aca.settings.OpenAiEndpoint
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.progress.ProgressIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenAiCompatibleProvider(
    private val settings: AiCommitSettingsState.StateData,
    private val apiKey: String,
) : AiProvider {
    private val gson = Gson()

    override fun generate(prompt: CommitPrompt, indicator: ProgressIndicator, onToken: TokenConsumer): String {
        val requestBody = gson.toJson(
            mapOf(
                "model" to settings.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to prompt.system),
                    mapOf("role" to "user", "content" to prompt.user),
                ),
                "temperature" to 0.2,
                "stream" to settings.streaming,
            )
        )

        val request = HttpRequest.newBuilder(endpoint())
            .timeout(Duration.ofSeconds(settings.timeoutSeconds.toLong()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", if (settings.streaming) "text/event-stream" else "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds.toLong()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
        return if (settings.streaming) {
            handleStreamingResponse(response, indicator, onToken)
        } else {
            handleJsonResponse(response, onToken)
        }
    }

    private fun endpoint(): URI {
        return URI.create(OpenAiEndpoint.chatCompletionsUrl(settings.baseUrl))
    }

    private fun handleStreamingResponse(
        response: HttpResponse<java.util.stream.Stream<String>>,
        indicator: ProgressIndicator,
        onToken: TokenConsumer,
    ): String {
        val result = StringBuilder()
        response.body().use { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                indicator.checkCanceled()
                val line = iterator.next().trim()
                if (line.isBlank() || !line.startsWith("data:")) {
                    continue
                }

                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    break
                }

                ensureSuccessful(response.statusCode(), payload)
                val token = extractStreamingContent(payload)
                if (!token.isNullOrEmpty()) {
                    result.append(token)
                    onToken.accept(token)
                }
            }
        }

        val message = result.toString().trim()
        if (message.isBlank()) {
            throw IllegalStateException("AI provider returned an empty commit message.")
        }
        return message
    }

    private fun handleJsonResponse(
        response: HttpResponse<java.util.stream.Stream<String>>,
        onToken: TokenConsumer,
    ): String {
        val body = response.body().use { it.toList().joinToString("\n") }
        ensureSuccessful(response.statusCode(), body)
        val message = extractMessageContent(body)?.trim()
            ?: throw IllegalStateException("AI provider returned an invalid response.")
        if (message.isBlank()) {
            throw IllegalStateException("AI provider returned an empty commit message.")
        }
        onToken.accept(message)
        return message
    }

    private fun ensureSuccessful(statusCode: Int, body: String) {
        if (statusCode in 200..299) {
            return
        }
        val safeBody = body.take(800)
        throw IllegalStateException("AI provider request failed with HTTP $statusCode: $safeBody")
    }

    private fun extractStreamingContent(payload: String): String? {
        val root = JsonParser.parseString(payload).asJsonObject
        val choices = root.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) {
            return null
        }
        val choice = choices[0].asJsonObject
        val delta = choice.getAsJsonObject("delta") ?: return null
        val content = delta.get("content") ?: return null
        return if (content.isJsonNull) null else content.asString
    }

    private fun extractMessageContent(payload: String): String? {
        val root = JsonParser.parseString(payload).asJsonObject
        val choices = root.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) {
            return null
        }
        val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return null
        val content = message.get("content") ?: return null
        return if (content.isJsonNull) null else content.asString
    }
}
