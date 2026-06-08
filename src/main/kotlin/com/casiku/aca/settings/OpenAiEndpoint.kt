package com.casiku.aca.settings

import java.net.URI

object OpenAiEndpoint {
    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return trimmed
        }

        val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return trimmed
        val path = uri.rawPath.orEmpty().trimEnd('/')

        if (path.isBlank()) {
            return replacePath(uri, "/v1")
        }

        return trimmed
    }

    fun modelsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/models"

    fun chatCompletionsUrl(baseUrl: String): String = "${normalizeBaseUrl(baseUrl)}/chat/completions"

    private fun replacePath(uri: URI, path: String): String =
        URI(uri.scheme, uri.userInfo, uri.host, uri.port, path, uri.query, uri.fragment).toString()
}

class ModelFetchException(
    val statusCode: Int,
    val endpoint: String,
    body: String,
) : IllegalStateException(buildMessage(statusCode, endpoint, body)) {
    companion object {
        private fun buildMessage(statusCode: Int, endpoint: String, body: String): String {
            val cleaned = cleanBody(body)
            return buildString {
                append("HTTP $statusCode\n")
                append("请求地址：$endpoint")
                if (cleaned.isNotBlank()) {
                    append("\n响应摘要：$cleaned")
                }
            }
        }

        private fun cleanBody(body: String): String =
            body
                .replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(220)
    }
}
