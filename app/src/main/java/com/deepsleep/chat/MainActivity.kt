package com.deepsleep.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepsleep.chat.domain.model.AppSettings
import com.deepsleep.chat.domain.model.ThemeMode
import com.deepsleep.chat.ui.navigation.DeepSleepApp
import com.deepsleep.chat.ui.theme.DeepSleepTheme
import com.deepsleep.chat.ui.update.UpdateHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DeepSleepApplication).container
        setContent {
            val settings by container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DeepSleepTheme(darkTheme = darkTheme) {
                Box {
                    DeepSleepApp(container)
                    UpdateHost(container)
                }
            }
        }
    }
}