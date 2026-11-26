package com.deepsleep.chat.data.repository

import com.deepsleep.chat.data.preferences.SettingsStore
import com.deepsleep.chat.domain.model.AppSettings
import com.deepsleep.chat.domain.model.SearchConfig
import com.deepsleep.chat.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

class SettingsRepository(
    private val store: SettingsStore,
) {
    val settings: Flow<AppSettings> = store.settings

    suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)
    suspend fun setDefaultProvider(providerId: String?) = store.setDefaultProvider(providerId)
    suspend fun setContextTokenLimit(limit: Int) = store.setContextTokenLimit(limit)
    suspend fun setAutoTitle(enabled: Boolean) = store.setAutoTitle(enabled)
    suspend fun setSearchConfig(config: SearchConfig) = store.setSearchConfig(config)
    suspend fun setSearchApiKey(key: String) = store.setSearchApiKey(key)
    suspend fun searchApiKey(): String? = store.searchApiKey()
    suspend fun setOnboardingCompleted() = store.setOnboardingCompleted()
    suspend fun setUpdateRepo(repo: String) = store.setUpdateRepo(repo)
    suspend fun setUpdateManifestUrl(url: String) = store.setUpdateManifestUrl(url)
    suspend fun setAutoCheckUpdates(enabled: Boolean) = store.setAutoCheckUpdates(enabled)
    suspend fun setLastUpdateCheckAt(timestamp: Long) = store.setLastUpdateCheckAt(timestamp)
}