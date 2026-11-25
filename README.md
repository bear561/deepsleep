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
- 系统语音输入（直接使用 `SpeechRecognizer`，带麦克风权限请求和聆听状态；小米设备需系统语音引擎有麦克风权限）
- DeepSeek 官方备份 ZIP（format v3）导入/导出
- 本 App 自身完整备份（不含 API Key）
- 深色/浅色/跟随系统主题、token 用量展示、复制/分享消息
- API Key 使用 Android Keystore AES-GCM 加密后保存；禁止系统备份导出

## 明确不做（轻量取舍）

PDF/DOCX 解析、图片 OCR、LaTeX 排版、代码语法高亮、登录/云同步、在线更新、推送、埋点。官方备份中的图片只保留文件名，不导入图片正文；附件在官方格式导出时也只写元数据（完整恢复请用本 App 备份）。

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

- 包含：所有对话、消息、思考内容、来源、附件文本、设置、Provider（不含 API Key）；
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

聊天输入框右侧麦克风使用系统 `SpeechRecognizer`（zh-CN），首次使用会申请麦克风权限，输入框上方会显示“正在听…”和实时识别文字。

小米 / Redmi 设备注意：系统语音引擎（`小米语音引擎` 或 `com.xiaomi.mibrain.speech`）自身也必须拥有麦克风权限。若提示“系统语音服务缺少麦克风权限”，到：

设置 → 应用 → 小米语音引擎 → 权限 → 麦克风 → 允许

然后回到 App 再试。部分没有可用语音服务的系统会提示使用输入法语音键。

## 隐私与安全

- 无账号、无云同步、无埋点、无后台服务；
- 对话在本地 Room 数据库；API Key 在 Android Keystore 加密后存 DataStore；
- `allowBackup=false`，禁止系统把密文备份到其他设备；
- 联网只发生在：你主动发消息时的模型 API、你开启联网搜索时的搜索 API、你点开来源链接时；
- 备份文件包含聊天内容，请妥善保管。

## 已知限制

- DeepSeek 官方备份格式按开源 chat-demo 的 export v3 schema 实现；如果官方后续改版本，需要适配；
- 实时 Markdown 会边生成边重新排版，低端机上大段代码/表格可能略有抖动；生成结束后会做最终渲染；
- 第三方 API 的 reasoning 字段、SSE 细节、参数支持并不完全一致，已做常见兼容与中文错误提示；
- 公式只显示 TeX 原文；代码无语法高亮；
- 不支持 PDF/DOCX/图片 OCR；
- 部分第三方模型在服务端强制思考（无论 App 开关）。此时关闭深度思考只能隐藏思维链，无法阻止服务端实际推理；要真正关闭请改用非思考模型，或使用该服务商提供的禁用参数。
- 本 App 与 DeepSeek 官方无关联，不使用其名称、Logo 或商标。

## 开发

```
app/src/main/java/com/deepsleep/chat/
├─ data/           Room、DataStore、Keystore、OpenAI/SSE、搜索、备份
├─ domain/model/   领域模型
├─ ui/             chat / settings / backup / theme / navigation
└─ util/           提示词、token 估算、模型配置解析
```

关键实现：

- SSE 解析：`data/remote/openai/SseParser.kt`、`OpenAiChatClient.kt`
- 提示词裁剪：`util/PromptBuilder.kt`
- 备份格式：`data/backup/BackupRepository.kt`
- 聊天状态机：`ui/chat/ChatViewModel.kt`

测试：

```bat
gradlew.bat :app:testDebugUnitTest
```