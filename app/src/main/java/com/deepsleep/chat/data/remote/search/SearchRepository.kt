package com.deepsleep.chat.data.remote.search

import com.deepsleep.chat.data.remote.HttpClients
import com.deepsleep.chat.domain.model.SearchConfig
import com.deepsleep.chat.domain.model.SearchProviderType
import com.deepsleep.chat.domain.model.SearchSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SearchRepository(
    private val clients: HttpClients,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(
        config: SearchConfig,
        apiKey: String?,
        query: String,
        count: Int = config.maxResults,
    ): Result<List<SearchSource>> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.isBlank()) throw IOException("搜索内容为空")
            val limit = count.coerceIn(1, 10)
            when (config.type) {
                SearchProviderType.TAVILY -> searchTavily(apiKey, query, limit)
                SearchProviderType.SERPER -> searchSerper(apiKey, query, limit)
                SearchProviderType.SEARXNG -> searchSearxng(config.baseUrl, query, limit)
            }
        }
    }

    private fun searchTavily(apiKey: String?, query: String, limit: Int): List<SearchSource> {
        requireKey(apiKey)
        val payload = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", limit)
            put("search_depth", "basic")
            put("include_answer", false)
            put("include_raw_content", false)
        }
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val root = execute(request)
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].str() ?: return@mapNotNull null
            SearchSource(
                title = obj["title"].str() ?: url,
                url = url,
                snippet = obj["content"].str().orEmpty(),
            )
        }.take(limit)
    }

    private fun searchSerper(apiKey: String?, query: String, limit: Int): List<SearchSource> {
        requireKey(apiKey)
        val payload = buildJsonObject {
            put("q", query)
            put("num", limit)
        }
        val request = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", apiKey!!)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val root = execute(request)
        val results = root["organic"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["link"].str() ?: return@mapNotNull null
            SearchSource(
                title = obj["title"].str() ?: url,
                url = url,
                snippet = obj["snippet"].str().orEmpty(),
            )
        }.take(limit)
    }

    private fun searchSearxng(baseUrl: String, query: String, limit: Int): List<SearchSource> {
        if (baseUrl.isBlank()) throw IOException("请先在设置中填写 SearXNG 地址")
        val base = baseUrl.trim().trimEnd('/')
        val url = "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("language", "zh-CN")
            .addQueryParameter("safesearch", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val root = execute(request)
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val urlValue = obj["url"].str() ?: return@mapNotNull null
            SearchSource(
                title = obj["title"].str() ?: urlValue,
                url = urlValue,
                snippet = obj["content"].str() ?: obj["snippet"].str().orEmpty(),
            )
        }.take(limit)
    }

    private fun execute(request: Request): JsonObject {
        clients.search.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    val root = json.parseToJsonElement(body).jsonObject
                    (root["error"] as? JsonObject)?.get("message")?.str()
                        ?: root["message"]?.str()
                        ?: root["error"]?.str()
                }.getOrNull()
                throw IOException("搜索失败（HTTP ${response.code}）${message?.let { "：$it" }.orEmpty()}")
            }
            if (body.isBlank()) return JsonObject(emptyMap())
            return runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("搜索接口返回了无法解析的内容") }
        }
    }

    private fun requireKey(key: String?) {
        if (key.isNullOrBlank()) throw IOException("未配置搜索 API Key")
    }

    private fun kotlinx.serialization.json.JsonElement?.str(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}