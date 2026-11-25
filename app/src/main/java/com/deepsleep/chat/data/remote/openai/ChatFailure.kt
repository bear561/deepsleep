package com.deepsleep.chat.data.remote.openai

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatFailure(
    val userMessage: String,
    val httpCode: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

object ErrorMapper {

    fun fromHttp(code: Int, rawBody: String): ChatFailure {
        val serverMessage = extractServerMessage(rawBody)
        val suffix = serverMessage?.let { "：$it" }.orEmpty()
        val message = when (code) {
            300, 301, 302, 303, 307, 308 -> "接口发生重定向，请把 Base URL 改成最终地址"
            400 -> "请求参数不被接受$suffix（也可能是上下文过长或模型 ID 不存在）"
            401 -> "API Key 无效或已过期$suffix"
            402 -> "账户余额不足$suffix"
            403 -> "没有权限访问该接口或模型$suffix"
            404 -> "接口地址或模型不存在，请检查 Base URL 和模型 ID$suffix"
            408 -> "请求超时，请检查网络后重试"
            429 -> "请求过于频繁或额度受限，请稍后再试$suffix"
            in 500..599 -> "服务商暂时不可用（HTTP $code）$suffix"
            else -> "请求失败（HTTP $code）$suffix"
        }
        return ChatFailure(
            userMessage = message,
            httpCode = code,
            retryable = code == 408 || code == 429 || code >= 500,
        )
    }

    fun fromException(throwable: Throwable): ChatFailure {
        if (throwable is ChatFailure) return throwable
        val message = when (throwable) {
            is UnknownHostException -> "无法解析服务器域名，请检查网络和 Base URL"
            is ConnectException -> "连接被拒绝，请检查 Base URL、端口和网络"
            is SocketTimeoutException -> "连接超时，请检查网络后重试"
            is SSLException -> "TLS 连接失败，请检查网络环境或改用 HTTPS"
            is IOException -> "网络异常：${throwable.message?.take(160) ?: "请稍后重试"}"
            else -> "请求失败：${throwable.message?.take(160) ?: throwable.javaClass.simpleName}"
        }
        return ChatFailure(
            userMessage = message,
            retryable = throwable is IOException,
            cause = throwable,
        )
    }

    fun extractServerMessage(rawBody: String): String? {
        if (rawBody.isBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(rawBody).jsonObject
            val error = root["error"] as? JsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: root["detail"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.take(300)
            ?: rawBody.takeIf { it.length < 300 }?.trim()
    }
}