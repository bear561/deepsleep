package com.deepsleep.chat.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deepsleep.chat.di.AppContainer
import com.deepsleep.chat.ui.backup.BackupScreen
import com.deepsleep.chat.ui.chat.ChatScreen
import com.deepsleep.chat.ui.settings.ProviderEditScreen
import com.deepsleep.chat.ui.settings.SearchSettingsScreen
import com.deepsleep.chat.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val PROVIDER_EDIT = "provider/{providerId}"
    const val BACKUP = "backup"
    const val SEARCH_SETTINGS = "search_settings"

    fun chat(conversationId: String): String = "chat/$conversationId"
    fun newChat(): String = "chat/new"
    fun providerEdit(providerId: String): String = "provider/$providerId"
}

@Composable
fun DeepSleepApp(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.newChat()) {
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                    defaultValue = "new"
                },
            ),
        ) { entry ->
            val conversationId = entry.arguments?.getString("conversationId")?.takeUnless { it == "new" }
            ChatScreen(
                container = container,
                conversationId = conversationId,
                onOpenConversation = { id ->
                    navController.navigate(Routes.chat(id)) {
                        launchSingleTop = true
                    }
                },
                onNewChatRoute = {
                    navController.navigate(Routes.newChat())
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onAddProvider = { navController.navigate(Routes.providerEdit("new")) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenSearchSettings = { navController.navigate(Routes.SEARCH_SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onAddProvider = { navController.navigate(Routes.providerEdit("new")) },
                onEditProvider = { id -> navController.navigate(Routes.providerEdit(id)) },
                onOpenSearchSettings = { navController.navigate(Routes.SEARCH_SETTINGS) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
            )
        }
        composable(
            route = Routes.PROVIDER_EDIT,
            arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
        ) { entry ->
            val providerId = entry.arguments?.getString("providerId").orEmpty()
            ProviderEditScreen(
                container = container,
                providerId = providerId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BACKUP) {
            BackupScreen(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH_SETTINGS) {
            SearchSettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
            )
        }
    }
}