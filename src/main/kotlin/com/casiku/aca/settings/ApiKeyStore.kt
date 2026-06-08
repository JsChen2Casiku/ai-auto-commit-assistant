package com.casiku.aca.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object ApiKeyStore {
    private const val SERVICE_NAME = "Ai Auto Commit Assistant"
    private const val ACCOUNT_NAME = "openai-compatible"
    private val legacyServiceNames = listOf(
        "Ai Auto Commit Assistant",
        "AI Commit Assistant",
        "AI Commit Helper",
        "AI Commit Message",
        "AiCommitAssistant",
        "ai-commit-assistant",
        "com.github.casiku.aicommitassistant",
        "com.github.casiku.ai-commit-assistant",
        "com.casiku.aca",
    )
    private val legacyAccountNames = listOf(
        ACCOUNT_NAME,
        "api-key",
        "apiKey",
        "apikey",
        "openai",
        "default",
    )

    private val attributes: CredentialAttributes
        get() = CredentialAttributes(generateServiceName(SERVICE_NAME, ACCOUNT_NAME))

    private fun channelAttributes(channelId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SERVICE_NAME, "channel:$channelId"))

    fun getApiKey(): String? =
        PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }
            ?: findLegacyApiKey()?.also { setApiKey(it) }

    fun getApiKey(channelId: String): String? =
        PasswordSafe.instance.getPassword(channelAttributes(channelId))?.takeIf { it.isNotBlank() }
            ?: findLegacyChannelApiKey(channelId)?.also { setApiKey(channelId, it) }
            ?: if (channelId == "default") getApiKey() else null

    fun setApiKey(apiKey: String) {
        val value = apiKey.trim()
        val credentials = if (value.isBlank()) null else Credentials(ACCOUNT_NAME, value)
        PasswordSafe.instance.set(attributes, credentials)
    }

    fun setApiKey(channelId: String, apiKey: String) {
        val value = apiKey.trim()
        val credentials = if (value.isBlank()) null else Credentials("channel:$channelId", value)
        PasswordSafe.instance.set(channelAttributes(channelId), credentials)
        if (channelId == "default") {
            setApiKey(value)
        }
    }

    fun migrateApiKeyIfMissing(channelId: String, apiKey: String?) {
        if (apiKey.isNullOrBlank()) return
        if (getApiKey(channelId).isNullOrBlank()) {
            setApiKey(channelId, apiKey)
        }
    }

    private fun findLegacyApiKey(): String? {
        legacyServiceNames.forEach { serviceName ->
            legacyAccountNames.forEach { accountName ->
                val value = PasswordSafe.instance.getPassword(
                    CredentialAttributes(generateServiceName(serviceName, accountName))
                )
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }

    private fun findLegacyChannelApiKey(channelId: String): String? {
        legacyServiceNames.forEach { serviceName ->
            listOf("channel:$channelId", channelId).forEach { accountName ->
                val value = PasswordSafe.instance.getPassword(
                    CredentialAttributes(generateServiceName(serviceName, accountName))
                )
                if (!value.isNullOrBlank()) return value
            }
        }
        return null
    }
}
