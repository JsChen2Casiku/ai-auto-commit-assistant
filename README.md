# AI Commit Assistant

[中文文档](README.zh-CN.md)

AI Commit Assistant is a JetBrains IDE plugin that generates commit messages from the changes currently selected in the Commit tool window. It works with OpenAI-compatible chat completion APIs, supports multiple provider channels, streams generated text into the commit message input, and can hide model reasoning output before it reaches the final commit message.

The plugin is designed for everyday Git workflows in IntelliJ IDEA and other JetBrains IDEs that support the VCS Commit workflow.

## Features

- Generate commit messages directly from the JetBrains Commit tool window.
- Read only the changes currently included in the commit workflow, not the whole working tree.
- Send a unified diff to an OpenAI-compatible `/chat/completions` endpoint.
- Support streaming and non-streaming generation.
- Stream generated output directly into the commit message input.
- Hide reasoning content such as `<think>...</think>`, `<thinking>...</thinking>`, `<reasoning>...</reasoning>`, and common reasoning prefixes.
- Show a generation overlay while the AI is working, including a dedicated `AI 正在思考中` state when reasoning content is detected.
- Support Chinese and English UI labels in the settings page.
- Use Chinese as the default output language.
- Support multiple prompt templates: Conventional Commits, Simple, Detailed, and Custom.
- Show the custom prompt editor only when the Custom prompt template is selected.
- Support multiple API channels with a channel name, Base URL, API key, and model.
- Add, switch, and delete channels from the settings page.
- Fetch available models from the configured provider with one click.
- Automatically normalize provider Base URLs by appending `/v1` when the URL has no path.
- Automatically recommend model context token limits from provider metadata, `models.dev`, or model-name heuristics.
- Truncate the diff only when it exceeds the selected model context budget.
- Store API keys in JetBrains PasswordSafe instead of the regular settings XML.
- Migrate settings and API keys from earlier plugin IDs or setting file names when possible.

## Requirements

- JetBrains IDE based on build `243` or later.
- Java 17 runtime for development.
- An OpenAI-compatible API provider that supports:
  - `GET /models` for model discovery.
  - `POST /chat/completions` for commit message generation.
  - Server-sent events for streaming if streaming output is enabled.

The plugin has been configured for IntelliJ IDEA Community `2024.3.6` during development and verification.

## Installation

### Install From Local Build

1. Build the plugin ZIP:

```powershell
.\gradlew.bat buildPlugin
```

2. Open the JetBrains IDE.
3. Go to `Settings` -> `Plugins` -> `Install Plugin from Disk...`.
4. Select the ZIP file from:

```text
build/distributions/
```

5. Restart the IDE if prompted.

### Run In Development IDE

```powershell
.\gradlew.bat runIde
```

## Quick Start

1. Open `Settings` -> `Tools` -> `AI Commit Assistant`.
2. Fill in the channel configuration:
   - `Channel name`: a readable name for the provider, for example `OpenAI`, `DeepSeek`, or `Local`.
   - `Base URL`: the provider endpoint root, for example `https://api.openai.com` or `https://api.openai.com/v1`.
   - `API key`: the provider API key.
3. Click `Connect`.
4. Select a model from the fetched model list, or type a custom model name.
5. Choose the output language, prompt template, context token behavior, timeout, streaming, and reasoning filter options.
6. Apply the settings.
7. Open the Commit tool window.
8. Select the changes that should be included in the commit.
9. Click `AI Commit Assistant` in the commit message action area.
10. Review the generated commit message before committing.

The action overwrites the current commit message directly. There is no additional confirmation dialog.

## Settings

### Channel Name

The channel name identifies the current provider configuration. It is stored with the channel and is used for quick switching.

Use the `Channels` button next to the channel name to:

- Switch to another configured channel.
- Add a new channel.
- Delete the current channel.

At least one channel is always kept.

### Base URL

The Base URL is the root URL of an OpenAI-compatible API provider.

If the URL has no path, the plugin automatically appends `/v1`.

Examples:

```text
https://api.openai.com
```

is normalized to:

```text
https://api.openai.com/v1
```

The plugin then calls:

```text
GET  {baseUrl}/models
POST {baseUrl}/chat/completions
```

If the configured URL already contains a path, the plugin keeps it unchanged.

### API Key

API keys are stored in JetBrains PasswordSafe. They are not written into `aiCommitAssistant.xml`.

Each channel has its own API key entry. The default channel also keeps compatibility with earlier credential keys.

### Connect Button

The `Connect` button uses the current Base URL and API key to request the provider model list from `/models`.

After a successful connection:

- The Base URL field is updated to the normalized value.
- Available models are loaded into the model selector.
- Context token metadata is stored when the provider returns it.
- Missing context token data is supplemented from `https://models.dev/api.json` when possible.

If the request fails, the error dialog includes the HTTP status, request URL, and a short response summary.

### Model

The model selector is editable. You can select a fetched model or type a model name manually.

The selected model is used in the `/chat/completions` request body:

```json
{
  "model": "selected-model",
  "messages": [],
  "temperature": 0.2,
  "stream": true
}
```

### Language

The language controls the generated commit message language and the settings page labels.

Supported values:

- `中文`
- `English`

The default value is `中文`.

### Prompt Template

The plugin supports four prompt styles:

- `Conventional Commits`: generates messages such as `feat: add model selector`.
- `Simple`: generates a short, direct commit message.
- `Detailed`: generates a more descriptive commit message.
- `Custom`: uses your custom prompt.

The custom prompt text area appears only when `Custom` is selected.

### Model Context Tokens

This value is the selected model's maximum context token count. It is used to calculate the diff token budget.

When `Auto from selected model` is enabled, the plugin resolves the value in this order:

1. Provider model metadata from `/models`.
2. `models.dev` model metadata.
3. Built-in model-name heuristics.
4. Conservative default value: `128,000`.

The accepted range is `4,096` to `2,000,000` tokens.

The plugin attempts to keep the full diff. It truncates only when the estimated diff tokens exceed the calculated model context budget. This avoids the older behavior of cutting by a fixed character count.

### Timeout

The timeout value controls both connection timeout and request timeout. The supported range is `5` to `300` seconds.

### Streaming

`Stream generated result into commit input` controls whether generation uses streaming output.

When enabled:

- The request sets `"stream": true`.
- The plugin reads server-sent events from the provider.
- Visible generated content is progressively written into the commit message input.

When disabled:

- The request waits for a full JSON response.
- The final generated message is written into the commit message input once.

### Thinking Filter

`Hide reasoning from model output` controls whether model reasoning text is removed from previews and final messages.

When enabled, the plugin removes:

- `<think>...</think>`
- `<thinking>...</thinking>`
- `<reasoning>...</reasoning>`
- Unclosed reasoning tags.
- Common reasoning prefixes such as `analysis:`, `reasoning:`, `thought process:`, `思考过程:`, and `分析:`.

During streaming generation, if reasoning content is detected before visible commit text is available, the overlay shows an AI thinking state instead of writing reasoning text into the commit message input.

## Generation Flow

When the action is triggered from the Commit tool window:

1. The plugin reads the changes currently included in the commit workflow.
2. It builds a file summary and unified diff.
3. It calculates a diff token budget from the selected model context tokens.
4. It truncates the diff only if the estimated token count exceeds the budget.
5. It builds the system and user prompt from the selected prompt template and language.
6. It sends the request to the configured OpenAI-compatible provider.
7. It displays a loading overlay while waiting for output.
8. It streams or writes the sanitized commit message into the commit input.
9. It removes the overlay when generation completes or fails.

Only selected commit changes are sent to the provider.

## Privacy And Data Handling

- The plugin sends selected diff content, file paths, and prompt instructions to the configured API provider.
- It does not send unselected changes from the Commit tool window.
- API keys are stored through JetBrains PasswordSafe.
- General settings are stored in the IDE options file `aiCommitAssistant.xml`.
- The plugin does not include provider-specific compatibility hacks; it targets OpenAI-compatible APIs.

Review generated commit messages before committing, especially when the diff contains sensitive code or internal file paths.

## Configuration Migration

The current plugin ID is:

```text
com.casiku.aca
```

Settings are persisted under:

```text
aiCommitAssistant.xml
```

The plugin includes migration support for earlier setting file names, component hints, package names, service names, and credential account names. This is intended to preserve existing configuration after plugin ID or package changes.

The migration attempts to import:

- Channels.
- Current channel.
- Channel name.
- Base URL.
- Model.
- Language.
- Prompt style.
- Custom prompt.
- Model context tokens.
- Timeout.
- Streaming preference.
- Thinking filter preference.
- API key.

API keys are migrated into the current PasswordSafe credential location if the target credential is missing.

## Troubleshooting

### `404` When Connecting Or Generating

Check whether the provider expects `/v1`.

If your Base URL is only a domain, the plugin automatically appends `/v1`. For example:

```text
https://example-provider.com
```

becomes:

```text
https://example-provider.com/v1
```

If your provider uses a custom path, enter the full path expected by the provider.

### No Models Are Loaded

Verify that:

- The Base URL is correct.
- The API key has permission to call `/models`.
- The provider returns an OpenAI-compatible JSON object with a `data` array.
- The network request is not blocked by proxy, firewall, or provider restrictions.

You can still type a model name manually if the provider does not expose `/models`.

### Reasoning Text Appears In The Commit Message

Enable `Hide reasoning from model output`.

The plugin filters common reasoning formats, but providers may use proprietary fields or unusual text formats. If reasoning still appears, adjust the selected model or use a custom prompt that explicitly forbids reasoning output.

### The Diff Is Truncated

The plugin truncates only when the estimated diff token count exceeds the budget derived from the selected model context tokens.

To reduce truncation:

- Use a model with a larger context window.
- Enable automatic context tokens.
- Click `Connect` so provider metadata and `models.dev` metadata can be used.
- Split very large commits into smaller commits.

### Settings Disappear After Updating

The plugin includes migration logic for earlier IDs and setting file names. If configuration is still missing:

- Open `Settings` -> `Tools` -> `AI Commit Assistant` once to trigger settings initialization.
- Verify that the installed plugin ID is `com.casiku.aca`.
- Check whether the previous settings XML exists in the IDE config `options` directory.
- Re-enter the API key if the operating system credential store does not allow migration.

## Development

### Common Commands

Run a development IDE:

```powershell
.\gradlew.bat runIde
```

Build the plugin:

```powershell
.\gradlew.bat buildPlugin
```

Verify the plugin:

```powershell
.\gradlew.bat verifyPlugin
```

### Project Metadata

- Plugin ID: `com.casiku.aca`
- Plugin name: `AI Commit Assistant`
- Vendor: `2Casiku`
- Gradle group: `com.casiku`
- Kotlin JVM plugin: `2.0.21`
- IntelliJ Platform Gradle plugin: `2.5.0`
- Development IDE target: `IC 2024.3.6`
- Minimum IDE build: `243`
- JVM toolchain: `17`

### Source Layout

```text
src/main/kotlin/com/casiku/aca/actions       Commit action and action ordering
src/main/kotlin/com/casiku/aca/ai            OpenAI-compatible provider and output sanitizer
src/main/kotlin/com/casiku/aca/diff          Commit diff collection and token budget estimation
src/main/kotlin/com/casiku/aca/notification  IDE notifications
src/main/kotlin/com/casiku/aca/prompt        Prompt construction
src/main/kotlin/com/casiku/aca/settings      Settings UI, persistence, channels, model fetching, migration
src/main/kotlin/com/casiku/aca/ui            Icons and generation overlay
src/main/resources/META-INF/plugin.xml       Plugin declaration
src/main/resources/META-INF/pluginIcon.svg   Marketplace/plugin icon
src/main/resources/icons/aiCommit.svg        Commit action icon
```

## License

No license file is currently included in this repository. Add one before distributing the plugin publicly if needed.
