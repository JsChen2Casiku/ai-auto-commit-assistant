# AI Commit Assistant

[English](README.md)

AI Commit Assistant 是一个 JetBrains IDE 插件，用于根据 Commit 工具窗口中当前勾选的 Git 变更自动生成提交信息。插件支持 OpenAI 兼容的 Chat Completions 接口，支持多渠道配置、模型列表拉取、流式写入提交输入框、思考过程屏蔽、模型上下文 token 自动获取，以及中文优先的设置体验。

该插件面向 IntelliJ IDEA 以及其他支持 VCS Commit 工作流的 JetBrains IDE。

## 功能特性

- 在 JetBrains Commit 工具窗口中一键生成提交信息。
- 只读取当前提交工作流中已经勾选的变更，不读取整个工作区。
- 将统一 diff 发送到 OpenAI 兼容的 `/chat/completions` 接口。
- 支持流式生成和非流式生成。
- 支持将生成结果流式写入提交输入框。
- 支持隐藏模型输出中的思考过程。
- 支持清理 `<think>...</think>`、`<thinking>...</thinking>`、`<reasoning>...</reasoning>` 以及常见思考前缀。
- 生成过程中在提交输入框上显示蒙版和动态加载效果。
- 检测到思考内容且还没有可展示提交信息时，显示 `AI 正在思考中` 状态。
- 设置页支持中文和 English，默认中文。
- 支持中文和英文提交信息生成。
- 支持多种提示词模板：约定式提交、简洁、详细、自定义。
- 只有选择自定义提示词模板时才展示自定义提示词输入框。
- 支持多渠道配置，每个渠道包含渠道名称、Base URL、API Key 和模型。
- 支持在设置页新增、切换、删除渠道。
- 支持点击连接按钮自动拉取可用模型。
- 当 Base URL 没有路径时，自动补全 `/v1`。
- 支持根据模型接口返回、`models.dev` 或模型名称自动推荐模型上下文 token 数。
- 只有当 diff 超出模型上下文预算时才进行截断。
- API Key 使用 JetBrains PasswordSafe 保存，不写入普通配置 XML。
- 支持从早期插件 ID、配置文件名、凭据名称中迁移配置和 API Key。

## 运行要求

- JetBrains IDE build `243` 或更高版本。
- 开发环境需要 Java 17。
- 一个 OpenAI 兼容的 API 服务，最好支持：
  - `GET /models`，用于模型列表拉取。
  - `POST /chat/completions`，用于生成提交信息。
  - 如果启用流式输出，需要支持 Server-Sent Events。

当前项目开发和验证使用的是 IntelliJ IDEA Community `2024.3.6`。

## 安装方式

### 从本地构建安装

1. 构建插件 ZIP：

```powershell
.\gradlew.bat buildPlugin
```

2. 打开 JetBrains IDE。
3. 进入 `Settings` -> `Plugins` -> `Install Plugin from Disk...`。
4. 选择以下目录中的 ZIP 文件：

```text
build/distributions/
```

5. 根据 IDE 提示重启。

### 使用开发 IDE 运行

```powershell
.\gradlew.bat runIde
```

## 快速开始

1. 打开 `Settings` -> `Tools` -> `AI Commit Assistant`。
2. 填写渠道配置：
   - `渠道名称`：用于识别当前服务商，例如 `OpenAI`、`DeepSeek`、`Local`。
   - `Base URL`：服务商接口根地址，例如 `https://api.openai.com` 或 `https://api.openai.com/v1`。
   - `API Key`：服务商 API Key。
3. 点击 `连接`。
4. 从拉取到的模型列表中选择模型，或者手动输入模型名称。
5. 根据需要选择语言、提示词模板、模型上下文 token、超时时间、流式输出和思考屏蔽。
6. 点击应用保存设置。
7. 打开 Commit 工具窗口。
8. 勾选需要提交的变更。
9. 点击提交信息区域中的 `AI Commit Assistant`。
10. 检查生成的提交信息后再提交。

触发生成时，插件会直接覆盖当前提交输入框内容，不再弹出二次确认。

## 设置说明

### 渠道名称

渠道名称用于标识当前 API 服务配置，并用于后续快速切换。

点击渠道名称右侧的 `渠道` 按钮可以：

- 切换到其他已配置渠道。
- 新增渠道。
- 删除当前渠道。

插件会始终保留至少一个渠道。

### Base URL

Base URL 是 OpenAI 兼容 API 服务的根地址。

如果填写的 URL 没有路径，插件会自动补全 `/v1`。

示例：

```text
https://api.openai.com
```

会被规范化为：

```text
https://api.openai.com/v1
```

随后插件会调用：

```text
GET  {baseUrl}/models
POST {baseUrl}/chat/completions
```

如果填写的 URL 已经包含路径，插件会保持原样，不会强行追加 `/v1`。

### API Key

API Key 使用 JetBrains PasswordSafe 保存，不会写入 `aiCommitAssistant.xml`。

每个渠道都有独立的 API Key。默认渠道还会兼容早期版本的凭据存储方式。

### 连接按钮

`连接` 按钮会使用当前 Base URL 和 API Key 请求服务商的 `/models` 接口。

连接成功后：

- Base URL 会更新为规范化后的地址。
- 可用模型会加载到模型下拉框中。
- 如果服务商返回了模型上下文长度，会记录到当前模型。
- 如果服务商没有返回上下文长度，插件会尝试从 `https://models.dev/api.json` 获取。

如果连接失败，错误弹窗会展示 HTTP 状态码、请求地址和响应摘要，便于定位 404、鉴权失败或接口不兼容问题。

### 模型

模型下拉框支持编辑。可以选择连接后拉取到的模型，也可以手动输入模型名称。

生成提交信息时，模型会写入 `/chat/completions` 请求体：

```json
{
  "model": "selected-model",
  "messages": [],
  "temperature": 0.2,
  "stream": true
}
```

### 语言

语言会影响生成的提交信息语言以及设置页文案。

支持：

- `中文`
- `English`

默认值是 `中文`。

### 提示词模板

插件支持四种提示词模板：

- `Conventional Commits`：生成类似 `feat: add model selector` 的约定式提交信息。
- `Simple`：生成简短直接的提交信息。
- `Detailed`：生成更详细的提交信息。
- `Custom`：使用自定义提示词。

只有选择 `Custom` 时，才会展示自定义提示词输入框。

### 模型上下文 token 数

该配置表示当前模型的最大上下文 token 数，用于计算可以发送给模型的 diff token 预算。

开启 `根据当前模型自动获取` 后，插件按以下顺序获取或推断：

1. 优先读取 `/models` 接口返回的模型上下文元数据。
2. 如果服务商未返回，尝试从 `models.dev` 获取模型上下文信息。
3. 如果仍然无法获取，根据模型名称进行内置估算。
4. 如果无法识别模型，使用保守默认值 `128,000`。

允许范围是 `4,096` 到 `2,000,000` tokens。

插件会尽量保留完整 diff。只有当估算后的 diff token 数超过模型上下文预算时，才会截断 diff。这替代了早期按固定字符数截断的方式，可以减少因为字符截断导致上下文不完整的问题。

### 超时时间

超时时间同时用于连接超时和请求超时。支持范围是 `5` 到 `300` 秒。

### 流式输出

`生成结果流式写提交输入框` 控制是否启用流式生成。

开启后：

- 请求体中会设置 `"stream": true`。
- 插件会读取服务商返回的 Server-Sent Events。
- 可展示的提交信息会逐步写入提交输入框。

关闭后：

- 插件等待完整 JSON 响应。
- 最终生成结果一次性写入提交输入框。

### 思考屏蔽

`隐藏模型输出中的思考过程` 控制是否清理模型输出中的思考内容。

开启后，插件会清理：

- `<think>...</think>`
- `<thinking>...</thinking>`
- `<reasoning>...</reasoning>`
- 未闭合的思考标签。
- `analysis:`、`reasoning:`、`thought process:`、`思考过程:`、`分析:` 等常见思考前缀。

流式生成时，如果检测到思考内容且还没有可展示提交信息，提交输入框上方的蒙版会显示 AI 思考状态，而不是把思考过程写入提交输入框。

## 生成流程

从 Commit 工具窗口触发操作后，插件会执行以下流程：

1. 读取当前提交工作流中已勾选的变更。
2. 构建文件摘要和统一 diff。
3. 根据当前模型上下文 token 数计算 diff token 预算。
4. 如果 diff 超过预算，按 token 估算进行截断。
5. 根据语言和提示词模板构建 system prompt 和 user prompt。
6. 调用配置渠道的 OpenAI 兼容接口。
7. 在提交输入框上显示加载蒙版。
8. 将清理后的提交信息流式或一次性写入提交输入框。
9. 生成完成或失败后移除蒙版。

插件只发送已勾选的提交变更，不会发送未勾选的变更。

## 隐私和数据处理

- 插件会将已勾选变更的 diff、文件路径和提示词发送到当前配置的 API 服务商。
- 插件不会发送 Commit 工具窗口中未勾选的变更。
- API Key 使用 JetBrains PasswordSafe 保存。
- 普通配置保存在 IDE options 目录下的 `aiCommitAssistant.xml`。
- 插件面向 OpenAI 兼容接口，不包含特定服务商的专用兼容逻辑。

如果 diff 中包含敏感代码、内部路径或凭据，请在生成和提交前自行检查。

## 配置迁移

当前插件 ID 是：

```text
com.casiku.aca
```

当前配置文件是：

```text
aiCommitAssistant.xml
```

插件内置了迁移逻辑，用于从早期配置文件名、组件名、包名、PasswordSafe 服务名和账号名中恢复配置，尽量避免插件更新或包名修改后配置丢失。

迁移内容包括：

- 渠道列表。
- 当前渠道。
- 渠道名称。
- Base URL。
- 模型。
- 语言。
- 提示词模板。
- 自定义提示词。
- 模型上下文 token 数。
- 超时时间。
- 流式输出开关。
- 思考屏蔽开关。
- API Key。

如果新的 PasswordSafe 凭据不存在，插件会尝试把旧 API Key 迁移到当前凭据位置。

## 常见问题

### 连接或生成时出现 `404`

优先检查服务商接口是否需要 `/v1`。

如果 Base URL 只填写域名，插件会自动补全 `/v1`。例如：

```text
https://example-provider.com
```

会变为：

```text
https://example-provider.com/v1
```

如果服务商使用自定义路径，请填写服务商要求的完整路径。

### 点击连接后没有模型

请检查：

- Base URL 是否正确。
- API Key 是否有权限访问 `/models`。
- 服务商是否返回 OpenAI 兼容格式，并包含 `data` 数组。
- 网络请求是否被代理、防火墙或服务商策略拦截。

如果服务商不提供 `/models` 接口，也可以手动输入模型名称。

### 提交信息中仍出现思考内容

请开启 `隐藏模型输出中的思考过程`。

插件会过滤常见思考格式，但不同服务商或模型可能使用特殊字段或非常规文本格式。如果仍然出现思考内容，可以更换模型，或者使用自定义提示词明确禁止输出思考过程。

### diff 被截断

插件只会在 diff token 估算超过模型上下文预算时截断。

减少截断的方法：

- 使用上下文更大的模型。
- 开启 `根据当前模型自动获取`。
- 点击 `连接`，让插件读取服务商模型元数据和 `models.dev` 数据。
- 将过大的提交拆成更小的提交。

### 更新插件后配置不见了

插件已经实现旧配置迁移。如果仍然没有恢复：

- 打开一次 `Settings` -> `Tools` -> `AI Commit Assistant`，触发配置初始化。
- 确认当前安装的插件 ID 是 `com.casiku.aca`。
- 检查 IDE config 的 `options` 目录中是否仍存在旧配置 XML。
- 如果操作系统凭据存储不允许迁移，请重新输入 API Key。

## 开发说明

### 常用命令

启动开发 IDE：

```powershell
.\gradlew.bat runIde
```

构建插件：

```powershell
.\gradlew.bat buildPlugin
```

验证插件：

```powershell
.\gradlew.bat verifyPlugin
```

### 项目信息

- 插件 ID：`com.casiku.aca`
- 插件名称：`AI Commit Assistant`
- 作者：`2Casiku`
- Gradle group：`com.casiku`
- Kotlin JVM 插件版本：`2.0.21`
- IntelliJ Platform Gradle 插件版本：`2.5.0`
- 开发目标 IDE：`IC 2024.3.6`
- 最低 IDE build：`243`
- JVM toolchain：`17`

### 目录结构

```text
src/main/kotlin/com/casiku/aca/actions       Commit 动作和动作排序
src/main/kotlin/com/casiku/aca/ai            OpenAI 兼容接口调用和输出清理
src/main/kotlin/com/casiku/aca/diff          Commit diff 采集和 token 预算估算
src/main/kotlin/com/casiku/aca/notification  IDE 通知
src/main/kotlin/com/casiku/aca/prompt        Prompt 构建
src/main/kotlin/com/casiku/aca/settings      设置页、持久化、多渠道、模型拉取、迁移
src/main/kotlin/com/casiku/aca/ui            图标和生成中蒙版
src/main/resources/META-INF/plugin.xml       插件声明
src/main/resources/META-INF/pluginIcon.svg   插件图标
src/main/resources/icons/aiCommit.svg        Commit 动作图标
```

## License

当前仓库暂未包含 License 文件。如果需要公开分发插件，建议先补充明确的开源协议或授权说明。
