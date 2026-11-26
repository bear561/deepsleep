package com.deepsleep.chat.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.deepsleep.chat.domain.model.AppSettings
import com.deepsleep.chat.domain.model.SearchConfig
import com.deepsleep.chat.domain.model.SearchProviderType
import com.deepsleep.chat.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class SettingsStore(
    private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val themeKey = stringPreferencesKey("theme_mode")
    private val defaultProviderKey = stringPreferencesKey("default_provider_id")
    private val contextLimitKey = intPreferencesKey("context_token_limit")
    private val autoTitleKey = booleanPreferencesKey("auto_title")
    private val searchConfigKey = stringPreferencesKey("search_config")
    private val searchApiKeyKey = stringPreferencesKey("search_api_key")
    private val onboardingKey = booleanPreferencesKey("onboarding_completed")
    private val updateRepoKey = stringPreferencesKey("update_repo")
    private val updateManifestUrlKey = stringPreferencesKey("update_manifest_url")
    private val autoCheckUpdatesKey = booleanPreferencesKey("auto_check_updates")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check_at")

    val settings: Flow<AppSettings> = context.appDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                defaultProviderId = prefs[defaultProviderKey],
                contextTokenLimit = prefs[contextLimitKey] ?: 32_000,
                autoTitle = prefs[autoTitleKey] ?: false,
                search = prefs[searchConfigKey]?.let {
                    runCatching { json.decodeFromString(SearchConfig.serializer(), it) }.getOrNull()
                } ?: SearchConfig(),
                searchHasKey = prefs[searchApiKeyKey] != null,
                onboardingCompleted = prefs[onboardingKey] ?: false,
                updateRepo = prefs[updateRepoKey]
                    ?.takeIf { it.isNotBlank() }
                    ?: AppSettings.DEFAULT_UPDATE_REPO,
                updateManifestUrl = prefs[updateManifestUrlKey].orEmpty(),
                autoCheckUpdates = prefs[autoCheckUpdatesKey] ?: true,
                lastUpdateCheckAt = prefs[lastUpdateCheckKey] ?: 0L,
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDefaultProvider(providerId: String?) {
        context.appDataStore.edit { prefs ->
            if (providerId == null) prefs.remove(defaultProviderKey) else prefs[defaultProviderKey] = providerId
        }
    }

    suspend fun setContextTokenLimit(limit: Int) {
        context.appDataStore.edit { it[contextLimitKey] = limit.coerceIn(2_000, 1_000_000) }
    }

    suspend fun setAutoTitle(enabled: Boolean) {
        context.appDataStore.edit { it[autoTitleKey] = enabled }
    }

    suspend fun setSearchConfig(config: SearchConfig) {
        context.appDataStore.edit { it[searchConfigKey] = json.encodeToString(SearchConfig.serializer(), config) }
    }

    /** Pass a blank string to clear the saved key. */
    suspend fun setSearchApiKey(key: String) {
        context.appDataStore.edit { prefs ->
            if (key.isBlank()) {
                prefs.remove(searchApiKeyKey)
            } else {
                secureKeyStore.encrypt(key)?.let { prefs[searchApiKeyKey] = it }
            }
        }
    }

    suspend fun searchApiKey(): String? {
        val prefs = context.appDataStore.data.catch { emit(emptyPreferences()) }.first()
        return secureKeyStore.decrypt(prefs[searchApiKeyKey])
    }

    suspend fun setOnboardingCompleted() {
        context.appDataStore.edit { it[onboardingKey] = true }
    }

    suspend fun setUpdateRepo(repo: String) {
        context.appDataStore.edit { it[updateRepoKey] = repo.trim() }
    }

    suspend fun setUpdateManifestUrl(url: String) {
        context.appDataStore.edit { prefs ->
            val value = url.trim()
            if (value.isEmpty()) prefs.remove(updateManifestUrlKey) else prefs[updateManifestUrlKey] = value
        }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.appDataStore.edit { it[autoCheckUpdatesKey] = enabled }
    }

    suspend fun setLastUpdateCheckAt(timestamp: Long) {
        context.appDataStore.edit { it[lastUpdateCheckKey] = timestamp }
    }

    companion object {
        val SEARCH_TYPES = SearchProviderType.entries
    }
}