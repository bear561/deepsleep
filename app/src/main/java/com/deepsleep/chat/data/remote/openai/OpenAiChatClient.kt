package com.deepsleep.chat.data.remote.openai

import com.deepsleep.chat.data.remote.HttpClients
import com.deepsleep.chat.domain.model.ModelConfig
import com.deepsleep.chat.domain.model.PromptMessage
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.TokenUsage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

sealed interface StreamEvent {
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ContentDelta(val text: String) : StreamEvent
    data class UsageUpdated(val usage: TokenUsage) : StreamEvent
    data class Finished(val finishReason: String?) : StreamEvent
    data class Failed(val error: ChatFailure) : StreamEvent
}

data class ConnectionTestResult(
    val ok: Boolean,
    val message: String,
    val models: List<ModelConfig> = emptyList(),
)

class OpenAiChatClient(
    private val clients: HttpClients,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun stream(
        provider: Provider,
        apiKey: String?,
        modelId: String,
        messages: List<PromptMessage>,
    ): Flow<StreamEvent> = flow {
        val bases = candidateBases(provider.baseUrl)
        var includeUsage = true
        var lastFailure: ChatFailure? = null

        outer@ for ((baseIndex, base) in bases.withIndex()) {
            var attempt = 0
            retryLoop@ while (attempt <= MAX_RETRIES) {
                currentCoroutineContext().ensureActive()
                val request = buildChatRequest(
                    baseUrl = base,
                    provider = provider,
                    apiKey = apiKey,
                    modelId = modelId,
                    messages = messages,
                    includeUsage = includeUsage,
                )
                val call = clients.streaming.newCall(request)
                val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
                var response: Response? = null
                try {
                    response = call.execute()
                    if (!response.isSuccessful) {
                        val code = response.code
                        val raw = response.body?.string().orEmpty().take(4_000)
                        val failure = ErrorMapper.fromHttp(code, raw)
                        lastFailure = failure
                        response.close()
                        response = null

                        if (code == 400 && includeUsage && raw.contains("stream_options", ignoreCase = true)) {
                            includeUsage = false
                            continue@retryLoop
                        }
                        if (code == 404 && baseIndex < bases.lastIndex) {
                            continue@outer
                        }
                        if (failure.retryable && attempt < MAX_RETRIES) {
                            attempt++
                            delay(1_000L * attempt)
                            continue@retryLoop
                        }
                        emit(StreamEvent.Failed(failure))
                        return@flow
                    }

                    val body = response.body
                    if (body == null) {
                        emit(StreamEvent.Failed(ChatFailure("服务器返回了空响应")))
                        return@flow
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("json", ignoreCase = true) &&
                        !contentType.contains("event-stream", ignoreCase = true)
                    ) {
                        val raw = body.string()
                        if (raw.trimStart().startsWith("data:")) {
                            consumeSse(SseParser(Buffer().writeUtf8(raw)))
                        } else {
                            consumeNonStream(raw)
                        }
                        return@flow
                    }

                    consumeSse(SseParser(body.source()))
                    return@flow
                } catch (t: Throwable) {
                    if (!currentCoroutineContext().isActive) return@flow
                    val failure = ErrorMapper.fromException(t)
                    lastFailure = failure
                    if (failure.retryable && attempt < MAX_RETRIES) {
                        attempt++
                        delay(1_000L * attempt)
                        continue@retryLoop
                    }
                    emit(StreamEvent.Failed(failure))
                    return@flow
                } finally {
                    cancellation.dispose()
                    response?.close()
                    call.cancel()
                }
            }
        }

        emit(StreamEvent.Failed(lastFailure ?: ChatFailure("无法连接到服务器")))
    }.flowOn(Dispatchers.IO)

    suspend fun listModels(provider: Provider, apiKey: String?): List<ModelConfig> =
        withContext(Dispatchers.IO) {
            val bases = candidateBases(provider.baseUrl)
            var lastFailure: ChatFailure? = null
            for ((index, base) in bases.withIndex()) {
                val request = buildModelsRequest(base, provider, apiKey)
                try {
                    clients.standard.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val failure = ErrorMapper.fromHttp(response.code, raw)
                            lastFailure = failure
                            if (response.code == 404 && index < bases.lastIndex) throw RetryNextBase
                            throw failure
                        }
                        return@withContext parseModels(raw)
                    }
                } catch (retry: RetryNextBase) {
                    continue
                } catch (t: Throwable) {
                    if (t is ChatFailure) throw t
                    throw ErrorMapper.fromException(t)
                }
            }
            throw lastFailure ?: ChatFailure("无法获取模型列表")
        }

    suspend fun testConnection(provider: Provider, apiKey: String?): ConnectionTestResult {
        return try {
            val models = listModels(provider, apiKey)
            ConnectionTestResult(
                ok = true,
                message = if (models.isEmpty()) "连接成功，但服务没有返回模型列表" else "连接成功，发现 ${models.size} 个模型",
                models = models,
            )
        } catch (failure: ChatFailure) {
            if (failure.httpCode == 404) {
                ConnectionTestResult(
                    ok = false,
                    message = "无法访问 /models（HTTP 404）。部分服务不提供该接口，可手动填写模型 ID 后直接发送消息测试。",
                )
            } else {
                ConnectionTestResult(ok = false, message = failure.userMessage)
            }
        }
    }

    private suspend fun FlowCollector<StreamEvent>.consumeSse(parser: SseParser) {
        var finishReason: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val data = parser.nextData() ?: break
            if (data.trim() == "[DONE]") break
            val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue

            extractStreamError(root)?.let {
                emit(StreamEvent.Failed(it))
                return
            }

            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val delta = choice?.get("delta") as? JsonObject
            val reasoning = delta?.get("reasoning_content").textValue()
                ?: delta?.get("reasoning").textValue()
                ?: delta?.get("thinking").textValue()
            if (!reasoning.isNullOrEmpty()) emit(StreamEvent.ReasoningDelta(reasoning))
            val content = delta?.get("content").textValue()
            if (!content.isNullOrEmpty()) emit(StreamEvent.ContentDelta(content))
            choice?.get("finish_reason").stringValue()?.let { finishReason = it }
            parseUsage(root)?.let { emit(StreamEvent.UsageUpdated(it)) }
        }
        emit(StreamEvent.Finished(finishReason))
    }

    private suspend fun FlowCollector<StreamEvent>.consumeNonStream(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: throw ChatFailure("服务器返回了无法解析的响应")
        extractStreamError(root)?.let { throw it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val reasoning = message?.get("reasoning_content").textValue()
            ?: message?.get("reasoning").textValue()
        val content = message?.get("content").textValue()
            ?: choice?.get("text").textValue()
        if (!reasoning.isNullOrEmpty()) emit(StreamEvent.ReasoningDelta(reasoning))
        if (!content.isNullOrEmpty()) emit(StreamEvent.ContentDelta(content))
        parseUsage(root)?.let { emit(StreamEvent.UsageUpdated(it)) }
        emit(StreamEvent.Finished(choice?.get("finish_reason").stringValue()))
    }

    private fun buildChatRequest(
        baseUrl: String,
        provider: Provider,
        apiKey: String?,
        modelId: String,
        messages: List<PromptMessage>,
        includeUsage: Boolean,
    ): Request {
        val payload = buildJsonObject {
            put("model", modelId)
            putJsonArray("messages") {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message.role)
                        put("content", message.content)
                    }
                }
            }
            put("stream", true)
            if (includeUsage) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            provider.temperature?.let { put("temperature", it) }
            provider.maxTokens?.let { put("max_tokens", it) }
        }
        val builder = Request.Builder()
            .url(UrlNormalizer.chatUrl(baseUrl))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        applyAuthAndHeaders(builder, provider, apiKey)
        return builder.build()
    }

    private fun buildModelsRequest(baseUrl: String, provider: Provider, apiKey: String?): Request {
        val builder = Request.Builder()
            .url(UrlNormalizer.modelsUrl(baseUrl))
            .header("Accept", "application/json")
            .get()
        applyAuthAndHeaders(builder, provider, apiKey)
        return builder.build()
    }

    private fun applyAuthAndHeaders(builder: Request.Builder, provider: Provider, apiKey: String?) {
        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        provider.extraHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
        }
    }

    private fun parseModels(raw: String): List<ModelConfig> {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: throw ChatFailure("模型列表无法解析")
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["data"] as? JsonArray ?: element["models"] as? JsonArray
            else -> null
        } ?: throw ChatFailure("接口没有返回模型列表")
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj["id"].stringValue()
                ?: obj["name"].stringValue()
                ?: return@mapNotNull null
            val display = obj["name"].stringValue() ?: id
            ModelConfig(
                id = id,
                displayName = display,
                supportsReasoning = looksLikeReasoningModel(id),
            )
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun candidateBases(baseUrl: String): List<String> = buildList {
        add(baseUrl)
        UrlNormalizer.v1Fallback(baseUrl)?.let { add(it) }
    }

    private fun parseUsage(root: JsonObject): TokenUsage? {
        val usage = root["usage"] as? JsonObject ?: return null
        val prompt = usage["prompt_tokens"].intValue() ?: 0
        val completion = usage["completion_tokens"].intValue() ?: 0
        val total = usage["total_tokens"].intValue() ?: (prompt + completion)
        val reasoning = (usage["completion_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens").intValue() ?: 0
        if (prompt == 0 && completion == 0 && total == 0) return null
        return TokenUsage(prompt, completion, total, reasoning)
    }

    private fun extractStreamError(root: JsonObject): ChatFailure? {
        val error = root["error"]
        if (error == null || error is JsonNull) return null
        val message = when (error) {
            is JsonObject -> error["message"].stringValue()
                ?: error["code"].stringValue()
                ?: error.toString().take(300)
            is JsonPrimitive -> error.contentOrNull
            else -> error.toString().take(300)
        } ?: "服务端返回错误"
        return ChatFailure("模型服务返回错误：${message.take(300)}", retryable = false)
    }

    private fun looksLikeReasoningModel(id: String): Boolean = listOf(
        "reasoner", "reasoning", "r1", "o1", "o3", "o4", "qwq", "thinking",
    ).any { id.contains(it, ignoreCase = true) }

    private fun JsonElement?.textValue(): String? = when (this) {
        is JsonPrimitive -> if (isString) content else null
        is JsonArray -> joinToString("") { part ->
            (part as? JsonObject)?.get("text").stringValue() ?: ""
        }
        else -> null
    }

    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.intValue(): Int? =
        (this as? JsonPrimitive)?.intOrNull

    private object RetryNextBase : IOException()

    private companion object {
        const val MAX_RETRIES = 2
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}