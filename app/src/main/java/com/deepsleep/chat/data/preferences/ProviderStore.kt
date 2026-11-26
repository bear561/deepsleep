package com.deepsleep.chat.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.deepsleep.chat.domain.model.Provider
import com.deepsleep.chat.domain.model.StoredProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ProviderStore(
    private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val providersKey = stringPreferencesKey("providers_json")
    private val serializer = ListSerializer(StoredProvider.serializer())

    val providers: Flow<List<Provider>> = context.appDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            val stored = decode(prefs[providersKey])
            stored.map { it.toDomain(hasKey = prefs.asMap().containsKey(providerKey(it.id))) }
        }

    suspend fun upsert(provider: Provider, apiKey: String?) {
        context.appDataStore.edit { prefs ->
            val current = decode(prefs[providersKey]).toMutableList()
            val index = current.indexOfFirst { it.id == provider.id }
            val isFirst = current.isEmpty()
            var incoming = provider.toStored(isDefault = provider.isDefault || isFirst)
            if (incoming.isDefault) {
                for (i in current.indices) {
                    current[i] = current[i].copy(isDefault = false)
                }
            }
            if (index >= 0) current[index] = incoming else current.add(incoming)
            if (current.none { it.isDefault }) {
                current[0] = current[0].copy(isDefault = true)
            }
            prefs[providersKey] = json.encodeToString(serializer, current)

            when {
                apiKey == null -> Unit
                apiKey.isBlank() -> prefs.remove(providerKey(provider.id))
                else -> secureKeyStore.encrypt(apiKey)?.let { prefs[providerKey(provider.id)] = it }
            }
        }
    }

    suspend fun delete(providerId: String) {
        context.appDataStore.edit { prefs ->
            val current = decode(prefs[providersKey]).filterNot { it.id == providerId }
            val normalized = if (current.isNotEmpty() && current.none { it.isDefault }) {
                listOf(current.first().copy(isDefault = true)) + current.drop(1)
            } else {
                current
            }
            prefs[providersKey] = json.encodeToString(serializer, normalized)
            prefs.remove(providerKey(providerId))
        }
    }

    suspend fun setDefault(providerId: String) {
        context.appDataStore.edit { prefs ->
            val current = decode(prefs[providersKey]).map {
                it.copy(isDefault = it.id == providerId)
            }
            prefs[providersKey] = json.encodeToString(serializer, current)
        }
    }

    suspend fun apiKey(providerId: String): String? {
        val prefs = context.appDataStore.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .first()
        return secureKeyStore.decrypt(prefs[providerKey(providerId)])
    }

    private fun decode(raw: String?): List<StoredProvider> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun providerKey(id: String) = stringPreferencesKey("provider_key_$id")

    private fun StoredProvider.toDomain(hasKey: Boolean) = Provider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        models = models,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        transcriptionModelId = transcriptionModelId,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = extraHeaders,
        isDefault = isDefault,
        hasApiKey = hasKey,
    )

    private fun Provider.toStored(isDefault: Boolean) = StoredProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        models = models,
        chatModelId = chatModelId,
        reasoningModelId = reasoningModelId,
        transcriptionModelId = transcriptionModelId,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        extraHeaders = extraHeaders,
        isDefault = isDefault,
    )
}