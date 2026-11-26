package com.deepsleep.chat.di

import android.content.Context
import androidx.room.Room
import com.deepsleep.chat.data.backup.BackupRepository
import com.deepsleep.chat.data.local.AppDatabase
import com.deepsleep.chat.data.preferences.ProviderStore
import com.deepsleep.chat.data.preferences.SecureKeyStore
import com.deepsleep.chat.data.preferences.SettingsStore
import com.deepsleep.chat.data.remote.HttpClients
import com.deepsleep.chat.data.remote.openai.OpenAiChatClient
import com.deepsleep.chat.data.remote.search.SearchRepository
import com.deepsleep.chat.data.repository.AttachmentRepository
import com.deepsleep.chat.data.repository.ChatRepository
import com.deepsleep.chat.data.repository.ConversationRepository
import com.deepsleep.chat.data.repository.ProviderRepository
import com.deepsleep.chat.data.repository.SettingsRepository
import com.deepsleep.chat.data.update.UpdateManager
import com.deepsleep.chat.data.update.UpdateRepository

/**
 * Manual dependency container. Kept intentionally small: no Hilt, no reflection,
 * so release builds stay lean and startup stays fast.
 */
class AppContainer(
    private val appContext: Context,
) {
    val context: Context get() = appContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "deepsleep.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    private val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore() }

    private val providerStore: ProviderStore by lazy {
        ProviderStore(appContext, secureKeyStore)
    }

    private val settingsStore: SettingsStore by lazy {
        SettingsStore(appContext, secureKeyStore)
    }

    private val httpClients: HttpClients by lazy { HttpClients() }

    private val openAiChatClient: OpenAiChatClient by lazy {
        OpenAiChatClient(httpClients)
    }

    val providerRepository: ProviderRepository by lazy {
        ProviderRepository(providerStore)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsStore)
    }

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(database)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(openAiChatClient)
    }

    val searchRepository: SearchRepository by lazy {
        SearchRepository(httpClients)
    }

    val attachmentRepository: AttachmentRepository by lazy {
        AttachmentRepository(appContext)
    }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepository(appContext, httpClients)
    }

    val updateManager: UpdateManager by lazy {
        UpdateManager(appContext, updateRepository, settingsRepository)
    }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(
            context = appContext,
            conversationRepository = conversationRepository,
            providerRepository = providerRepository,
            settingsRepository = settingsRepository,
            attachmentRepository = attachmentRepository,
        )
    }
}