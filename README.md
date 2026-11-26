# deepsleep

给低端安卓手机定制的轻量级 AI 聊天 App。交互参考 DeepSeek 移动端，但所有模型请求都走**用户自己的 OpenAI 兼容 API**；纯本地存储、无账号、无后端、无统计上报。

- 默认包名：`com.deepsleep.chat`
- 最低系统：Android 8.0（API 26），面向 Android 11–12 的低端机优化
- Release APK：约 **2 MB**（R8 + 资源压缩后）
- 技术栈：Kotlin + Jetpack Compose + Material 3、Room、DataStore、OkHttp

## 功能

- 多轮对话、SSE 流式输出、停止生成、重新生成、编辑重发
- 每次请求自动注入手机当前日期/时间/时区，模型能正确回答“今天几号/现在几点/最新”
- 流式过程中**实时渲染** Markdown（标题、表格、代码块边生成边显示）
- 深度思考（reasoning）实时折叠展示，完成后显示思考耗时；未配置推理模型时会弹窗选择并保存
- 关闭深度思考时忽略并隐藏服务商仍返回的 `reasoning_content`；每条消息记录自己的思考开关，历史思考记录不受影响
- Markdown、表格、代码块横向滚动与一键复制；公式以原文显示（轻量取舍）
- 多套 Provider：DeepSeek 官方、OpenRouter、硅基流动、Ollama、任意 OpenAI 兼容接口
- 模型切换、拉取 `/models`、连接测试、自定义请求头/温度/最大 Token/系统提示词
- 会话列表、搜索、重命名、置顶、删除、按日期分组、自动标题
- 联网搜索：接入 Tavily / Serper / SearXNG，结果注入提示词并展示真实来源；未配置时点开关会直接引导配置
- txt/md 附件本地读取并注入上下文（最多 2MB/文件、2 万字/文件）
- 相册图片附件：多选、自动压缩（长边 1600、JPEG）、缩略图预览、点击查看大图；以 OpenAI `image_url` 多模态格式发送给支持视觉的模型
- 按住说话（按下开始、松手结束），优先使用系统 `SpeechRecognizer`；系统没有语音服务的机型（vivo 等）可配置语音转写模型，改为本地录音 + 云端转写
- App 内检查更新：读取 GitHub Releases（或自定义 JSON），下载、校验签名/版本后直接调起系统安装，无需手动传 APK
- DeepSeek 官方备份 ZIP（format v3）导入/导出
- 本 App 自身完整备份（不含 API Key）
- 深色/浅色/跟随系统主题、token 用量展示、复制/分享消息
- API Key 使用 Android Keystore AES-GCM 加密后保存；禁止系统备份导出

## 明确不做（轻量取舍）

PDF/DOCX 解析、本地图片 OCR、LaTeX 排版、代码语法高亮、登录/云同步、推送、埋点。图片只作为多模态内容发给模型，不在本地识别文字；DeepSeek 官方 API 不支持图片输入，需要换用支持视觉的 OpenAI 兼容模型。官方备份中的图片只保留文件名，不导入图片正文；附件在官方格式导出时也只写元数据（完整恢复请用本 App 备份）。

## 构建

环境：JDK 17+（本项目在 JDK 21 验证）、Android SDK Platform 36、Build Tools 35.0.1。

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

`local.properties` 需要指向本机 SDK：

```properties
sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk
```

### 依赖版本

| 组件 | 版本 |
|---|---|
| Gradle | 8.14.3 |
| AGP | 8.13.2 |
| Kotlin / Compose 编译器 | 2.3.21 |
| Compose BOM | 2026.06.01（Compose 1.11.4 / Material3 1.4.0） |
| Room / KSP | 2.8.5 / 2.3.14 |
| Navigation / Lifecycle / Activity | 2.9.8 / 2.9.4 / 1.13.0 |
| OkHttp | 4.12.0 |
| Markwon | 4.6.2 |

不要单独升级 Compose 到 1.12+：它要求 compileSdk 37 + AGP 9；当前项目按 compileSdk 36 固定。

### 签名

`keystore.properties` 和 `keystore/deepsleep-release.jks` 已被 `.gitignore` 忽略，但**必须自己备份**：

- keystore 密码 / alias 见 `keystore.properties`
- 如果丢失，后续版本无法覆盖安装或升级，只能卸载重装（本地数据会丢）

## 安装

有 adb：

```bat
adb install -r app\build\outputs\apk\release\app-release.apk
```

没有 adb：把 APK 传到手机，在文件管理器中允许“安装未知应用”后安装。

## 配置 API

首次启动没有 Provider 时，点「添加 API 配置」：

1. 选择「DeepSeek 官方」预设，填入 API Key；
2. 或填写任意 OpenAI 兼容地址（一般以 `/v1` 结尾）；
3. 可点「测试连接」/「拉取模型」；
4. 选择默认模型；DeepSeek 可把 `deepseek-reasoner` 填为深度思考模型；
5. 保存后回到聊天页。

Ollama 等局域网服务允许 HTTP 地址（App 已开启 cleartext），Key 可留空。API Key 只保存在本机 Keystore 加密数据里，不会写进备份或日志。

## DeepSeek 备份导入/导出

### 导入

1. 在 DeepSeek App/网页端：设置 → 数据管理 → 导出所有历史对话；
2. 在浏览器打开下载链接（有效期约 7 天），得到 ZIP；
3. 本 App：设置 → 备份与恢复 → 导入 DeepSeek 备份（ZIP）；
4. 确认预览后导入。

规则与限制：

- 支持 DeepSeek 导出格式 **v3**（`manifest.json` + `chat_sessions/*.json` + `chat_session_index.json`）；
- 只导入每个会话的**当前分支**，其他分支丢弃；
- `REQUEST/RESPONSE` → 正文，`THINK` → 思考内容与耗时，`TOOL_SEARCH` → 参考来源，`FILE` → 附件文件名；
- 图片文件不会导入；
- 默认跳过已导入过的会话，可选择全部新建；
- 导入后对话会使用当前默认 Provider/模型继续聊天。

### 导出

设置 → 备份与恢复 → 导出为 DeepSeek 格式。生成 format v3 ZIP，包含消息树、思考内容、参考来源和附件元数据；非图片附件没有正文，图片不导出。若需要完整备份（含附件文本），请用「导出本 App 备份」。

### 本 App 备份

- 包含：所有对话、消息、思考内容、来源、附件文本、图片文件（`images/` 目录）、设置、Provider（不含 API Key）；
- 不包含：API Key、搜索 API Key；
- 换机导入后需重新填写 Key。
- 导入大小上限为 **1GB**；导入时会先复制到临时缓存，需要大致等于备份大小的可用存储空间。DeepSeek 官方备份中的图片不会被读取进内存，只处理会话 JSON。

## 联网搜索

DeepSeek 官方 API 不提供内置搜索，所以联网搜索需要自备搜索服务：

1. 设置 → 联网搜索；
2. 选择 Tavily / Serper / SearXNG 并填入 Key（SearXNG 填地址，通常无需 Key）；
3. 点「测试搜索」确认返回结果；
4. 聊天页打开「联网搜索」，本轮问题会先搜索，最多取 5 条结果交给模型，并在回答下方列出真实来源。

未配置时点击聊天页的「联网搜索」会直接弹出配置引导。搜索失败会自动降级为直接回答并提示。

## 语音

聊天输入框右侧麦克风是**按住说话**：按下开始、松手结束，识别结果会追加到输入框。首次使用会申请麦克风权限。

### 系统语音服务

有系统语音识别服务的机型（小米 / HyperOS、原生 Android 等）直接使用 `SpeechRecognizer`（zh-CN），输入框上方显示“正在听…”和实时文字。

小米 / Redmi 设备注意：系统语音引擎（`小米语音引擎` 或 `com.xiaomi.mibrain.speech`）自身也必须拥有麦克风权限。若提示“系统语音服务缺少麦克风权限”，到：

设置 → 应用 → 小米语音引擎 → 权限 → 麦克风 → 允许

### 没有系统语音服务的机型（vivo 等）

vivo/Funtouch、部分 OPPO/Honor 系统不向第三方 App 提供 `RecognitionService`，只靠系统接口无法识别。此时本 App 会自动改为**本地录音 + 云端转写**：

1. 设置 → 某个 API 配置 → 「语音转写模型（可选）」；
2. 填一个当前服务商支持 `/audio/transcriptions` 的模型，例如：
   - OpenAI：`whisper-1`
   - 硅基流动：`FunAudioLLM/SenseVoiceSmall`（预设会自动填好）
3. 按住麦克风说话，松手后录音会上传到该配置的接口转成文字。

没有配置转写模型时，按住麦克风会提示如何配置；也可以使用输入法的语音键。DeepSeek 官方 API 不提供语音转写。

## 更新

App 内可以直接检查、下载并调起系统安装器升级，不需要再把 APK 手动传到手机。更新不会卸载旧版，聊天记录和设置都会保留（必须使用同一签名证书）。

设置 → 更新：

- **检查更新**：手动查询最新版本；
- **自动检查更新**：启动时静默检查，最多每 6 小时一次；有新版本才弹窗；
- **更新仓库**：默认 `bear561/deepsleep`，可改成任意公开 GitHub 仓库；
- **自定义更新地址**：填写后优先使用自建 JSON，而不是 GitHub；留空关闭。

### 发布新版本（GitHub Releases）

1. 修改 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`（必须比上一版大）；
2. 用**同一个签名证书**构建 `:app:assembleRelease`；
3. 在 GitHub 仓库创建 Release，标签使用版本号（`0.3.0` 或 `v0.3.0` 都可以）；
4. 把 `DeepSleep-0.3.0.apk` 作为 Release 资产上传。

App 会读取 `releases/latest`，自动选择名称以 `DeepSleep` 开头、非 debug 的 `.apk` 资产；更新说明取自 Release 正文。GitHub 只允许公开仓库匿名读取；未登录接口有频率限制，自动检查已限制为每 6 小时一次。

### 自定义更新 JSON

如果使用自己的服务器，`自定义更新地址` 指向的 JSON 格式：

```json
{
  "versionCode": 4,
  "versionName": "0.3.0",
  "apkUrl": "https://example.com/deepsleep/DeepSleep-0.3.0.apk",
  "notes": "更新说明",
  "sizeBytes": 2200000,
  "sha256": "可选，APK 的 SHA-256"
}
```

`apkUrl` 可以写相对路径（相对 JSON 地址解析）。`versionCode` 存在时以它为准，否则比较 `versionName`。

### 安装校验

下载完成后会在本地检查：

- `packageName` 必须是 `com.deepsleep.chat`；
- 被下载 APK 的 `versionCode` 必须高于当前版本；
- 签名必须与当前已安装的 App 一致（不同证书的包系统也不会允许覆盖安装）；
- 如果清单提供了 SHA-256，会先校验文件摘要。

校验通过后调用系统安装器，需要在系统设置中允许本应用「安装未知应用」。安装界面由系统提供，本 App 不静默安装。

## 隐私与安全

- 无账号、无云同步、无埋点、无后台服务；
- 对话在本地 Room 数据库；API Key 在 Android Keystore 加密后存 DataStore；
- `allowBackup=false`，禁止系统把密文备份到其他设备；
- 联网只发生在：你主动发消息时的模型 API、你开启联网搜索时的搜索 API、你点开来源链接时、你检查/下载更新时（GitHub 或自定义更新地址）；
- 备份文件包含聊天内容，请妥善保管。

## 已知限制

- DeepSeek 官方备份格式按开源 chat-demo 的 export v3 schema 实现；如果官方后续改版本，需要适配；
- 实时 Markdown 会边生成边重新排版，低端机上大段代码/表格可能略有抖动；生成结束后会做最终渲染；
- 第三方 API 的 reasoning 字段、SSE 细节、参数支持并不完全一致，已做常见兼容与中文错误提示；
- 公式只显示 TeX 原文；代码无语法高亮；
- 不支持 PDF/DOCX/本地图片 OCR；图片会原样发给模型，能否理解取决于所选模型是否支持视觉；
- 部分第三方模型在服务端强制思考（无论 App 开关）。此时关闭深度思考只能隐藏思维链，无法阻止服务端实际推理；要真正关闭请改用非思考模型，或使用该服务商提供的禁用参数。
- 自动更新使用 GitHub 匿名接口，受 GitHub 频率限制；私有仓库需要改用自定义更新地址。
- 更新只能在同签名证书的版本之间覆盖安装；更换签名后只能卸载重装，本地数据会丢失。
- 本 App 与 DeepSeek 官方无关联，不使用其名称、Logo 或商标。

## 开发

```
app/src/main/java/com/deepsleep/chat/
├─ data/           Room、DataStore、Keystore、OpenAI/SSE、搜索、备份、更新
├─ domain/model/   领域模型
├─ ui/             chat / settings / backup / update / theme / navigation
└─ util/           提示词、token 估算、模型配置解析
```

关键实现：

- SSE 解析：`data/remote/openai/SseParser.kt`、`OpenAiChatClient.kt`
- 提示词裁剪：`util/PromptBuilder.kt`
- 备份格式：`data/backup/BackupRepository.kt`
- 更新检查与下载：`data/update/UpdateRepository.kt`、`UpdateManager.kt`
- 更新弹窗：`ui/update/UpdateHost.kt`
- 聊天状态机：`ui/chat/ChatViewModel.kt`

测试：

```bat
gradlew.bat :app:testDebugUnitTest
```