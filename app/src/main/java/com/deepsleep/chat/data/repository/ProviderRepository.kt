package com.deepsleep.chat.data.repository

import com.deepsleep.chat.data.preferences.ProviderStore
import com.deepsleep.chat.domain.model.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ProviderRepository(
    private val store: ProviderStore,
) {
    val providers: Flow<List<Provider>> = store.providers

    fun observeProvider(id: String): Flow<Provider?> = store.providers.map { list ->
        list.firstOrNull { it.id == id }
    }

    suspend fun upsert(provider: Provider, apiKey: String?) = store.upsert(provider, apiKey)

    suspend fun delete(providerId: String) = store.delete(providerId)

    suspend fun setDefault(providerId: String) = store.setDefault(providerId)

    suspend fun apiKey(providerId: String): String? = store.apiKey(providerId)

    suspend fun defaultProvider(): Provider? {
        val list = providers.first()
        return list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }

    suspend fun resolveProvider(providerId: String?): Provider? {
        val list = providers.first()
        return list.firstOrNull { it.id == providerId }
            ?: list.firstOrNull { it.isDefault }
            ?: list.firstOrNull()
    }
}