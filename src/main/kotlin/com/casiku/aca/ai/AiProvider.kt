package com.casiku.aca.ai

import com.casiku.aca.prompt.CommitPrompt
import com.intellij.openapi.progress.ProgressIndicator

fun interface TokenConsumer {
    fun accept(token: String)
}

interface AiProvider {
    fun generate(prompt: CommitPrompt, indicator: ProgressIndicator, onToken: TokenConsumer): String
}
