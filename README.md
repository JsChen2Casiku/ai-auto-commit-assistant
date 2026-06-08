# AI Commit Assistant

JetBrains IDE plugin that generates commit messages from the changes selected in the Commit tool window.

## MVP

- Adds an `AI Commit Message` action to the commit message action group.
- Reads only the currently included changes from the IntelliJ Commit workflow.
- Sends a capped unified diff to an OpenAI-compatible `/chat/completions` endpoint.
- Streams the generated message back into the Commit Message field.
- Stores the API key in JetBrains PasswordSafe.

## Development

```powershell
./gradlew runIde
./gradlew verifyPlugin
./gradlew buildPlugin
```
