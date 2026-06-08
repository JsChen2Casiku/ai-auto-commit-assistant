package com.casiku.aca.settings

data class ModelContextRecommendation(
    val contextTokens: Int,
    val source: ModelContextRecommendationSource,
)

enum class ModelContextRecommendationSource {
    PROVIDER_METADATA,
    MODELS_DEV,
    MODEL_NAME,
    DEFAULT,
}

object ModelContextTokenAdvisor {
    const val DEFAULT_CONTEXT_TOKENS: Int = 128_000
    const val MIN_CONTEXT_TOKENS: Int = 4_096
    const val HARD_MAX_CONTEXT_TOKENS: Int = 2_000_000

    fun recommend(
        modelName: String,
        providerContextTokens: Int? = null,
        modelsDevContextTokens: Int? = null,
    ): ModelContextRecommendation {
        providerContextTokens?.takeIf { it > 0 }?.let {
            return ModelContextRecommendation(normalize(it), ModelContextRecommendationSource.PROVIDER_METADATA)
        }
        modelsDevContextTokens?.takeIf { it > 0 }?.let {
            return ModelContextRecommendation(normalize(it), ModelContextRecommendationSource.MODELS_DEV)
        }
        estimateContextTokens(modelName)?.let {
            return ModelContextRecommendation(normalize(it), ModelContextRecommendationSource.MODEL_NAME)
        }
        return ModelContextRecommendation(DEFAULT_CONTEXT_TOKENS, ModelContextRecommendationSource.DEFAULT)
    }

    private fun normalize(contextTokens: Int): Int =
        contextTokens.coerceIn(MIN_CONTEXT_TOKENS, HARD_MAX_CONTEXT_TOKENS)

    private fun estimateContextTokens(modelName: String): Int? {
        val model = modelName.lowercase()
        if (model.isBlank()) return null

        return when {
            model.contains("1m") || model.contains("1000k") || model.contains("million") -> 1_000_000
            model.contains("gemini-1.5") || model.contains("gemini-2.") || model.contains("gemini-3") -> 1_000_000
            model.contains("gpt-4.1") -> 1_000_000
            model.contains("gpt-5") -> 400_000
            model.contains("claude") -> 200_000
            model.contains("o1") || model.contains("o3") || model.contains("o4") -> 200_000
            model.contains("gpt-4o") || model.contains("gpt-4-turbo") -> 128_000
            model.contains("gpt-4") -> 128_000
            model.contains("gpt-3.5") -> 16_000
            model.contains("deepseek") -> 64_000
            model.contains("qwen") && (model.contains("long") || model.contains("max")) -> 1_000_000
            model.contains("qwen") -> 128_000
            model.contains("llama-3.1") || model.contains("llama-3.2") || model.contains("llama-3.3") -> 128_000
            model.contains("mistral") || model.contains("glm-4") || model.contains("kimi") || model.contains("moonshot") -> 128_000
            model.contains("mixtral") -> 32_000
            model.contains("yi-") -> 32_000
            else -> null
        }
    }
}
